package com.fpink.capture.ui.capture

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.fpink.capture.ui.savedContainerViewModel
import com.fpink.capture.R
import com.fpink.capture.ui.components.ActionIconButton

private const val CAMERA_PERMISSION_DENIED =
    "Camera access was denied. Choose image or Browse files still works; camera access can be enabled in Android Settings."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onImageCaptured: (String) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    startWithCamera: Boolean = false,
    viewModel: CaptureViewModel = savedContainerViewModel { container, savedState ->
        CaptureViewModel(container.imageImports, container.recognitionCoordinator, container.settingsStore, savedState)
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun cameraPermissionGranted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember {
        mutableStateOf(cameraPermissionGranted())
    }
    var requestingPermission by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requestingPermission = false
        hasPermission = granted
        if (viewModel.canChooseCamera()) {
            if (granted) viewModel.chooseCamera()
            else {
                viewModel.chooseOtherSource()
                viewModel.error(CAMERA_PERMISSION_DENIED)
            }
        }
    }
    fun requestCamera() {
        if (requestingPermission || !viewModel.canChooseCamera()) return
        hasPermission = cameraPermissionGranted()
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            viewModel.error("This device has no camera. Choose an image instead.")
        } else if (hasPermission) {
            viewModel.chooseCamera()
        } else {
            requestingPermission = true
            try {
                permission.launch(Manifest.permission.CAMERA)
            } catch (_: ActivityNotFoundException) {
                requestingPermission = false
                viewModel.error("Camera access could not be requested. You can still choose an image.")
            } catch (_: SecurityException) {
                requestingPermission = false
                viewModel.error(CAMERA_PERMISSION_DENIED)
            }
        }
    }
    LaunchedEffect(viewModel) {
        if (viewModel.consumeCameraEntry(startWithCamera)) requestCamera()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = cameraPermissionGranted()
    }
    LaunchedEffect(viewModel, hasPermission, state.cameraChosen, state.busy, state.previewFile) {
        val current = viewModel.uiState.value
        if (!hasPermission && current.cameraChosen && !current.busy && current.previewFile == null) {
            viewModel.chooseOtherSource()
            if (current.error == null) viewModel.error(CAMERA_PERMISSION_DENIED)
        }
    }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) viewModel.importContent(uri)
        else viewModel.pickerCancelled()
    }
    fun chooseImage(files: Boolean) {
        val intent = Intent(if (files) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            chooser.launch(Intent.createChooser(intent, if (files) "Choose an image file" else "Choose image using"))
        } catch (_: ActivityNotFoundException) {
            viewModel.error("No compatible image provider is installed. Try Browse files or install a gallery with an image chooser.")
        } catch (_: SecurityException) {
            viewModel.error("Android could not open this image provider. Try Browse files.")
        }
    }

    BackHandler { viewModel.leave(onBack) }
    LaunchedEffect(state.confirmedSourceId) {
        state.confirmedSourceId?.let(onImageCaptured)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add notes") },
                navigationIcon = {
                    ActionIconButton(R.drawable.ic_back, R.string.back, onClick = { viewModel.leave(onBack) })
                },
                actions = {
                    ActionIconButton(R.drawable.ic_settings, R.string.settings, enabled = !state.busy, onClick = onSettings)
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.providerLabel, style = MaterialTheme.typography.bodyMedium)
            Text(
                "One image, up to 24 MB. Images are normalized to PNG and downsampled to at most 4 megapixels / 3072 pixels per side. Photograph small handwriting closely; keep the page sharp and well lit.",
                style = MaterialTheme.typography.bodySmall,
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when {
                state.previewFile != null -> {
                    AsyncImage(
                        model = state.previewFile,
                        contentDescription = "Prepared image to recognize",
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    Text("Each recognized paragraph becomes a separate local note. Ink colour is measured on this device.")
                    CaptureReviewActions(
                        busy = state.busy,
                        providerAvailable = state.providerAvailable,
                        onChooseAnother = viewModel::chooseAnother,
                        onConfirm = viewModel::confirm,
                    )
                }
                state.cameraChosen && hasPermission -> {
                    CameraPreview(viewModel, state.busy, Modifier.weight(1f).fillMaxWidth())
                    OutlinedButton(onClick = viewModel::chooseOtherSource, enabled = !state.busy) { Text("Choose another source") }
                }
                else -> {
                    ActionIconButton(
                        R.drawable.ic_camera,
                        R.string.take_photo,
                        filled = true,
                        enabled = !state.busy && !requestingPermission,
                        onClick = ::requestCamera,
                    )
                    Button(onClick = { chooseImage(false) }, enabled = !state.busy) { Text("Choose image") }
                    OutlinedButton(onClick = { chooseImage(true) }, enabled = !state.busy) { Text("Browse files") }
                    Text("Compatible third-party gallery and file apps are supported. Gallery access does not require camera or storage permission.")
                }
            }
            if (state.busy) CircularProgressIndicator()
        }
    }
}

