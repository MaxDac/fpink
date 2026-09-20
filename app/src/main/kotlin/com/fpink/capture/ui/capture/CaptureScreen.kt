package com.fpink.capture.ui.capture

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.fpink.capture.ui.savedContainerViewModel
import com.fpink.capture.R
import com.fpink.capture.ui.components.ActionIconButton
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onImageCaptured: (String) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    viewModel: CaptureViewModel = savedContainerViewModel { container, savedState ->
        CaptureViewModel(container.imageImports, container.recognitionCoordinator, container.settingsStore, savedState)
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) viewModel.chooseCamera()
        else {
            viewModel.chooseOtherSource()
            viewModel.error("Camera access was denied. Choose image or Browse files still works; camera access can be enabled in Android Settings.")
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
        val contentModifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        if (state.previewFile != null || state.cameraChosen && hasPermission) {
            CaptureImageLayout(
                modifier = contentModifier,
                information = { CaptureInformation(state) },
                image = { modifier ->
                    if (state.previewFile != null) {
                        AsyncImage(
                            model = state.previewFile,
                            contentDescription = "Prepared image to recognize",
                            modifier = modifier,
                        )
                    } else {
                        CameraPreview(viewModel, state.busy, modifier)
                    }
                },
                actions = { compact ->
                    if (state.previewFile != null) {
                        if (!compact) {
                            Text("Each recognized paragraph becomes a separate local note. Ink colour is measured on this device.")
                        }
                        CaptureReviewActions(
                            busy = state.busy,
                            providerAvailable = state.providerAvailable,
                            onChooseAnother = viewModel::chooseAnother,
                            onConfirm = viewModel::confirm,
                        )
                    } else {
                        OutlinedButton(onClick = viewModel::chooseOtherSource, enabled = !state.busy) { Text("Choose another source") }
                    }
                    if (state.busy) CircularProgressIndicator()
                    if (compact && state.previewFile != null) {
                        Text("Each recognized paragraph becomes a separate local note. Ink colour is measured on this device.")
                    }
                },
            )
        } else {
            Column(
                modifier = contentModifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CaptureInformation(state)
                ActionIconButton(
                    R.drawable.ic_camera,
                    R.string.take_photo,
                    filled = true,
                    enabled = !state.busy,
                    onClick = {
                        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                            viewModel.error("This device has no camera. Choose an image instead.")
                        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            hasPermission = true
                            viewModel.chooseCamera()
                        } else {
                            permission.launch(Manifest.permission.CAMERA)
                        }
                    },
                )
                Button(onClick = { chooseImage(false) }, enabled = !state.busy) { Text("Choose image") }
                OutlinedButton(onClick = { chooseImage(true) }, enabled = !state.busy) { Text("Browse files") }
                Text("Compatible third-party gallery and file apps are supported. Gallery access does not require camera or storage permission.")
                if (state.busy) CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun CaptureInformation(state: CaptureUiState) {
    Text(state.providerLabel, style = MaterialTheme.typography.bodyMedium)
    Text(
        "One image, up to 24 MB. Images are normalized to PNG and downsampled to at most 4 megapixels / 3072 pixels per side. Photograph small handwriting closely; keep the page sharp and well lit.",
        style = MaterialTheme.typography.bodySmall,
    )
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun CaptureImageLayout(
    modifier: Modifier = Modifier,
    information: @Composable () -> Unit,
    image: @Composable (Modifier) -> Unit,
    actions: @Composable (compact: Boolean) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val panelHeight = maxHeight * 0.3f
        val controlsWidth = maxOf(maxWidth * 0.4f, 200.dp * LocalDensity.current.fontScale)
        if (maxWidth > maxHeight && maxWidth - controlsWidth >= 132.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                image(Modifier.weight(1f).fillMaxHeight())
                Column(
                    Modifier.width(controlsWidth).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    actions(true)
                    information()
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = panelHeight).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { information() }
                image(Modifier.weight(1f).fillMaxWidth())
                Column(
                    Modifier.fillMaxWidth().heightIn(max = panelHeight).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { actions(false) }
            }
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
    var capture by remember(previewView, lifecycleOwner) { mutableStateOf<ImageCapture?>(null) }

    DisposableEffect(previewView, lifecycleOwner, viewModel) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var disposed = false
        fun cameraUnavailable() {
            viewModel.chooseOtherSource()
            viewModel.error("The camera is unavailable. You can still choose an image.")
        }
        val rotation = CameraDisplayRotation(previewView, lifecycleOwner.lifecycle) { targetRotation ->
            val current = provider
            val boundCapture = capture
            if (boundCapture != null) {
                boundCapture.targetRotation = targetRotation
            } else if (current != null && !disposed) {
                try {
                    val selector = when {
                        current.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                        current.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> error("No usable camera")
                    }
                    val cameraPreview = Preview.Builder().setTargetRotation(targetRotation).build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetRotation(targetRotation)
                        .build()
                    preview = cameraPreview
                    current.bindToLifecycle(lifecycleOwner, selector, cameraPreview, imageCapture)
                    capture = imageCapture
                } catch (_: Exception) {
                    cameraUnavailable()
                }
            }
        }
        future.addListener({
            if (!disposed) {
                try {
                    provider = future.get()
                    rotation.updateRotation()
                } catch (_: Exception) {
                    cameraUnavailable()
                }
            }
        }, executor)
        onDispose {
            disposed = true
            rotation.close()
            val boundCapture = capture
            capture = null
            preview?.let { cameraPreview ->
                provider?.unbind(*listOfNotNull(cameraPreview, boundCapture).toTypedArray())
            }
        }
    }
    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        ActionIconButton(
            R.drawable.ic_camera,
            R.string.take_photo,
            filled = true,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            enabled = capture != null && !busy,
            onClick = click@{
                val imageCapture = capture ?: return@click
                if (!viewModel.captureStarted()) return@click
                var pendingFile: File? = null
                try {
                    val file = viewModel.newCameraFile().also { pendingFile = it }
                    imageCapture.targetRotation = requireNotNull(previewView.display).rotation
                    imageCapture.takePicture(
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
                    pendingFile?.let(viewModel::deleteCameraFile)
                    viewModel.error("Could not start a capture. Check camera access and available storage.")
                }
            },
        )
    }
}
