package com.fpink.capture.acceptance

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

internal class FixturePicker(private val scenario: ActivityScenario<ComponentActivity>) : AutoCloseable {
    private val fixturePackage = InstrumentationRegistry.getInstrumentation().context.packageName
    private val supplied = mutableListOf<Uri>()

    fun provide(bytes: ByteArray, name: String = "image.png", length: Long = -1): Uri {
        val id = UUID.randomUUID().toString()
        val expectedUri = Uri.parse("content://$fixturePackage.images/$id/$name")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_DENIED, target.checkUriPermission(
            expectedUri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION,
        ))
        val result = launch(intent(id, name).putExtra("bytes", bytes).putExtra("length", length))
        return requireNotNull(result.data?.data).also {
            supplied += it
            assertEquals(expectedUri, it)
            val providerUid = requireNotNull(result.data).getIntExtra("fixtureUid", -1)
            assertTrue(providerUid > 0)
            assertNotEquals("The provider must run under a separate UID", Process.myUid(), providerUid)
        }
    }

    fun remove(uri: Uri) {
        launch(intent(uri.pathSegments[0], requireNotNull(uri.lastPathSegment)).putExtra("remove", true))
        supplied.remove(uri)
    }

    override fun close() = supplied.toList().forEach(::remove)

    private fun intent(id: String, name: String) = Intent().apply {
        component = ComponentName(fixturePackage, FixturePickerActivity::class.java.name)
        putExtra("id", id)
        putExtra("name", name)
    }

    private fun launch(intent: Intent): ActivityResult {
        val completed = CountDownLatch(1)
        var result: ActivityResult? = null
        var launcher: ActivityResultLauncher<Intent>? = null
        val key = UUID.randomUUID().toString()
        scenario.onActivity { activity ->
            launcher = activity.activityResultRegistry.register(key, ActivityResultContracts.StartActivityForResult()) {
                result = it
                completed.countDown()
            }
            launcher!!.launch(intent)
        }
        try {
            assertTrue("Fixture picker did not return within 15 seconds", completed.await(15, TimeUnit.SECONDS))
            assertEquals(Activity.RESULT_OK, result?.resultCode)
            return requireNotNull(result)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { launcher?.unregister() }
        }
    }
}
