package `in`.nulltheory.waypoint.sim

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * ACCESS_MOCK_LOCATION in the manifest is necessary but not sufficient: the appop has to be
 * allowed too, either through developer options or over adb.
 */
object MockPermission {

    @Suppress("DEPRECATION")
    fun isGranted(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        return runCatching {
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), ctx.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), ctx.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    /** The adb route, for headless or stripped dashcam builds with no Settings UI. */
    fun adbCommand(packageName: String): String =
        "adb shell appops set $packageName android:mock_location allow"

    /**
     * Deep-links developer options so the user can pick the mock location app. Returns false
     * on builds where that screen does not exist, which is common on stripped AOSP images.
     */
    fun openDeveloperOptions(ctx: Context): Boolean = runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}
