package fi.nikosavola.clockifywear.mobile

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PRIVACY.md and the manifest's own comment both claim this app never talks to Clockify's API
 * itself and can't leak the pasted key into a backup. Nothing else in this codebase enforces either
 * claim - these pin them against the actual merged manifest.
 */
@RunWith(RobolectricTestRunner::class)
class CompanionManifestTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun `the app requests no internet permission`() {
    val packageInfo =
      context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)

    val requestedPermissions = packageInfo.requestedPermissions?.toList().orEmpty()

    assertFalse(requestedPermissions.contains(android.Manifest.permission.INTERNET))
  }

  @Test
  fun `the app is excluded from Android's automatic backup`() {
    val allowsBackup = context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0

    assertFalse(allowsBackup)
  }
}
