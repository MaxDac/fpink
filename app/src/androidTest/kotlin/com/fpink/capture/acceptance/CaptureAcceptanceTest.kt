package com.fpink.capture.acceptance

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fpink.capture.data.AndroidFileStore
import com.fpink.capture.data.ImageImportStore
import com.fpink.capture.data.RecognitionCoordinator
import com.fpink.capture.ui.capture.CaptureScreen
import com.fpink.capture.ui.capture.CaptureViewModel
import com.fpink.core.ai.DefaultNoteProcessor
import com.fpink.core.storage.NoteRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var storage: AcceptanceStorage
    private lateinit var viewModel: CaptureViewModel
    private lateinit var picker: FixturePicker
    private val viewModels = ViewModelStore()
    private val launches = mutableListOf<Pair<Int, Intent>>()
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
        ) {
            launches += requestCode to contract.createIntent(compose.activity, input)
        }
    }

    @Before fun showCapture() {
        storage = AcceptanceStorage()
        picker = FixturePicker(compose.activityRule.scenario)
        compose.runOnUiThread {
            val coordinator = RecognitionCoordinator(
                storage.images, storage.settings, NoteRepository(AndroidFileStore(storage.context)),
                DefaultNoteProcessor(), { error("Acceptance capture tests must never run recognition or contact Azure") },
            )
            viewModel = CaptureViewModel(storage.images, coordinator, storage.settings, SavedStateHandle())
            viewModels.put("capture", viewModel)
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) {
                MaterialTheme {
                    CaptureScreen(
                        onImageCaptured = { error("Tests must not confirm recognition") },
                        onBack = {}, onSettings = {}, viewModel = viewModel,
                    )
                }
            }
        }
        compose.waitUntil(10_000) { viewModel.uiState.value.providerAvailable }
    }

    @After fun cleanUp() {
        try {
            picker.close()
        } finally {
            try {
                compose.runOnUiThread { viewModels.clear() }
            } finally {
                storage.close()
            }
        }
    }

    @Test fun manifestRequiresNeitherCameraHardwareNorBroadMediaPermissions() {
        val context = compose.activity
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_CONFIGURATIONS,
        )
        val permissions = info.requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.CAMERA in permissions)
        assertFalse(permissions.any { it in setOf(
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.MANAGE_EXTERNAL_STORAGE, Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) })
        val cameraFeatures = info.reqFeatures.orEmpty().filter { it.name?.startsWith("android.hardware.camera") == true }
        assertTrue(cameraFeatures.any { it.name == PackageManager.FEATURE_CAMERA_ANY })
        assertTrue(cameraFeatures.all { it.flags and FeatureInfo.FLAG_REQUIRED == 0 })
    }

    @Test fun galleryAndFilesLaunchSingleOpenableImageReadGrantsWithoutPermissionRequests() {
        for ((label, expectedAction) in listOf("Choose image" to Intent.ACTION_GET_CONTENT, "Browse files" to Intent.ACTION_OPEN_DOCUMENT)) {
            compose.onNodeWithText(label).performClick()
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
            compose.onNodeWithText("No image selected. You can choose an image or take a photo.").assertIsDisplayed()
            compose.onNodeWithText("Choose image").assertIsEnabled()
            compose.onNodeWithText("Browse files").assertIsEnabled()
            compose.onNodeWithText("Use image").assertDoesNotExist()
        }
    }

    @Test fun deniedCameraCallbackKeepsGalleryAndFilesAvailable() {
        assumeTrue("Requires an ungranted camera permission; test never revokes an installed user's permission",
            ContextCompat.checkSelfPermission(compose.activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
        assumeTrue("Camera-less devices are covered by the optional manifest contract",
            compose.activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        compose.onNodeWithText("Take photo").performClick()
        compose.runOnIdle {
            assertEquals(1, launches.size)
            val (request, intent) = launches.single()
            assertEquals(ActivityResultContracts.RequestMultiplePermissions.ACTION_REQUEST_PERMISSIONS, intent.action)
            assertEquals(listOf(Manifest.permission.CAMERA),
                intent.getStringArrayExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS)!!.toList())
            // Exercise the app's denied-result callback, not the system permission dialog.
            registry.dispatchResult(request, Activity.RESULT_OK, Intent().apply {
                putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, arrayOf(Manifest.permission.CAMERA))
                putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, intArrayOf(PackageManager.PERMISSION_DENIED))
            })
        }
        compose.onNodeWithText("Camera access was denied. Choose image or Browse files still works; camera access can be enabled in Android Settings.").assertIsDisplayed()
        compose.onNodeWithText("Choose image").assertIsEnabled()
        compose.onNodeWithText("Browse files").assertIsEnabled()
        assertFalse(viewModel.uiState.value.cameraChosen)
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

    @Test fun revokedProviderGrantIsVisiblyRejectedWithoutLosingSourceChoices() {
        val uri = picker.provide(byteArrayOf(1))
        picker.remove(uri)
        returnImage(uri)
        assertRejected("The image provider denied access. Download the image locally and choose it again.")
    }

    private fun returnImage(uri: Uri) {
        compose.onNodeWithText("Choose image").performClick()
        compose.runOnIdle {
            registry.dispatchResult(launches.single().first, Activity.RESULT_OK, Intent().setData(uri))
        }
    }

    private fun assertRejected(message: String) {
        compose.waitUntil(15_000) { viewModel.uiState.value.error != null && !viewModel.uiState.value.busy }
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("Choose image").assertIsEnabled()
        compose.onNodeWithText("Browse files").assertIsEnabled()
        compose.onNodeWithText("Use image").assertDoesNotExist()
        assertNull(viewModel.uiState.value.sourceId)
        assertNull(viewModel.uiState.value.previewFile)
        assertTrue(java.io.File(storage.context.noBackupFilesDir, "image-imports").listFiles().orEmpty().isEmpty())
    }
}
