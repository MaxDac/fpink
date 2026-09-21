package com.fpink.capture.acceptance

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fpink.capture.data.AndroidFileStore
import com.fpink.capture.data.ImageImportStore
import com.fpink.capture.ui.notes.NotesListScreen
import com.fpink.capture.ui.notes.NotesListViewModel
import com.fpink.capture.ui.notes.SourceImportViewModel
import com.fpink.core.storage.NoteRepository
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the contextual Gallery/File menu opened directly on [NotesListScreen] by the bottom-left
 * Plus action and the centered empty-state pen action: menu opening from both entry points,
 * scrim/outside-tap/Back dismissal, the Gallery/File launch intent contracts, cancellation and
 * import errors, and navigating straight to the prepared-image review flow via `onSourceReady`.
 * The bottom-right Camera shortcut's direct behavior is covered by [NotesListActionsAcceptanceTest].
 */
@RunWith(AndroidJUnit4::class)
class SourceMenuAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var storage: AcceptanceStorage
    private lateinit var picker: FixturePicker
    private lateinit var notes: NotesListViewModel
    private lateinit var source: SourceImportViewModel
    private val models = ViewModelStore()
    private val readySourceIds = mutableListOf<String>()
    private val launches = mutableListOf<Pair<Int, Intent>>()
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
        ) {
            launches += requestCode to contract.createIntent(compose.activity, input)
        }
    }

    @Before fun showNotes() {
        storage = AcceptanceStorage()
        picker = FixturePicker(compose.activityRule.scenario)
        compose.runOnUiThread {
            notes = NotesListViewModel(NoteRepository(AndroidFileStore(storage.context)))
            source = SourceImportViewModel(storage.images)
            models.put("notes", notes)
            models.put("source", source)
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) {
                MaterialTheme {
                    NotesListScreen(
                        onSourceReady = { readySourceIds += it },
                        onCameraClick = {},
                        onNoteClick = {},
                        onSettingsClick = {},
                        viewModel = notes,
                        sourceViewModel = source,
                    )
                }
            }
        }
        compose.waitUntil(10_000) { !notes.uiState.value.isLoading }
    }

    @After fun cleanUp() {
        try {
            picker.close()
        } finally {
            try {
                compose.runOnUiThread { models.clear() }
            } finally {
                storage.close()
            }
        }
    }

    @Test fun bothEntryPointsOpenTheSameMenuWithOnlyGalleryAndFile() {
        openMenu(0)
        assertMenuOpen()
        compose.onNodeWithTag("sourceMenuScrim").performClick()
        assertMenuClosed()
        // The notes list starts empty, so the centered empty-state pen icon (index 1) shares the
        // same "Add notes" content description; it must open the identical menu.
        openMenu(1)
        assertMenuOpen()
    }

    @Test fun scrimTapDismissesTheMenuWithoutLaunchingAnything() {
        openMenu(0)
        assertMenuOpen()
        compose.onNodeWithTag("sourceMenuScrim").performClick()
        assertMenuClosed()
        compose.runOnIdle { assertTrue(launches.isEmpty()) }
    }

    @Test fun systemBackDismissesTheMenuWithoutLaunchingAnything() {
        openMenu(0)
        assertMenuOpen()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        assertMenuClosed()
        compose.runOnIdle { assertTrue(launches.isEmpty()) }
    }

    @Test fun galleryAndFileLaunchSingleOpenableImageReadGrantsWithoutPermissionRequests() {
        for ((label, expectedAction) in listOf("Gallery" to Intent.ACTION_GET_CONTENT, "File" to Intent.ACTION_OPEN_DOCUMENT)) {
            openMenu(0)
            compose.onNodeWithText(label).performClick()
            assertMenuClosed()
            compose.runOnIdle {
                assertEquals(1, launches.size)
                val (request, chooser) = launches.single()
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                @Suppress("DEPRECATION")
                val content = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
                assertEquals(expectedAction, content.action)
                assertEquals("image/*", content.type)
                assertTrue(content.hasCategory(Intent.CATEGORY_OPENABLE))
                assertTrue(content.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertEquals(0, content.flags and (Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
                assertFalse(content.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
                registry.dispatchResult(request, Activity.RESULT_CANCELED, null)
                launches.clear()
            }
            compose.onNodeWithText("No image selected. Choose Gallery or File to try again.").assertIsDisplayed()
            assertTrue(readySourceIds.isEmpty())
        }
    }

    @Test fun successfulGalleryImportNavigatesDirectlyToReviewWithoutTheOldChooser() {
        val uri = picker.provide(encodedBitmap(8, 8, Bitmap.CompressFormat.PNG) { _, _ -> Color.WHITE })
        openMenu(0)
        compose.onNodeWithText("Gallery").performClick()
        assertMenuClosed()
        compose.runOnIdle {
            registry.dispatchResult(launches.single().first, Activity.RESULT_OK, Intent().setData(uri))
        }
        compose.waitUntil(10_000) { readySourceIds.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(1, readySourceIds.size)
            assertNull(source.uiState.value.readySourceId)
            assertNull(source.uiState.value.error)
        }
    }

    @Test fun malformedProviderImageIsVisiblyRejectedAndPrivatePartialImportRemoved() {
        val uri = picker.provide("This is not an image".toByteArray())
        returnImage(uri)
        assertRejected("This image is corrupt or its format is not supported on this device.")
    }

    @Test fun oversizedProviderStreamIsVisiblyRejectedAndPrivatePartialImportRemoved() {
        val uri = picker.provide(byteArrayOf(1), length = ImageImportStore.MAX_IMPORT_BYTES + 1)
        returnImage(uri)
        assertRejected("Choose an image no larger than 24 MB.")
    }

    @Test fun revokedProviderGrantIsVisiblyRejectedWithoutLosingTheMenu() {
        val uri = picker.provide(byteArrayOf(1))
        picker.remove(uri)
        returnImage(uri)
        assertRejected("The image provider denied access. Download the image locally and choose it again.")
    }

    private fun returnImage(uri: Uri) {
        openMenu(0)
        compose.onNodeWithText("Gallery").performClick()
        compose.runOnIdle {
            registry.dispatchResult(launches.single().first, Activity.RESULT_OK, Intent().setData(uri))
        }
    }

    private fun assertRejected(message: String) {
        compose.waitUntil(15_000) { source.uiState.value.error != null }
        compose.onNodeWithText(message).assertIsDisplayed()
        assertTrue(readySourceIds.isEmpty())
        // The menu must remain reachable after an import error; it is not left open or broken.
        openMenu(0)
        assertMenuOpen()
        compose.onNodeWithTag("sourceMenuScrim").performClick()
        compose.runOnIdle {
            assertTrue(File(storage.context.noBackupFilesDir, "image-imports").listFiles().orEmpty().isEmpty())
        }
    }

    private fun openMenu(index: Int) =
        compose.onAllNodesWithContentDescription("Add notes")[index].performClick()

    private fun assertMenuOpen() {
        compose.onNodeWithText("Gallery").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("File").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("sourceMenuScrim").assertIsDisplayed()
    }

    private fun assertMenuClosed() {
        compose.onNodeWithText("Gallery").assertDoesNotExist()
        compose.onNodeWithText("File").assertDoesNotExist()
    }
}
