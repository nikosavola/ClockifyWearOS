package fi.nikosavola.clockifywear.companion

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.clockifywear.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `WATCH_CAPABILITY` (in `companion-protocol`) and the `android_wear_capabilities` array (in
 * `wear/src/main/res/values/wear.xml`) are kept in sync by a code comment only - Play Services
 * reads the resource by name at runtime, with nothing at compile time checking it matches the
 * constant the mobile app queries for. A rename on either side would make
 * `CapabilityClient.getCapability(WATCH_CAPABILITY, ...)` find zero nodes and the whole companion
 * feature would silently stop working - see docs/RELEASING.md's note on why an end-to-end pairing
 * test can't run in CI, which is exactly why this drift needs a check that can.
 */
@RunWith(RobolectricTestRunner::class)
class WatchCapabilityRegistrationTest {
  @Test
  fun `the declared capability matches the constant the mobile app queries for`() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    val declaredCapabilities = context.resources.getStringArray(R.array.android_wear_capabilities)

    assertTrue(declaredCapabilities.contains(WATCH_CAPABILITY))
  }

  @Test
  fun `the companion listener service is declared and exported`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val component = ComponentName(context, ApiKeyMessageListenerService::class.java)

    val serviceInfo = context.packageManager.getServiceInfo(component, 0)

    assertTrue(serviceInfo.exported)
  }
}
