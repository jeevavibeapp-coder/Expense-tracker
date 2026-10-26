package android.content.pm

class PackageManager {
    fun getPackageInfo(packageName: String, flags: Int): PackageInfo = PackageInfo()
    fun checkPermission(permName: String, pkgName: String): Int = PERMISSION_DENIED

    companion object {
        const val PERMISSION_GRANTED = 0
        const val PERMISSION_DENIED = -1
        const val GET_PERMISSIONS = 0x00001000
    }
}

class PackageInfo {
    /** Null when the manifest declares none — the no-SMS flavour relies on it. */
    val requestedPermissions: Array<String>? = null
    val versionName: String? = null
}

class ApplicationInfo
