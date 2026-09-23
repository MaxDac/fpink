package com.fpink.capture.acceptance

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.system.Os
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fpink.capture.R
import com.fpink.capture.data.AndroidFileStore
import com.fpink.capture.data.RecognitionCoordinator
import com.fpink.capture.data.StagedImage
import com.fpink.capture.ui.capture.CaptureReviewActions
import com.fpink.capture.ui.capture.CaptureScreen
import com.fpink.capture.ui.capture.CaptureViewModel
import com.fpink.core.ai.DefaultNoteProcessor
import com.fpink.core.ai.RecognitionProviderId
import com.fpink.core.storage.NoteRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CaptureReviewActionsAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var storage: AcceptanceStorage? = null
    private lateinit var viewModel: CaptureViewModel
    private val savedState = SavedStateHandle()
    private val viewModels = ViewModelStore()
    private val confirmed = mutableListOf<String>()
    private val launches = mutableListOf<Pair<Int, Intent>>()
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
        ) {
            launches += requestCode to contract.createIntent(compose.activity, input)
        }
    }

    private val chooseAnother get() = compose.activity.getString(R.string.choose_another)
    private val useImage get() = compose.activity.getString(R.string.use_image)

    @After fun cleanUp() {
        try {
            compose.runOnUiThread { viewModels.clear() }
        } finally {
            storage?.close()
        }
    }

    @Test fun portrait320NormalTextFits() = assertLabelsFit(320, 640, 1f)
    @Test fun portrait320DoubleTextStacks() = assertLabelsFit(320, 640, 2f, stacked = true)
    @Test fun portrait360NormalTextFitsInRow() = assertLabelsFit(360, 640, 1f, stacked = false)
    @Test fun portrait360DoubleTextStacks() = assertLabelsFit(360, 640, 2f, stacked = true)
    @Test fun compactLandscape480NormalTextFitsInRow() = assertLabelsFit(480, 320, 1f, stacked = false)
    @Test fun compactLandscape480DoubleTextFits() = assertLabelsFit(480, 320, 2f)
    @Test fun compactLandscape640NormalTextFitsInRow() = assertLabelsFit(640, 360, 1f, stacked = false)
    @Test fun compactLandscape640DoubleTextFitsInRow() = assertLabelsFit(640, 360, 2f, stacked = false)

    @Test fun actionCallbacksRespectBusyAndProviderAvailability() {
        val busy = mutableStateOf(false)
        val available = mutableStateOf(true)
        var secondaryClicks = 0
        var primaryClicks = 0
        compose.setContent {
            MaterialTheme {
                CaptureReviewActions(
                    busy.value, available.value,
                    onChooseAnother = { secondaryClicks++ },
                    onConfirm = { primaryClicks++ },
                )
            }
        }
        for ((isBusy, isAvailable) in listOf(false to true, false to false, true to true, true to false)) {
            compose.runOnIdle {
                busy.value = isBusy
                available.value = isAvailable
            }
            val secondary = compose.onNodeWithText(chooseAnother)
            val primary = compose.onNodeWithText(useImage)
            if (isBusy) secondary.assertIsNotEnabled() else secondary.assertIsEnabled()
            if (isBusy || !isAvailable) primary.assertIsNotEnabled() else primary.assertIsEnabled()
            secondary.performClick()
            primary.performClick()
        }
        compose.runOnIdle {
            assertEquals(2, secondaryClicks)
            assertEquals(1, primaryClicks)
        }
    }

    @Test fun chooseAnotherDiscardsPreviewAndReturnsToSourceChoicesWithoutLaunchingAnything() {
        val staged = showReview()
        compose.runOnIdle { viewModel.chooseCamera() }
        compose.onNodeWithText(chooseAnother).performClick()
        assertSourceChoices(staged)
    }

    @Test fun busyReviewCannotDiscardOrConfirmItsStagedImage() {
        val staged = showReview()
        val invalidImage = requireNotNull(storage).images.newCameraFile().apply {
            writeText("Not an image")
        }
        compose.runOnIdle {
            // A staged image rejects new camera starts; exercise a real pending import instead.
            viewModel.importCamera(invalidImage)
            assertTrue(viewModel.uiState.value.busy)
            viewModel.chooseAnother()
            viewModel.confirm()
            assertEquals(staged.sourceId, viewModel.uiState.value.sourceId)
            assertEquals(staged.sourceId, savedState.get<String>("sourceId"))
            assertTrue(staged.file.isFile)
            assertTrue(confirmed.isEmpty())
            assertTrue(launches.isEmpty())
        }
        compose.waitUntil(10_000) { !viewModel.uiState.value.busy }
        compose.runOnIdle {
            assertEquals(staged.sourceId, viewModel.uiState.value.sourceId)
            assertTrue(staged.file.isFile)
            assertTrue(confirmed.isEmpty())
        }
    }

    @Test fun useImageWaitsForAvailableProviderThenConfirmsTheSameSource() {
        val staged = showReview()
        val testStorage = requireNotNull(storage)
        runBlocking {
            testStorage.preferences.edit { it[stringPreferencesKey("recognition_provider")] = "AZURE" }
        }
        compose.waitUntil(10_000) { !viewModel.uiState.value.providerAvailable }
        compose.onNodeWithText(chooseAnother).assertIsEnabled()
        compose.onNodeWithText(useImage).assertIsNotEnabled().performClick()
        compose.runOnIdle {
            assertTrue(confirmed.isEmpty())
            assertFalse(File(staged.file.parentFile, "selection").exists())
        }
        runBlocking { testStorage.settings.save(RecognitionProviderId.PADDLE) }
        compose.waitUntil(10_000) { viewModel.uiState.value.providerAvailable }
        compose.onNodeWithText(useImage).assertIsEnabled().performClick()
        compose.waitUntil(10_000) { confirmed.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(listOf(staged.sourceId), confirmed)
            assertEquals(staged.sourceId, viewModel.uiState.value.confirmedSourceId)
            assertEquals(true, savedState.get<Boolean>("transferred"))
            assertTrue(staged.file.isFile)
            assertTrue(File(staged.file.parentFile, "selection").isFile)
            assertTrue(launches.isEmpty())
        }
    }

    @Test fun cleanupFailureIsVisibleAndKeepsPreviewAvailableForRetry() {
        val staged = showReview()
        val directory = requireNotNull(staged.file.parentFile)
        // Deny deletion only inside this test's private staged directory, never production storage.
        Os.chmod(directory.absolutePath, 320) // 0500
        try {
            compose.onNodeWithText(chooseAnother).performClick()
            compose.waitUntil(10_000) { viewModel.uiState.value.error != null }
            compose.onNodeWithText(
                "Could not remove the previous staged image. Check free storage and retry.",
            ).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(chooseAnother).assertIsEnabled()
            compose.runOnIdle {
                assertEquals(staged.sourceId, viewModel.uiState.value.sourceId)
                assertEquals(staged.file, viewModel.uiState.value.previewFile)
                assertEquals(staged.sourceId, savedState.get<String>("sourceId"))
                assertTrue(staged.file.isFile)
                assertTrue(launches.isEmpty())
            }
        } finally {
            Os.chmod(directory.absolutePath, 448) // 0700
        }
        compose.onNodeWithText(chooseAnother).performClick()
        assertSourceChoices(staged)
    }

    private fun assertLabelsFit(width: Int, height: Int, fontScale: Float, stacked: Boolean? = null) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize().testTag("reviewViewport").padding(16.dp)) {
                            CaptureReviewActions(false, true, {}, {})
                        }
                    }
                }
            }
        }
        val viewport = compose.onNodeWithTag("reviewViewport").fetchSemanticsNode().boundsInRoot
        val buttonBounds = listOf(chooseAnother, useImage).map { label ->
            val button = compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled()
                .fetchSemanticsNode().boundsInRoot
            val text = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single()
            assertEquals(label, layout.layoutInput.text.text)
            assertEquals("$label must occupy exactly one line", 1, layout.lineCount)
            assertEquals(label.length, layout.getLineEnd(0, visibleEnd = true))
            assertFalse("$label must not be ellipsized", layout.isLineEllipsized(0))
            assertFalse("$label must not overflow vertically", layout.didOverflowHeight)
            // String Text semantics rebuild a paragraph at the available width; check glyphs, not that spare width.
            val localBounds = Rect(0f, 0f, layout.size.width.toFloat(), layout.size.height.toFloat())
            label.indices.forEach { assertContains(localBounds, layout.getBoundingBox(it)) }
            assertTrue(layout.getLineLeft(0) >= 0f)
            assertTrue(layout.getLineRight(0) <= layout.size.width)
            assertTrue(layout.getLineTop(0) >= 0f)
            assertTrue(layout.getLineBottom(0) <= layout.size.height)
            val textBounds = text.fetchSemanticsNode().boundsInRoot
            assertEquals("$label must not be clipped horizontally", layout.size.width.toFloat(), textBounds.width, 1f)
            assertEquals("$label must not be clipped vertically", layout.size.height.toFloat(), textBounds.height, 1f)
            assertContains(button, textBounds)
            assertContains(viewport, button)
            val density = layout.layoutInput.density.density
            assertEquals(fontScale, layout.layoutInput.density.fontScale, 0f)
            assertEquals(width.toFloat(), viewport.width / density, 1f)
            assertEquals(height.toFloat(), viewport.height / density, 1f)
            assertTrue("$label needs a 48dp touch height", button.height / density >= 48f - 0.5f)
            assertTrue("$label needs a 48dp touch width", button.width / density >= 48f - 0.5f)
            button
        }
        val (secondary, primary) = buttonBounds
        if (stacked == true) {
            assertTrue("Whole buttons should stack", secondary.bottom < primary.top)
            assertEquals(secondary.left, primary.left, 1f)
        } else if (stacked == false) {
            assertTrue("Buttons should share a row when they fit", secondary.right < primary.left)
            assertEquals(secondary.top, primary.top, 1f)
        }
    }

    private fun assertContains(outer: Rect, inner: Rect) {
        assertTrue("$inner must fit fully inside $outer",
            inner.left >= outer.left && inner.top >= outer.top &&
                inner.right <= outer.right && inner.bottom <= outer.bottom)
    }

    private fun showReview(): StagedImage {
        val testStorage = AcceptanceStorage().also { storage = it }
        compose.runOnUiThread {
            val coordinator = RecognitionCoordinator(
                testStorage.images, testStorage.settings, NoteRepository(AndroidFileStore(testStorage.context)),
                DefaultNoteProcessor(), { error("Review tests must never run recognition or contact Azure") },
            )
            viewModel = CaptureViewModel(testStorage.images, coordinator, testStorage.settings, savedState)
            viewModels.put("capture", viewModel)
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) {
                MaterialTheme {
                    CaptureScreen(onImageCaptured = { confirmed += it }, onBack = {}, onSettings = {}, viewModel = viewModel)
                }
            }
        }
        compose.waitUntil(10_000) { viewModel.uiState.value.providerAvailable }
        // Staging now happens on the notes list's Gallery/File menu (see SourceMenuAcceptanceTest);
        // exercise CaptureScreen's review step directly via the camera import path it still owns.
        compose.runOnIdle {
            val file = testStorage.images.newCameraFile().apply {
                writeBytes(encodedBitmap(8, 8, Bitmap.CompressFormat.PNG) { _, _ -> Color.WHITE })
            }
            viewModel.importCamera(file)
        }
        compose.waitUntil(15_000) { viewModel.uiState.value.previewFile != null && !viewModel.uiState.value.busy }
        compose.onNodeWithText(chooseAnother).assertIsDisplayed()
        return viewModel.uiState.value.let { StagedImage(requireNotNull(it.sourceId), requireNotNull(it.previewFile)) }
    }

    private fun assertSourceChoices(staged: StagedImage) {
        compose.waitUntil(10_000) { viewModel.uiState.value.previewFile == null }
        compose.onNodeWithContentDescription("Take photo").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Choose image").assertDoesNotExist()
        compose.onNodeWithText("Browse files").assertDoesNotExist()
        compose.onNodeWithText(useImage).assertDoesNotExist()
        compose.onNodeWithText(chooseAnother).assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(requireNotNull(staged.file.parentFile).exists())
            assertNull(viewModel.uiState.value.sourceId)
            assertFalse(viewModel.uiState.value.cameraChosen)
            assertNull(viewModel.uiState.value.error)
            assertNull(savedState.get<String>("sourceId"))
            assertTrue(confirmed.isEmpty())
            assertTrue(launches.isEmpty())
        }
    }
}
