package com.hione.update.ota

/** The HioneOS build this device is running, from ro.hioneos.version (set by build_hioneos_super.sh). */
object BuildInfo {
    fun hioneosVersion(): String? = sysProp("ro.hioneos.version")?.takeIf { it.isNotBlank() }

    fun sysProp(key: String): String? = try {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java).invoke(null, key) as? String
    } catch (_: Throwable) {
        null
    }

    /**
     * Compares HioneOS build strings like "0.1.16" or "0.2.0". Numeric dot segments, missing ones
     * count as 0, and anything after a '-' or '+' is ignored ("0.2.0-rc1" == "0.2.0").
     */
    fun compare(a: String, b: String): Int {
        fun parts(s: String) = s.trim().removePrefix("v").split('-', '+')[0].split('.')
            .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** True when [device] is older than [required]. An unknown device build counts as older. */
    fun isOlder(device: String?, required: String?): Boolean {
        if (required.isNullOrBlank()) return false
        if (device.isNullOrBlank()) return true
        return compare(device, required) < 0
    }
}
