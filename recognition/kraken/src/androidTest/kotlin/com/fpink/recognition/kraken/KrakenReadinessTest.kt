package com.fpink.recognition.kraken

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fpink.core.ai.RecognitionError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** This test APK requests no network permission; Kraken must never download model bytes. */
@RunWith(AndroidJUnit4::class)
class KrakenReadinessTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun missingReviewedExportFailsClosed() {
        val failure = KrakenOcrProvider.readiness(context).exceptionOrNull()
        assertTrue(failure is RecognitionError.ModelUnavailable || failure is RecognitionError.UnsupportedDevice)
    }

    @Test fun testApkDoesNotDeclareInternetPermission() {
        val permissions = context.packageManager.getPackageInfo(context.packageName, 4096).requestedPermissions.orEmpty()
        assertFalse(permissions.contains(android.Manifest.permission.INTERNET))
    }
}
