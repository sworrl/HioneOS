package com.hione.update.ota

import android.content.Context
import android.util.Log
import java.io.File
import java.security.cert.CertificateFactory
import java.util.zip.ZipFile

/**
 * How a full system image gets onto the device. Two paths:
 *
 *  - [AbUpdateEnginePath]: on the device, through update_engine. The manifest's system_update
 *    lists payload.bin files (built by hioneos/ota/ab_payload.py, signed with a HioneOS payload key);
 *    Hione Update downloads one into /data/ota_package, checks its SHA-256 against the signed
 *    manifest and hands it to update_engine, which writes the inactive slot. A restart switches
 *    slots. If the new slot fails before Android marks it good, the bootloader goes back to the
 *    old one. [AbUpdater] runs it.
 *  - [WebInstallerPath]: a PC and the WebUSB installer. Always there, and the only way for an
 *    image that does not trust the HioneOS payload keys yet (anything before the first image built
 *    with HIONEOS_AB_OTA=1).
 */
interface SystemUpdatePath {
    /** Short label for the action button. */
    val actionLabel: String
    fun isAvailable(ctx: Context): Boolean
}

/** Full images go on from a PC through the WebUSB installer. */
class WebInstallerPath(val installerUrl: String, val build: String) : SystemUpdatePath {
    override val actionLabel = "Open the web installer"
    override fun isAvailable(ctx: Context) = true

    /** The link carries the target build so the page can preselect it. */
    fun link(): String {
        val sep = if (installerUrl.contains('?')) '&' else '?'
        return "$installerUrl${sep}build=${java.net.URLEncoder.encode(build, "UTF-8")}"
    }
}

/**
 * On-device A/B through update_engine.
 *
 * Facts this relies on (checked on the M500, HioneOS 0.3.0, 2026-10-10):
 *  - ro.build.ab_update=true, Virtual A/B with compression (snapuserd in init_boot's ramdisk),
 *    update_engine running, boot control HAL 1.2. HiBy shipped their own 1.00 and 1.20 updates
 *    this way, so the boot chain handles slot switches.
 *  - update_engine checks payload signatures against /system/etc/security/otacerts.zip. Stock
 *    images carry HiBy's key there; images built with HIONEOS_AB_OTA=1 carry the HioneOS payload
 *    certificates instead. [deviceTrustsHioneKeys] compares that file with the certificates
 *    embedded in this APK, so the button only shows when update_engine will take the payload.
 *  - SELinux: system_app (this app) may find update_engine_service and binder-call update_engine
 *    (vendor policy), and may create files in /data/ota_package, which update_engine may read.
 */
object AbUpdateEnginePath : SystemUpdatePath {
    private const val TAG = "HioneUpdate"
    private const val OTACERTS = "/system/etc/security/otacerts.zip"
    private val CERT_ASSETS = listOf("ota-keys/ab-payload-primary.x509.pub.pem", "ota-keys/ab-payload-backup.x509.pub.pem")

    override val actionLabel = "Install on this device"

    private var trustCache: Boolean? = null

    fun isAbDevice(): Boolean =
        BuildInfo.sysProp("ro.build.ab_update") == "true" && !BuildInfo.sysProp("ro.boot.slot_suffix").isNullOrBlank()

    /** True when otacerts.zip in this image holds one of the HioneOS payload keys. */
    fun deviceTrustsHioneKeys(ctx: Context): Boolean {
        trustCache?.let { return it }
        val result = try {
            val cf = CertificateFactory.getInstance("X.509")
            val ours = CERT_ASSETS.mapNotNull { name ->
                runCatching { ctx.assets.open(name).use { cf.generateCertificate(it).publicKey.encoded } }.getOrNull()
            }
            val device = mutableListOf<ByteArray>()
            ZipFile(File(OTACERTS)).use { z ->
                for (e in z.entries()) {
                    if (!e.name.endsWith("x509.pem")) continue
                    runCatching { z.getInputStream(e).use { device += cf.generateCertificate(it).publicKey.encoded } }
                }
            }
            ours.isNotEmpty() && device.any { d -> ours.any { it.contentEquals(d) } }
        } catch (e: Exception) {
            Log.w(TAG, "could not read $OTACERTS", e)
            false
        }
        trustCache = result
        return result
    }

    override fun isAvailable(ctx: Context) = isAbDevice() && deviceTrustsHioneKeys(ctx)

    /**
     * The payload to use for [su] on a device running [deviceBuild]: a delta made from exactly
     * this build if there is one (smaller), otherwise the full one. Null when this path cannot
     * install [su] at all. [skipDelta] forces the full payload, used after a delta failed.
     */
    fun choosePayload(
        ctx: Context,
        su: OtaManifest.SystemUpdate,
        deviceBuild: String?,
        skipDelta: Boolean = false,
    ): OtaManifest.Payload? {
        if (su.payloads.isEmpty() || !isAvailable(ctx)) return null
        if (!BuildInfo.isOlder(deviceBuild, su.build)) return null
        val delta = if (skipDelta || deviceBuild == null) null
        else su.payloads.firstOrNull { !it.isFull && it.sourceBuild == deviceBuild }
        return delta ?: su.payloads.firstOrNull { it.isFull }
    }
}