@Composable
internal fun CaptureReviewActions(
    busy: Boolean,
    providerAvailable: Boolean,
    onChooseAnother: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            OutlinedButton(
                onClick = onChooseAnother,
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.choose_another), softWrap = false) }
            Button(
                onClick = onConfirm,
                enabled = !busy && providerAvailable,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.use_image), softWrap = false) }
        },
    ) { measurables, constraints ->
        val spacing = 12.dp.roundToPx()
        // Intrinsic button widths include the current font scale, theme typography and padding.
        val preferredButtonWidth = measurables.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) }
        val width = constraints.constrainWidth(preferredButtonWidth * 2 + spacing)
        val horizontalButtonWidth = ((width - spacing) / 2).coerceAtLeast(0)
        val stacked = preferredButtonWidth > horizontalButtonWidth
        val buttonWidth = if (stacked) width else horizontalButtonWidth
        val buttons = measurables.map { it.measure(Constraints.fixedWidth(buttonWidth)) }
        val height = if (stacked) buttons.sumOf { it.height } + spacing else buttons.maxOf { it.height }
        layout(width, height) {
            var offset = 0
            buttons.forEach { button ->
                button.placeRelative(if (stacked) 0 else offset, if (stacked) offset else 0)
                offset += (if (stacked) button.height else button.width) + spacing
            }
        }
    }
}

@Composable
private fun CameraPreview(viewModel: CaptureViewModel, busy: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember(context) { PreviewView(context) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    var ready by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var disposed = false
        future.addListener({
            if (!disposed) {
                try {
                    val current = future.get()
                    provider = current
                    val selector = when {
                        current.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                        current.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> error("No usable camera")
                    }
                    val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    preview = cameraPreview
                    current.bindToLifecycle(lifecycleOwner, selector, cameraPreview, capture)
                    ready = true
                } catch (_: Exception) {
                    viewModel.chooseOtherSource()
                    viewModel.error("The camera is unavailable. You can still choose an image.")
                }
            }
        }, executor)
        onDispose {
            disposed = true
            ready = false
            preview?.let { provider?.unbind(it, capture) }
        }
    }
    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        ActionIconButton(
            R.drawable.ic_camera,
            R.string.take_photo,
            filled = true,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            enabled = ready && !busy,
            onClick = {
                try {
                    val file = viewModel.newCameraFile()
                    viewModel.captureStarted()
                    capture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                    capture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(file).build(),
                        executor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = viewModel.importCamera(file)
                            override fun onError(exception: ImageCaptureException) {
                                viewModel.deleteCameraFile(file)
                                viewModel.error("The camera could not save the photo. Try again or choose an image.")
                            }
                        },
                    )
                } catch (_: Exception) {
                    viewModel.error("Could not start a capture. Check camera access and available storage.")
                }
            },
        )
    }
}
