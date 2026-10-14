package com.hione.launcher.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Base64
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HioneOS Production Cryptography Engine.
 * Provides AES-256-GCM authenticated encryption and runtime XOR string shielding.
 */
object HioneCrypto {
    // NOT A SECRET. This literal ships inside the APK, so anyone holding the APK holds the key;
    // it is obfuscation, not protection. It must never be used to protect anything that matters
    // (credentials, tokens, user data, licence state). Anything genuinely sensitive belongs in the
    // Android Keystore or must not be on the device at all. Kept only so the XOR/AES helpers below
    // have a fixed key for cosmetic string shielding.
    private const val AES_KEY_STRING = "39HioneOS_SecureProdKey_2026_DAP!" // 32 bytes AES-256 key

    /**
     * Returns null when the input is not a ciphertext this key can open (bad Base64, truncated
     * IV, failed GCM tag). It used to return the Base64 input itself on failure, so a caller
     * could not tell a decrypted plaintext from an undecrypted blob and would happily use the
     * ciphertext as if it were data.
     */
    fun decryptString(cipherTextBase64: String): String? {
        return try {
            val combined = Base64.decode(cipherTextBase64, Base64.DEFAULT)
            if (combined.size < 12) return null
            val iv = combined.copyOfRange(0, 12)
            val cipherBytes = combined.copyOfRange(12, combined.size)
            val key = SecretKeySpec(AES_KEY_STRING.toByteArray(Charsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    fun xorUnshield(encoded: ByteArray, key: Byte = 0x39): String {
        val result = ByteArray(encoded.size)
        for (i in encoded.indices) {
            result[i] = (encoded[i].toInt() xor key.toInt()).toByte()
        }
        return String(result, Charsets.UTF_8)
    }
}

/**
 * HioneOS Production Anti-Tamper & Security Sentinel.
 * Runtime integrity guardian protecting against APK repackaging, Frida hooking,
 * dynamic debugger attachment, and binary tampering.
 */
object HioneSecuritySentinel {
    private const val TAG = "HioneOS_Security"

    // Official HioneOS Production Certificate SHA-256 Fingerprints
    private val OFFICIAL_SIGNATURE_HASHES = setOf(
        "DFE220D2C2E64F8F50D1D4BA1EB5D6B699750EAA20B0D14ADCE24660CBFF7C61", // Production JKS
        "27196E386B875E76ADF700E7EA84E4C6EEE33DFFA9C724126E5E11E44D6042EE"  // AOSP Platform Key
    )

    /**
     * Tri-state signature check. It used to return `true` both when the hash loop found NO match and
     * from the catch block, so `signatureValid` was hardcoded-true: any UI rendering a
     * "SIGNATURE VALID ✓" chip off it would have been asserting a check that never actually passed.
     */
    enum class SignatureState { VALID, UNRECOGNISED_KEY, CHECK_FAILED }

    fun verifyApkSignatureState(context: Context): SignatureState {
        try {
            val pm = context.packageManager
            val pkg = context.packageName
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                pi.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                pi.signatures
            } ?: return SignatureState.CHECK_FAILED

            val md = MessageDigest.getInstance("SHA-256")
            for (sig in signatures) {
                val digest = md.digest(sig.toByteArray())
                val hex = digest.joinToString("") { "%02X".format(it) }
                if (OFFICIAL_SIGNATURE_HASHES.contains(hex)) {
                    return SignatureState.VALID
                }
            }
            // Signed, but by a key we do not recognise (platform/debug key during development).
            // That is NOT the same as "valid" and must never be reported as such.
            return SignatureState.UNRECOGNISED_KEY
        } catch (t: Throwable) {
            return SignatureState.CHECK_FAILED
        }
    }

    /** True ONLY for a recognised official signing key. */
    fun verifyApkSignature(context: Context): Boolean =
        verifyApkSignatureState(context) == SignatureState.VALID

    /**
     * Outcome of a tamper probe. [CHECK_FAILED] is deliberately NOT the same value as [CLEAR]:
     * an exception, an SELinux denial on /proc, or a node we could not parse means we do not
     * know, and a security check that cannot run must never be reported as "nothing found".
     */
    enum class ProbeState { DETECTED, CLEAR, CHECK_FAILED }

    /**
     * Tri-state debugger probe. Both the exception path and "could not find TracerPid at all"
     * used to return `false`, i.e. a failed check indistinguishable from a clean device.
     */
    fun isDebuggerAttached(): ProbeState {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return ProbeState.DETECTED
        return try {
            val file = File("/proc/self/status")
            if (!file.exists()) return ProbeState.CHECK_FAILED
            var result = ProbeState.CHECK_FAILED
            BufferedReader(FileReader(file)).use { reader ->
                var line: String? = null
                while (reader.readLine().also { line = it } != null) {
                    val current = line
                    if (current != null && current.startsWith("TracerPid:")) {
                        val pid = current.substringAfter("TracerPid:").trim().toIntOrNull()
                        // An unparseable TracerPid is a failed read, not a tracer-free process.
                        result = if (pid == null) ProbeState.CHECK_FAILED
                            else if (pid > 0) ProbeState.DETECTED
                            else ProbeState.CLEAR
                        break
                    }
                }
            }
            result
        } catch (_: Throwable) {
            ProbeState.CHECK_FAILED
        }
    }

    /**
     * Tri-state hook-framework probe. The catch block and an unreadable /proc/self/maps used to
     * fall through to `false` — a check that never ran reported as "no hooks present".
     */
    fun isHookFrameworkDetected(): ProbeState {
        try {
            // 1. Stack trace inspection for Frida / Xposed frames
            val trace = Thread.currentThread().stackTrace
            for (elem in trace) {
                val cls = elem.className.lowercase()
                if (cls.contains("frida") || cls.contains("xposed") || cls.contains("cydia") || cls.contains("substrate")) {
                    return ProbeState.DETECTED
                }
            }

            // 2. Memory mapping scan for injected instrumentation libraries
            val mapsFile = File("/proc/self/maps")
            // The stack-trace pass alone cannot clear the process: without the maps scan the
            // strongest honest answer is "unknown".
            if (!mapsFile.exists()) return ProbeState.CHECK_FAILED
            BufferedReader(FileReader(mapsFile)).use { reader ->
                var line: String? = null
                while (reader.readLine().also { line = it } != null) {
                    val l = line?.lowercase() ?: ""
                    if (l.contains("frida-agent") || l.contains("xposed.bridge") || l.contains("libgadget.so")) {
                        return ProbeState.DETECTED
                    }
                }
            }
            return ProbeState.CLEAR
        } catch (_: Throwable) {
            return ProbeState.CHECK_FAILED
        }
    }

    fun getSecurityReport(context: Context): SecurityReport {
        val sigState = verifyApkSignatureState(context)
        val debugAttached = isDebuggerAttached()
        val hookDetected = isHookFrameworkDetected()
        // "Secure" now requires every probe to have actually RUN and come back clean. A probe that
        // could not run leaves isSecure false rather than silently counting as a pass.
        val isSecure = sigState == SignatureState.VALID &&
            debugAttached == ProbeState.CLEAR &&
            hookDetected == ProbeState.CLEAR

        return SecurityReport(
            isSecure = isSecure,
            signatureState = sigState,
            debuggerAttached = debugAttached,
            hookDetected = hookDetected,
            // "—" when the build reports no ABI; it used to assert "arm64-v8a".
            architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "—",
            securityPatch = Build.VERSION.SECURITY_PATCH ?: "—"
        )
    }
}

data class SecurityReport(
    val isSecure: Boolean,
    val signatureState: HioneSecuritySentinel.SignatureState,
    val debuggerAttached: HioneSecuritySentinel.ProbeState,
    val hookDetected: HioneSecuritySentinel.ProbeState,
    val architecture: String,
    val securityPatch: String
) {
    /** Only a recognised official key counts as valid — never "the check did not run". */
    val signatureValid: Boolean
        get() = signatureState == HioneSecuritySentinel.SignatureState.VALID

    /**
     * True when at least one probe could not run. A UI must show this rather than rendering an
     * unknown as a clean result; the individual [debuggerAttached] / [hookDetected] states say
     * which one.
     */
    val hasUnknownChecks: Boolean
        get() = signatureState == HioneSecuritySentinel.SignatureState.CHECK_FAILED ||
            debuggerAttached == HioneSecuritySentinel.ProbeState.CHECK_FAILED ||
            hookDetected == HioneSecuritySentinel.ProbeState.CHECK_FAILED
}
