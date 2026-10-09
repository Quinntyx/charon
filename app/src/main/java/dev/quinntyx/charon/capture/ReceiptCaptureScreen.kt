package dev.quinntyx.charon.capture

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Minimal receipt acquisition surface. OCR and transaction editing intentionally live elsewhere. */
@Composable
fun ReceiptCaptureScreen(
    onReceiptReady: (ReceiptImage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val store = remember(context.applicationContext) {
        ReceiptImageStore(File(context.applicationContext.filesDir, "receipt-images"))
    }
    var cameraPermissionGranted by remember {
        mutableStateOf(context.hasCameraPermission())
    }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionGranted = granted
        message = if (granted) null else "Camera access was not granted. You can still import an image."
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            busy = true
            message = null
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        resolver.openInputStream(uri)?.use { input ->
                            store.importImage(input, resolver.getType(uri))
                        } ?: error("The selected image could not be opened")
                    }
                }.onSuccess { image ->
                    busy = false
                    message = "Imported ${image.file.name}"
                    onReceiptReady(image)
                }.onFailure { failure ->
                    busy = false
                    message = failure.userMessage("Could not import the selected image")
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraPermissionGranted = context.hasCameraPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val activity = context.findActivity()
    val showSettings = permissionRequested &&
        !cameraPermissionGranted &&
        activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add receipt", style = MaterialTheme.typography.headlineSmall)
            Text("Take a photo or copy an existing image into Charon's private storage.")

            if (cameraPermissionGranted) {
                CameraPreview(
                    onImageCaptureReady = { imageCapture = it },
                    onCameraError = { message = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Camera permission is needed only when taking a new receipt photo.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        permissionRequested = true
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }) {
                        Text(if (permissionRequested) "Try camera permission again" else "Allow camera")
                    }
                    if (showSettings) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { context.openAppSettings() }) {
                            Text("Open app settings")
                        }
                    }
                }
            }

            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = cameraPermissionGranted && imageCapture != null && !busy,
                    onClick = {
                        val capture = imageCapture ?: return@Button
                        busy = true
                        message = null
                        val pending = runCatching { store.beginCameraCapture() }
                            .getOrElse { failure ->
                                busy = false
                                message = failure.userMessage("Could not prepare receipt storage")
                                return@Button
                            }
                        capture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(pending.stagingFile).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                store.commitCameraCapture(pending)
                                            }
                                        }.onSuccess { image ->
                                            busy = false
                                            message = "Saved ${image.file.name}"
                                            onReceiptReady(image)
                                        }.onFailure { failure ->
                                            busy = false
                                            message = failure.userMessage("Could not save the receipt photo")
                                        }
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    scope.launch(Dispatchers.IO) {
                                        store.discardCameraCapture(pending)
                                    }
                                    busy = false
                                    message = exception.userMessage("Camera capture failed")
                                }
                            },
                        )
                    },
                ) {
                    Text("Take photo")
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { importLauncher.launch(arrayOf("image/*")) },
                ) {
                    Text("Import image")
                }
                if (busy) CircularProgressIndicator()
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CameraPreview(
    onImageCaptureReady: (ImageCapture?) -> Unit,
    onCameraError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentReadyCallback by rememberUpdatedState(onImageCaptureReady)
    val currentErrorCallback by rememberUpdatedState(onCameraError)
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)

    DisposableEffect(context, lifecycleOwner, previewView) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var orientationListener: OrientationEventListener? = null
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                if (disposed) return@addListener
                runCatching {
                    val cameraProvider = providerFuture.get()
                    val initialRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                    val preview = Preview.Builder().setTargetRotation(initialRotation).build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(initialRotation)
                        .build()
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                    )
                    provider = cameraProvider
                    currentReadyCallback(capture)

                    var lastRotationDegrees = surfaceRotationToDegrees(initialRotation)
                    orientationListener = object : OrientationEventListener(context) {
                        override fun onOrientationChanged(orientation: Int) {
                            val targetDegrees = cameraTargetRotationDegrees(orientation, lastRotationDegrees)
                            if (targetDegrees != lastRotationDegrees) {
                                lastRotationDegrees = targetDegrees
                                val targetRotation = degreesToSurfaceRotation(targetDegrees)
                                preview.targetRotation = targetRotation
                                capture.targetRotation = targetRotation
                            }
                        }
                    }.also { if (it.canDetectOrientation()) it.enable() }
                }.onFailure { failure ->
                    currentReadyCallback(null)
                    currentErrorCallback(failure.userMessage("Camera is unavailable"))
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            disposed = true
            orientationListener?.disable()
            provider?.unbindAll()
            currentReadyCallback(null)
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun Throwable.userMessage(fallback: String): String =
    message?.takeIf { it.isNotBlank() } ?: fallback

private fun surfaceRotationToDegrees(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

private fun degreesToSurfaceRotation(degrees: Int): Int = when (degrees) {
    90 -> Surface.ROTATION_90
    180 -> Surface.ROTATION_180
    270 -> Surface.ROTATION_270
    else -> Surface.ROTATION_0
}
