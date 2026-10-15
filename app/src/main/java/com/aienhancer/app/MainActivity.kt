package com.aienhancer.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App()
            }
        }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var imageUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCamera = granted }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) imageUri = uri }

    val pickFromGallery: () -> Unit = {
        pickLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    LaunchedEffect(Unit) {
        if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    BackHandler(enabled = imageUri != null) { imageUri = null }

    val uri = imageUri
    when {
        uri != null -> PhotoPreview(uri = uri, onBack = { imageUri = null })
        hasCamera -> CameraScreen(
            onImageReady = { imageUri = it },
            onImport = pickFromGallery
        )
        else -> PermissionScreen(
            onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onImport = pickFromGallery
        )
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit, onImport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Для съёмки нужен доступ к камере")
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) {
            Text("Разрешить камеру")
        }
        OutlinedButton(onClick = onImport, modifier = Modifier.padding(top = 8.dp)) {
            Text("Выбрать из галереи")
        }
    }
}

/** Временный экран: показывает выбранное фото. На Шаге 5 заменим на сравнение До/После. */
@Composable
private fun PhotoPreview(uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }

    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                ImageUtils.loadBitmap(context, uri, 1600)
            } catch (e: Exception) {
                failed = true
                null
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            when {
                bmp != null -> Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                failed -> Text("Не удалось открыть фото", color = Color.White)
                else -> CircularProgressIndicator()
            }
        }
        val info = bitmap?.let { "${it.width} × ${it.height}" } ?: ""
        Text(info, color = Color.White, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = onBack, modifier = Modifier.padding(16.dp)) {
            Text("Назад к камере")
        }
    }
}
