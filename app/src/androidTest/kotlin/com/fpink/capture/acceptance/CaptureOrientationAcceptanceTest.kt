package com.fpink.capture.acceptance

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fpink.capture.MainActivity
import com.fpink.capture.data.AndroidFileStore
import com.fpink.capture.data.RecognitionCoordinator
import com.fpink.capture.ui.capture.CameraDisplayRotation
import com.fpink.capture.ui.capture.CaptureImageLayout
import com.fpink.capture.ui.capture.CaptureScreen
import com.fpink.capture.ui.capture.CaptureViewModel
import com.fpink.core.ai.DefaultNoteProcessor
import com.fpink.core.storage.NoteRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureOrientationAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var storage: AcceptanceStorage
    private lateinit var viewModel: CaptureViewModel
    private val restoredModels = ViewModelStore()
    private var restoredCount = 0

    @Before fun setUp() {
        storage = AcceptanceStorage()
        obtainActivityModel()
    }

    @After fun cleanUp() {
        // Activity-owned models must be cleared before removing their isolated storage.
        compose.activityRule.scenario.close()
        compose.runOnUiThread { restoredModels.clear() }
        compose.waitUntil(10_000) {
            File(storage.context.noBackupFilesDir, "image-imports").listFiles().orEmpty().isEmpty()
        }
        storage.close()
    }

    @Test fun productionActivityUsesSystemOrientationAndOrdinaryRecreation() {
        @Suppress("DEPRECATION")
        val info = compose.activity.packageManager.getActivityInfo(ComponentName(compose.activity, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, info.screenOrientation)
        assertEquals(0, info.configChanges and (ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
    }

    @Test fun shortWideLayoutKeepsPreviewAndControlsVisibleWithScrollableLongInformation() {
        compose.setContent {
            MaterialTheme {
                CaptureImageLayout(
                    modifier = Modifier.requiredSize(360.dp, 180.dp),
                    image = { Box(it.testTag("preview")) },
                    actions = { Button(onClick = {}) { Text("Source") } },
                    information = {
                        Text("Long help and errors. ".repeat(80))
                        Text("End of information")
                    },
                )
            }
        }
        compose.onNodeWithTag("preview").assertIsDisplayed().assertWidthIsAtLeast(148.dp).assertHeightIsAtLeast(180.dp)
        compose.onNodeWithText("Source").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("End of information").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("preview").assertIsDisplayed().assertHeightIsAtLeast(180.dp)
        compose.onNodeWithText("Source").performScrollTo().assertIsDisplayed()
    }

    @Test fun compactPortraitBoundsInformationAndActionsInsteadOfCollapsingPreview() {
        compose.setContent {
            MaterialTheme {
                CaptureImageLayout(
                    modifier = Modifier.requiredSize(260.dp, 400.dp),
                    image = { Box(it.testTag("preview")) },
                    actions = {
                        Text("Long action explanation. ".repeat(50))
                        Button(onClick = {}) { Text("Use image") }
                    },
                    information = {
                        Text("Long provider and error text. ".repeat(80))
                        Text("End of information")
                    },
                )
            }
        }
        compose.onNodeWithTag("preview").assertIsDisplayed().assertHeightIsAtLeast(136.dp)
        compose.onNodeWithText("End of information").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Use image").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("preview").assertIsDisplayed().assertHeightIsAtLeast(136.dp)
    }

    @Test fun selectedModeRestoresWithoutRestoringAnInFlightCaptureOrRepeatingARequest() {
        compose.runOnIdle {
            val handle = SavedStateHandle()
            val original = restoredModel(handle)
            original.chooseCamera()
            assertTrue(original.captureStarted())
            assertFalse(original.captureStarted())
            val restoredHandle = snapshot(handle)
            val restored = restoredModel(restoredHandle)
            assertTrue(restored.uiState.value.cameraChosen)
            assertFalse(restored.uiState.value.busy)
            assertEquals(null, restored.uiState.value.sourceId)
            restored.chooseOtherSource()
            assertFalse(restoredModel(snapshot(restoredHandle)).uiState.value.cameraChosen)
        }
        assertTrue(File(storage.context.cacheDir, "camera-captures").listFiles().orEmpty().isEmpty())
    }

    @Test fun restoredStagedImageTakesPrecedenceOverCameraAndRetainsSourceAcrossOrientations() {
        val staged = runBlocking { storage.images.importCamera(cameraFile()) }
        val bytes = staged.file.readBytes()
        compose.runOnIdle {
            viewModel = restoredModel(SavedStateHandle(mapOf("cameraChosen" to true, "sourceId" to staged.sourceId)))
            assertTrue(viewModel.uiState.value.cameraChosen)
            assertEquals(staged.sourceId, viewModel.uiState.value.sourceId)
            assertFalse(viewModel.captureStarted())
        }
        for (orientation in listOf(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        )) {
            rotateHost(orientation)
            mountScreen()
            compose.onNodeWithContentDescription("Prepared image to recognize").assertIsDisplayed().assertHeightIsAtLeast(100.dp)
            compose.onNodeWithText("Use image").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Retake / choose another").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Take photo").assertDoesNotExist()
            assertEquals(staged.sourceId, viewModel.uiState.value.sourceId)
            assertArrayEquals(bytes, staged.file.readBytes())
        }
        assertEquals(1, File(storage.context.noBackupFilesDir, "image-imports").listFiles().orEmpty().size)
    }

    @Test fun choosingAnotherImageClearsSavedCameraModeAndStagedSource() {
        val staged = runBlocking { storage.images.importCamera(cameraFile()) }
        val handle = SavedStateHandle(mapOf("cameraChosen" to true, "sourceId" to staged.sourceId))
        lateinit var restored: CaptureViewModel
        compose.runOnIdle {
            restored = restoredModel(handle)
            restored.chooseAnother()
        }
        compose.waitUntil(10_000) { restored.uiState.value.previewFile == null }
        compose.runOnIdle {
            val next = restoredModel(snapshot(handle))
            assertFalse(next.uiState.value.cameraChosen)
            assertEquals(null, next.uiState.value.sourceId)
            assertFalse(next.uiState.value.busy)
        }
        assertFalse(staged.file.exists())
    }

    @Test fun recreationAndBackgroundRetainSinglePendingCaptureAndItsImport() {
        val retained = viewModel
        compose.runOnIdle {
            viewModel.chooseCamera()
            assertTrue(viewModel.captureStarted())
        }
        compose.activityRule.scenario.recreate()
        obtainActivityModel()
        compose.runOnIdle {
            assertSame(retained, viewModel)
            assertTrue(viewModel.uiState.value.cameraChosen)
            assertTrue(viewModel.uiState.value.busy)
            assertFalse(viewModel.captureStarted())
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        val file = cameraFile()
        compose.runOnIdle { viewModel.importCamera(file) }
        compose.waitUntil(15_000) { viewModel.uiState.value.previewFile != null && !viewModel.uiState.value.busy }
        val source = viewModel.uiState.value.sourceId
        assertFalse(file.exists())
        compose.activityRule.scenario.recreate()
        obtainActivityModel()
        mountScreen()
        compose.onNodeWithContentDescription("Prepared image to recognize").assertIsDisplayed()
        assertEquals(source, viewModel.uiState.value.sourceId)
        assertEquals(1, File(storage.context.noBackupFilesDir, "image-imports").listFiles().orEmpty().size)
    }

    @Test fun landscapeReviewAtDoubleFontScaleKeepsImageAndActionsReachable() {
        val file = cameraFile()
        compose.runOnIdle { viewModel.importCamera(file) }
        compose.waitUntil(15_000) { viewModel.uiState.value.previewFile != null && !viewModel.uiState.value.busy }
        rotateHost(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    MaterialTheme {
                        Box(Modifier.requiredSize(640.dp, 320.dp)) {
                            CaptureScreen(
                                onImageCaptured = { error("Tests must not confirm recognition") },
                                onBack = {}, onSettings = {}, viewModel = viewModel,
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Prepared image to recognize").assertIsDisplayed().assertHeightIsAtLeast(100.dp)
        compose.onNodeWithText("Use image").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Retake / choose another").performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    @Test fun captureErrorAfterRecreationReleasesBusyAndAllowsRetry() {
        compose.runOnIdle { assertTrue(viewModel.captureStarted()) }
        compose.activityRule.scenario.recreate()
        obtainActivityModel()
        compose.runOnIdle {
            assertFalse(viewModel.captureStarted())
            viewModel.error("Capture interrupted")
            assertFalse(viewModel.uiState.value.busy)
            assertEquals("Capture interrupted", viewModel.uiState.value.error)
            assertTrue(viewModel.captureStarted())
        }
    }

    @Test fun displayUpdatesRequireMatchingDisplayStartedLifecycleAndAttachedView() {
        compose.runOnUiThread {
            val owner = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this)
            }
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            val container = FrameLayout(compose.activity)
            val view = View(compose.activity)
            compose.activity.setContentView(container)
            val rotations = mutableListOf<Int>()
            val listener = CameraDisplayRotation(view, owner.lifecycle, rotations::add)
            try {
                container.addView(view)
                assertTrue(rotations.isEmpty())
                owner.lifecycle.currentState = Lifecycle.State.STARTED
                assertEquals(listOf(view.display.rotation), rotations)
                listener.onDisplayChanged(view.display.displayId + 1)
                assertEquals(1, rotations.size)
                listener.onDisplayChanged(view.display.displayId)
                assertEquals(2, rotations.size)
                owner.lifecycle.currentState = Lifecycle.State.CREATED
                listener.onDisplayChanged(view.display.displayId)
                assertEquals(2, rotations.size)
                owner.lifecycle.currentState = Lifecycle.State.STARTED
                assertEquals(3, rotations.size)
                val displayId = view.display.displayId
                container.removeView(view)
                listener.onDisplayChanged(displayId)
                assertEquals(3, rotations.size)
                container.addView(view)
                assertEquals(4, rotations.size)
                listener.close()
                listener.onDisplayChanged(displayId)
                assertEquals(4, rotations.size)
                owner.lifecycle.currentState = Lifecycle.State.CREATED
                owner.lifecycle.currentState = Lifecycle.State.STARTED
                assertEquals(4, rotations.size)
            } finally {
                listener.close()
            }
        }
    }

    @Test fun reverseLandscapeUpdatesCaptureTargetWithoutRecreatingOrRotatingTheView() {
        rotateHost(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        val activity = compose.activity
        val view = activity.window.decorView
        lateinit var capture: ImageCapture
        lateinit var listener: CameraDisplayRotation
        compose.runOnUiThread {
            capture = ImageCapture.Builder().setTargetRotation(view.display.rotation).build()
            listener = CameraDisplayRotation(view, activity.lifecycle) { capture.targetRotation = it }
        }
        try {
            val before = capture.targetRotation
            rotateHost(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
            compose.waitUntil(10_000) { capture.targetRotation != before }
            compose.runOnIdle {
                assertSame(activity, compose.activity)
                assertEquals(view.display.rotation, capture.targetRotation)
                assertEquals(0f, view.rotation)
            }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle { assertEquals(view.display.rotation, capture.targetRotation) }
        } finally {
            compose.runOnUiThread { listener.close() }
        }
    }

    private fun newModel(handle: SavedStateHandle) = CaptureViewModel(
        storage.images,
        RecognitionCoordinator(
            storage.images, storage.settings, NoteRepository(AndroidFileStore(storage.context)),
            DefaultNoteProcessor(), { error("Orientation tests must never run recognition") },
        ),
        storage.settings,
        handle,
    )

    private fun restoredModel(handle: SavedStateHandle): CaptureViewModel =
        newModel(handle).also { restoredModels.put("restored-${restoredCount++}", it) }

    private fun snapshot(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })

    private fun obtainActivityModel() {
        compose.activityRule.scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity, viewModelFactory {
                initializer { newModel(createSavedStateHandle()) }
            })[CaptureViewModel::class.java]
        }
        compose.waitUntil(10_000) { viewModel.uiState.value.providerAvailable }
    }

    private fun mountScreen() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    CaptureScreen(
                        onImageCaptured = { error("Orientation tests must not confirm recognition") },
                        onBack = {}, onSettings = {}, viewModel = viewModel,
                    )
                }
            }
        }
        compose.waitUntil(10_000) { viewModel.uiState.value.providerAvailable }
    }

    private fun rotateHost(orientation: Int) {
        val before = compose.activity.window.decorView.display.rotation
        val oppositeLandscape = orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        compose.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            Configuration.ORIENTATION_PORTRAIT
        } else {
            Configuration.ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(10_000) {
            var rotated = false
            compose.activityRule.scenario.onActivity {
                rotated = it.resources.configuration.orientation == expected &&
                    (!oppositeLandscape || it.window.decorView.display.rotation != before)
            }
            rotated
        }
    }

    private fun cameraFile(): File = storage.images.newCameraFile().apply {
        writeBytes(encodedBitmap(12, 8, Bitmap.CompressFormat.PNG) { _, _ -> Color.BLUE })
    }
}
