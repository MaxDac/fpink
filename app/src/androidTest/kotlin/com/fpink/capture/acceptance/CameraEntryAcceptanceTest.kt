package com.fpink.capture.acceptance

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fpink.capture.data.AndroidFileStore
import com.fpink.capture.data.RecognitionCoordinator
import com.fpink.capture.ui.capture.CaptureScreen
import com.fpink.capture.ui.capture.CaptureViewModel
import com.fpink.core.ai.DefaultNoteProcessor
import com.fpink.core.storage.NoteRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraEntryAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var storage: AcceptanceStorage
    private var model by mutableStateOf<CaptureViewModel?>(null)
    private val models = ViewModelStore()
    private var modelNumber = 0
    private var visible by mutableStateOf(true)
    private var dark by mutableStateOf(false)
    private val launches = mutableListOf<Pair<Int, Intent>>()
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
        ) {
            launches += requestCode to contract.createIntent(compose.activity, input)
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
    }

    @Before fun setUp() {
        storage = AcceptanceStorage()
    }

    @After fun cleanUp() {
        compose.runOnUiThread { models.clear() }
        storage.close()
    }

    private fun createModel(savedState: SavedStateHandle = SavedStateHandle()): CaptureViewModel {
        lateinit var created: CaptureViewModel
        compose.runOnUiThread {
            val coordinator = RecognitionCoordinator(
                storage.images, storage.settings, NoteRepository(AndroidFileStore(storage.context)),
                DefaultNoteProcessor(), { error("Camera entry tests must never run recognition") },
            )
            created = CaptureViewModel(storage.images, coordinator, storage.settings, savedState)
            models.put("capture-${modelNumber++}", created)
            model = created
        }
        return created
    }

    private fun showCapture(startWithCamera: Boolean = true): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    if (visible) {
                        CaptureScreen(
                            onImageCaptured = { error("Tests must not confirm recognition") },
                            onBack = {}, onSettings = { visible = false },
                            startWithCamera = startWithCamera, viewModel = requireNotNull(model),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        return restoration
    }

    private fun assumeCameraPermissionNotGranted() {
        assumeTrue(compose.activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        assumeFalse("Never revoke an installed user's permission",
            ContextCompat.checkSelfPermission(compose.activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    @Test fun directEntryRequestsOnlyCameraOnceAcrossDenialRecompositionSettingsAndRestoration() {
        assumeCameraPermissionNotGranted()
        val saved = SavedStateHandle()
        val initial = createModel(saved)
        val restoration = showCapture()
        compose.runOnIdle {
            assertEquals(1, launches.size)
            val (request, intent) = launches.single()
            assertEquals(ActivityResultContracts.RequestMultiplePermissions.ACTION_REQUEST_PERMISSIONS, intent.action)
            assertEquals(listOf(Manifest.permission.CAMERA),
                intent.getStringArrayExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS)!!.toList())
            registry.dispatchResult(request, Activity.RESULT_OK, Intent().apply {
                putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, arrayOf(Manifest.permission.CAMERA))
                putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, intArrayOf(PackageManager.PERMISSION_DENIED))
            })
        }
        assertAlternatives()
        compose.onNodeWithText(
            "Camera access was denied. Choose image or Browse files still works; camera access can be enabled in Android Settings.",
        ).assertIsDisplayed()
        compose.runOnIdle { dark = true }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.runOnIdle { visible = true }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle {
            assertFalse(initial.uiState.value.cameraChosen)
            assertEquals(1, launches.size)
        }
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        createModel(restored)
        assertAlternatives()
        compose.runOnIdle { assertEquals(1, launches.size) }
    }

    @Test fun pendingPermissionSurvivesCompositionRestorationWithoutLaunchingAgain() {
        assumeCameraPermissionNotGranted()
        createModel()
        val restoration = showCapture()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(1, launches.size) }
    }

    @Test fun latePermissionResultsCannotInterruptBusyWorkOrReplaceAStagedPreview() {
        assumeCameraPermissionNotGranted()
        val current = createModel()
        showCapture()
        fun grantPendingPermission() {
            registry.dispatchResult(launches.last().first, Activity.RESULT_OK, Intent().apply {
                putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, arrayOf(Manifest.permission.CAMERA))
                putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, intArrayOf(PackageManager.PERMISSION_GRANTED))
            })
        }
        compose.runOnIdle {
            current.captureStarted()
            grantPendingPermission()
            assertTrue(current.uiState.value.busy)
            assertFalse(current.uiState.value.cameraChosen)
            current.chooseOtherSource()
        }
        compose.onNodeWithContentDescription("Take photo").performClick()
        compose.runOnIdle {
            assertEquals(2, launches.size)
            val file = storage.images.newCameraFile().apply {
                writeBytes(encodedBitmap(12, 8, Bitmap.CompressFormat.PNG) { _, _ -> Color.WHITE })
            }
            current.importCamera(file)
        }
        compose.waitUntil(10_000) { current.uiState.value.previewFile != null && !current.uiState.value.busy }
        val source = current.uiState.value.sourceId
        compose.runOnIdle {
            grantPendingPermission()
            assertEquals(source, current.uiState.value.sourceId)
            assertFalse(current.uiState.value.cameraChosen)
        }
        compose.onNodeWithContentDescription("Prepared image to recognize").assertIsDisplayed()
        compose.onNodeWithText("Choose another source").assertDoesNotExist()
    }

    @Test fun chooserEntryNeverRequestsPermissionAndConsumesAnyLaterEntryReplay() {
        val saved = SavedStateHandle()
        val current = createModel(saved)
        showCapture(startWithCamera = false)
        assertAlternatives()
        compose.runOnIdle {
            assertTrue(launches.isEmpty())
            assertFalse(current.consumeCameraEntry(true))
            assertFalse(current.uiState.value.cameraChosen)
        }
    }

    @Test fun restoredStagedImageWinsAndChooseAnotherReturnsToChooserWithoutCameraReplay() {
        val staged = runBlocking {
            val file = storage.images.newCameraFile().apply {
                writeBytes(encodedBitmap(12, 8, Bitmap.CompressFormat.PNG) { _, _ -> Color.WHITE })
            }
            storage.images.importCamera(file)
        }
        val saved = SavedStateHandle(mapOf("sourceId" to staged.sourceId))
        val current = createModel(saved)
        val restoration = showCapture()
        compose.onNodeWithContentDescription("Prepared image to recognize").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(launches.isEmpty())
            assertFalse(current.canChooseCamera())
            current.chooseCamera()
            assertFalse(current.uiState.value.cameraChosen)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("choose another", substring = true, ignoreCase = true).performClick()
        compose.waitUntil(10_000) { current.uiState.value.sourceId == null }
        assertAlternatives()
        compose.runOnIdle {
            assertFalse(staged.file.exists())
            assertTrue(launches.isEmpty())
            assertFalse(current.consumeCameraEntry(true))
        }
        createModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertAlternatives()
        compose.runOnIdle { assertTrue(launches.isEmpty()) }
    }

    @Test fun busyAndUnavailableRestoredSourcesSuppressAutomaticCameraEntry() {
        val current = createModel()
        compose.runOnIdle { current.captureStarted() }
        showCapture()
        compose.runOnIdle {
            assertTrue(launches.isEmpty())
            assertFalse(current.canChooseCamera())
            current.chooseCamera()
            assertFalse(current.uiState.value.cameraChosen)
            current.chooseOtherSource()
        }
        assertAlternatives()
        compose.runOnIdle { assertTrue(launches.isEmpty()) }
        createModel(SavedStateHandle(mapOf("sourceId" to "missing-source")))
        compose.onNodeWithText("The previous image is unavailable. Choose it again.").assertIsDisplayed()
        assertAlternatives()
        compose.runOnIdle { assertTrue(launches.isEmpty()) }
    }

    @Test fun unavailablePermissionDuringBusyCameraReconcilesBeforeExplicitRetry() {
        assumeCameraPermissionNotGranted()
        val current = createModel()
        compose.runOnUiThread {
            current.chooseCamera()
            current.captureStarted()
        }
        showCapture()
        compose.runOnIdle {
            assertTrue(current.uiState.value.busy)
            assertTrue(current.uiState.value.cameraChosen)
            assertTrue(launches.isEmpty())
            current.error("The camera could not save the photo. Try again or choose an image.")
        }
        compose.waitUntil(10_000) { !current.uiState.value.cameraChosen }
        compose.onNodeWithText("The camera could not save the photo. Try again or choose an image.").assertIsDisplayed()
        assertAlternatives()
        compose.runOnIdle { assertTrue(launches.isEmpty()) }
        compose.onNodeWithContentDescription("Take photo").performClick()
        compose.runOnIdle { assertEquals(1, launches.size) }
    }

    @Test fun missingCameraShowsErrorAndAlternativesWithoutAnyLauncher() {
        assumeFalse("Runs on camera-less devices", compose.activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        createModel()
        showCapture()
        compose.onNodeWithText("This device has no camera. Choose an image instead.").assertIsDisplayed()
        assertAlternatives()
        compose.runOnIdle { assertTrue(launches.isEmpty()) }
    }

    @Test fun grantedPermissionStreamsCameraWithoutAutoCaptureAndOtherSourceDoesNotReplay() {
        assumeTrue("Requires permission granted outside the test; never changes an installed user's permissions",
            ContextCompat.checkSelfPermission(compose.activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        assumeTrue(compose.activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        val current = createModel()
        val restoration = showCapture()
        compose.waitUntil(20_000) {
            current.uiState.value.error != null || previewIsStreaming()
        }
        // Requires frames from the device's camera (synthetic on an emulator), not a mocked callback.
        compose.runOnIdle { assertNull(current.uiState.value.error) }
        assertTrue(previewIsStreaming())
        compose.onNodeWithText("Choose another source").assertIsDisplayed()
        compose.onNodeWithContentDescription("Take photo").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle {
            assertTrue(launches.isEmpty())
            assertNull(current.uiState.value.sourceId)
            assertFalse(current.uiState.value.busy)
            assertTrue(java.io.File(storage.context.cacheDir, "camera-captures").listFiles().orEmpty().isEmpty())
        }
        compose.onNodeWithText("Choose another source").performClick()
        restoration.emulateSavedInstanceStateRestore()
        assertAlternatives()
        compose.runOnIdle {
            assertFalse(current.uiState.value.cameraChosen)
            assertTrue(launches.isEmpty())
        }
    }

    private fun assertAlternatives() {
        compose.onNodeWithText("Choose image").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Browse files").assertIsDisplayed().assertIsEnabled()
    }

    private fun previewIsStreaming(): Boolean {
        fun View.isStreaming(): Boolean = when (this) {
            is PreviewView -> previewStreamState.value == PreviewView.StreamState.STREAMING
            is ViewGroup -> children.any { it.isStreaming() }
            else -> false
        }
        var streaming = false
        compose.runOnUiThread { streaming = compose.activity.window.decorView.isStreaming() }
        return streaming
    }
}
