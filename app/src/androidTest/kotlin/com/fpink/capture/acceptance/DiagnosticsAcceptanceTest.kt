package com.fpink.capture.acceptance

import android.content.Intent
import android.content.ComponentName
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fpink.capture.BuildConfig
import com.fpink.capture.diagnostics.DiagnosticCategory
import com.fpink.capture.diagnostics.DiagnosticOperation
import com.fpink.capture.diagnostics.DiagnosticStore
import com.fpink.capture.ui.settings.DiagnosticsSettingsSection
import com.fpink.capture.ui.settings.DiagnosticsViewModel
import com.fpink.capture.ui.settings.diagnosticShareIntent
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val storage = AcceptanceStorage()
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val exportDirectory = File(target.cacheDir, "diagnostics-share/acceptance-${storage.id}/diagnostics-share")
    private val store = DiagnosticStore(File(storage.context.noBackupFilesDir, "diagnostics"), exportDirectory)
    private val models = ViewModelStore()

    @After fun tearDown() {
        compose.runOnUiThread { models.clear() }
        runBlocking { store.clear() }
        store.close()
        if (exportDirectory.exists()) check(exportDirectory.deleteRecursively())
        storage.close()
    }

    @Test fun reportRequiresReviewAndClearKeepsLoggingAvailable() {
        lateinit var model: DiagnosticsViewModel
        val full = BuildConfig.FLAVOR == "full"
        compose.runOnUiThread {
            model = DiagnosticsViewModel(store, full = full)
            models.put("diagnostics", model)
        }
        compose.setContent { MaterialTheme { Column { DiagnosticsSettingsSection(model) } } }
        compose.onNodeWithText("Report a problem").performClick()
        compose.waitUntil(10_000) { model.uiState.value.draft != null }
        compose.onNodeWithText("Open GitHub draft").assertExists()
        val repository = if (full) "fpink-private" else "fpink"
        assertTrue(model.uiState.value.draft!!.url.startsWith("https://github.com/MaxDac/$repository/issues/new?"))
        compose.onNodeWithText(
            if (full) "Your browser must be signed in as a collaborator with access."
            else "This draft targets public MaxDac/fpink.",
            substring = true,
        ).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(null, model.uiState.value.draft)
        compose.onNodeWithText("Clear logs").performClick()
        compose.onNode(hasText("Clear logs") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { !model.uiState.value.busy && model.uiState.value.notice != null }
        runBlocking {
            assertTrue(store.snapshot().isEmpty())
            store.report(DiagnosticOperation.STORAGE_LOAD, DiagnosticCategory.STORAGE)
            assertTrue(store.snapshot().isNotEmpty())
        }
    }

    @Test fun exportGrantsOnlyReadAndProviderRejectsUnrelatedData() = runBlocking {
        store.report(DiagnosticOperation.STORAGE_LOAD, DiagnosticCategory.STORAGE)
        val file = store.export()
        val intent = diagnosticShareIntent(target, file)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        val uri = requireNotNull(intent.clipData).getItemAt(0).uri
        assertEquals("content", uri.scheme)
        assertEquals("${target.packageName}.diagnostics.fileprovider", uri.authority)
        assertNotNull(target.contentResolver.openInputStream(uri)?.use { it.readBytes() })
        assertFalse(file.readText().contains(storage.id))
        val unrelated = File(target.filesDir, "not-a-diagnostic.txt")
        val rejected = runCatching {
            FileProvider.getUriForFile(target, "${target.packageName}.diagnostics.fileprovider", unrelated)
        }.exceptionOrNull()
        assertTrue(rejected is IllegalArgumentException)
        val sibling = File(exportDirectory, "unshared.txt").apply { writeText("fixture") }
        try {
            intent.component = ComponentName(
                InstrumentationRegistry.getInstrumentation().context.packageName,
                DiagnosticsReceiverActivity::class.java.name,
            )
            intent.putExtra(
                "sibling",
                FileProvider.getUriForFile(target, "${target.packageName}.diagnostics.fileprovider", sibling).toString(),
            )
            val result = receive(intent)
            assertNotEquals(Process.myUid(), result.getIntExtra("uid", -1))
            assertTrue(result.getBooleanExtra("readable", false))
            assertTrue(result.getBooleanExtra("writeDenied", false))
            assertTrue(result.getBooleanExtra("siblingDenied", false))
        } finally {
            check(sibling.delete())
        }
        store.clear()
        assertFalse(file.exists())
    }

    private fun receive(intent: Intent): Intent {
        val completed = CountDownLatch(1)
        var result: ActivityResult? = null
        var launcher: ActivityResultLauncher<Intent>? = null
        compose.activityRule.scenario.onActivity { activity ->
            launcher = activity.activityResultRegistry.register(
                "diagnostics-receiver",
                ActivityResultContracts.StartActivityForResult(),
            ) { received -> result = received; completed.countDown() }
            launcher!!.launch(intent)
        }
        try {
            assertTrue("Receiver timed out", completed.await(15, TimeUnit.SECONDS))
            assertEquals(android.app.Activity.RESULT_OK, result?.resultCode)
            return requireNotNull(result?.data)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { launcher?.unregister() }
        }
    }
}
