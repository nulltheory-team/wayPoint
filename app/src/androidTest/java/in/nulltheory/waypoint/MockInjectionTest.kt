package `in`.nulltheory.waypoint

import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import `in`.nulltheory.waypoint.sim.MockPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Milestone 0: prove that a fix injected through the LocationManager test provider comes back
 * out of the location framework on this hardware.
 *
 * This is the only step that can invalidate the whole design. If it fails on a given unit,
 * either the OEM reads NMEA straight into a vendor HAL and bypasses LocationManager, or the
 * appop is not granted. Run it on every new dashcam model before trusting the app there:
 *
 *     adb shell appops set in.nulltheory.waypoint android:mock_location allow
 *     adb shell pm grant in.nulltheory.waypoint android.permission.ACCESS_FINE_LOCATION
 *     ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MockInjectionTest {

    @Suppress("DEPRECATION")
    @Test
    fun injectedFixComesBackOutOfTheLocationFramework() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "Appop not granted. Run: ${MockPermission.adbCommand(ctx.packageName)}",
            MockPermission.isGranted(ctx)
        )

        val lm = ctx.getSystemService(LocationManager::class.java)
        val provider = LocationManager.GPS_PROVIDER

        runCatching { lm.removeTestProvider(provider) }
        lm.addTestProvider(
            provider, false, false, false, false, true, true, true,
            Criteria.POWER_LOW, Criteria.ACCURACY_FINE
        )
        lm.setTestProviderEnabled(provider, true)

        val latch = CountDownLatch(1)
        val received = arrayOfNulls<Location>(1)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                received[0] = location
                latch.countDown()
            }

            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) = Unit
            override fun onProviderEnabled(p: String) = Unit
            override fun onProviderDisabled(p: String) = Unit
        }

        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())

            val fix = Location(provider).apply {
                latitude = 12.9716
                longitude = 77.5946
                speed = 13.9f
                bearing = 143.0f
                accuracy = 3.0f
                altitude = 920.0
                time = System.currentTimeMillis()
                // The one field whose absence silently drops the fix.
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                bearingAccuracyDegrees = 1.0f
                speedAccuracyMetersPerSecond = 0.5f
                verticalAccuracyMeters = 3.0f
            }
            lm.setTestProviderLocation(provider, fix)

            assertTrue(
                "No location callback within 5s: this device may bypass LocationManager",
                latch.await(5, TimeUnit.SECONDS)
            )

            val out = received[0]
            assertNotNull(out)
            assertEquals(12.9716, out!!.latitude, 1e-6)
            assertEquals(77.5946, out.longitude, 1e-6)
            assertEquals(13.9f, out.speed, 0.01f)
            assertEquals(143.0f, out.bearing, 0.01f)
            assertTrue("elapsedRealtimeNanos was not carried through", out.elapsedRealtimeNanos > 0)

            // Consuming SDKs that filter mock fixes need a debug flag to accept these.
            val isMock = if (Build.VERSION.SDK_INT >= 31) out.isMock else out.isFromMockProvider
            assertTrue("Injected fix was not flagged as mock", isMock)
        } finally {
            runCatching { lm.removeUpdates(listener) }
            runCatching { lm.setTestProviderEnabled(provider, false) }
            runCatching { lm.removeTestProvider(provider) }
        }
    }
}
