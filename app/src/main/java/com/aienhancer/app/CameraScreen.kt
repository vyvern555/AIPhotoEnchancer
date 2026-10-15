package com.aienhancer.app

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            { cont.resume(future.get()) },
            ContextCompat.getMainExecutor(this)
        )
    }

@Composable
fun CameraScreen(
    onImageReady: (Uri) -> Unit,
    onImport: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var capturing by remember { mutableStateOf(false) }

    // Запуск камеры
    LaunchedEffect(Unit) {
        try {
            val p = context.awaitCameraProvider()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            p.unbindAll()
            camera = p.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
            provider = p
        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка камеры: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Освобождаем камеру при уходе с экрана
    DisposableEffect(provider) {
        onDispose { provider?.unbindAll() }
    }

    // Следим за зумом камеры
    DisposableEffect(camera) {
        val c = camera
        if (c == null) {
            onDispose { }
        } else {
            val observer = Observer<ZoomState> { state ->
                zoom = state.zoomRatio
                minZoom = state.minZoomRatio
                maxZoom = state.maxZoomRatio
            }
            c.cameraInfo.zoomState.observe(lifecycleOwner, observer)
            onDispose { c.cameraInfo.zoomState.removeObserver(observer) }
        }
    }

    fun setZoom(value: Float) {
        camera?.cameraControl?.setZoomRatio(value.coerceIn(minZoom, maxZoom))
    }

    fun takePhoto() {
        if (capturing) return
        capturing = true
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    onImageReady(Uri.fromFile(file))
                }

                override fun onError(exc: ImageCaptureException) {
                    capturing = false
                    Toast.makeText(context, "Ошибка съёмки: ${exc.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Прозрачный слой для щипка (pinch-to-zoom)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(camera) {
                    detectTransformGestures { _, _, gestureZoom, _ ->
                        if (gestureZoom != 1f) setZoom(zoom * gestureZoom)
                    }
                }
        )

        // Текущий зум сверху
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x66000000))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text("${"%.1f".format(zoom)}×", color = Color.White, fontSize = 16.sp)
        }

        // Нижняя панель
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Пресеты зума
            val presets = remember(minZoom, maxZoom) {
                listOf(0.6f, 1f, 2f, 3f, 5f, 10f, 30f, 50f, 100f)
                    .filter { it >= minZoom - 0.01f && it <= maxZoom + 0.01f }
            }
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { p ->
                    val selected = abs(zoom - p) / p < 0.08f
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) Color.White else Color(0x66000000))
                            .clickable { setZoom(p) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        val label = if (p < 1f) "$p" else "${p.toInt()}"
                        Text(
                            "$label×",
                            color = if (selected) Color.Black else Color.White,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Плавный ползунок зума (логарифмическая шкала)
            if (maxZoom > minZoom + 0.01f) {
                Slider(
                    value = ln(zoom.coerceIn(minZoom, maxZoom)),
                    onValueChange = { setZoom(exp(it)) },
                    valueRange = ln(minZoom)..ln(maxZoom),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color(0x55FFFFFF)
                    ),
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            // Галерея — Затвор — пустое место
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onImport,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "Импорт из галереи", tint = Color.White)
                }

                Box(
                    Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(if (capturing) Color.Gray else Color.White)
                        .clickable(enabled = !capturing) { takePhoto() }
                )

                Spacer(Modifier.size(56.dp))
            }
        }
    }
}
