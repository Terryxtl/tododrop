package com.terryxtl.tododrop.ui.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Size as CameraSize
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.terryxtl.tododrop.BuildConfig
import com.terryxtl.tododrop.R
import com.terryxtl.tododrop.transfer.QrParseResult
import com.terryxtl.tododrop.transfer.QrPayload
import com.terryxtl.tododrop.ui.components.AppIcon
import com.terryxtl.tododrop.ui.components.EmptyState
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** 扫到非本 App 的二维码时，提示的最短间隔。 */
private const val HINT_INTERVAL_MS = 2_500L

@Composable
fun ScanScreen(onBack: () -> Unit, onScanned: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    // 首次进入时自动申请一次权限；旋转屏幕后不再重复申请
    var autoRequested by rememberSaveable { mutableStateOf(false) }
    // 被拒绝后由 shouldShowRequestPermissionRationale 判断是否“不再询问”（含之前会话里的拒绝）
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    var showPaste by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // 手电筒：torchWanted 是要恢复的状态（旋转屏幕后重新应用），torchOn 是相机实际状态（界面显示）
    var torchWanted by rememberSaveable { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    val controller = remember(granted) { if (granted) LifecycleCameraController(context) else null }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        permanentlyDenied = !ok && context.findActivity()
            ?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
    }
    LaunchedEffect(Unit) {
        if (!granted && !autoRequested) {
            autoRequested = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
    // 从系统设置回来时重新检查权限
    LifecycleResumeEffect(Unit) {
        granted = hasCameraPermission(context)
        onPauseOrDispose {}
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = if (granted) Color.Black else MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { AppIcon(R.drawable.ic_arrow_back, stringResource(R.string.back)) }
                },
                actions = {
                    if (controller != null) {
                        IconToggleButton(
                            checked = torchOn,
                            onCheckedChange = {
                                torchWanted = it
                                controller.enableTorch(it)
                            },
                            shapes = IconButtonDefaults.toggleableShapes(),
                        ) {
                            AppIcon(
                                if (torchOn) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off,
                                stringResource(R.string.scan_torch),
                            )
                        }
                    }
                    if (BuildConfig.DEBUG) {
                        IconButton(onClick = { showPaste = true }) {
                            AppIcon(R.drawable.ic_content_paste, stringResource(R.string.scan_paste))
                        }
                    }
                },
                colors = if (granted) {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    )
                } else {
                    TopAppBarDefaults.topAppBarColors()
                },
            )
        },
    ) { padding ->
        if (controller != null) {
            CameraScanner(
                controller = controller,
                snackbar = snackbar,
                torchWanted = torchWanted,
                onTorchStateChanged = { on ->
                    torchOn = on
                    torchWanted = on
                },
                onScanned = onScanned,
            )
        } else {
            EmptyState(
                icon = R.drawable.ic_qr_code_scanner,
                title = stringResource(R.string.scan_permission_title),
                body = stringResource(R.string.scan_permission_body),
                modifier = Modifier.padding(padding),
                action = {
                    Button(
                        onClick = {
                            if (permanentlyDenied) {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                )
                            } else {
                                launcher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(if (permanentlyDenied) R.string.scan_permission_settings else R.string.scan_permission_grant))
                    }
                },
            )
        }
    }

    if (showPaste) {
        PasteDialog(
            onDismiss = { showPaste = false },
            onConfirm = { showPaste = false; onScanned(it) },
        )
    }
}

@Composable
private fun CameraScanner(
    controller: LifecycleCameraController,
    snackbar: SnackbarHostState,
    torchWanted: Boolean,
    onTorchStateChanged: (Boolean) -> Unit,
    onScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnScanned by rememberUpdatedState(onScanned)
    val currentTorchWanted by rememberUpdatedState(torchWanted)
    val currentOnTorchStateChanged by rememberUpdatedState(onTorchStateChanged)

    val msgNotOurs = stringResource(R.string.scan_not_tododrop)
    val msgUnsupported = stringResource(R.string.scan_unsupported)

    var handled by remember { mutableStateOf(false) }
    var lastHintAt by remember { mutableLongStateOf(0L) }

    DisposableEffect(controller, lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        // ZXing 解码较耗时，放到单独的后台线程；结果切回主线程处理
        val analysisExecutor = Executors.newSingleThreadExecutor()
        // 二维码里有多个地址和证书指纹，点阵较密，分析分辨率取 720p 左右
        controller.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(CameraSize(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()
        fun handle(raw: String) {
            if (handled) return
            val hint = when (val parsed = QrPayload.parse(raw)) {
                is QrParseResult.Ok -> {
                    handled = true
                    // 识别到有效二维码后立即停止分析
                    controller.clearImageAnalysisAnalyzer()
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    currentOnScanned(raw)
                    null
                }
                QrParseResult.NotTodoDrop -> msgNotOurs
                QrParseResult.UnsupportedVersion -> msgUnsupported
                is QrParseResult.Invalid ->
                    context.getString(R.string.scan_invalid, context.getString(parsed.reason.messageRes))
            }
            val now = System.currentTimeMillis()
            if (hint != null && now - lastHintAt > HINT_INTERVAL_MS) {
                lastHintAt = now
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(hint)
                }
            }
        }
        controller.setImageAnalysisAnalyzer(
            analysisExecutor,
            QrCodeAnalyzer { raw -> mainExecutor.execute { handle(raw) } },
        )

        // 相机连上后 torchState 会先发一次当前状态，这时把保存的手电筒状态重新应用一次
        // （旋转屏幕后控制器是新建的，手电筒默认关闭）
        var reapplyTorch = true
        val torchObserver = Observer<Int> { state ->
            val on = state == TorchState.ON
            if (reapplyTorch && currentTorchWanted && !on) {
                reapplyTorch = false
                controller.enableTorch(true)
            } else {
                reapplyTorch = false
                currentOnTorchStateChanged(on)
            }
        }
        controller.torchState.observe(lifecycleOwner, torchObserver)

        // 只需要预览 + 图像分析，不绑定拍照用例
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.torchState.removeObserver(torchObserver)
            // 后台线程可能还有一帧正在解码，离开页面后不再处理它的结果
            handled = true
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            analysisExecutor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        ViewfinderOverlay(Modifier.fillMaxSize())
        Text(
            stringResource(R.string.scan_hint),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 48.dp),
        )
    }
}

/** 半透明遮罩 + 中间圆角镂空的取景框。 */
@Composable
private fun ViewfinderOverlay(modifier: Modifier) {
    val frameColor = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                val side = minOf(size.width, size.height) * 0.68f
                val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2.4f)
                val radius = CornerRadius(40.dp.toPx())
                drawRect(Color.Black.copy(alpha = 0.55f))
                drawRoundRect(Color.Transparent, topLeft, Size(side, side), radius, blendMode = BlendMode.Clear)
                drawRoundRect(frameColor, topLeft, Size(side, side), radius, style = Stroke(width = 4.dp.toPx()))
            }
    )
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val parsed = remember(text) { QrPayload.parse(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_paste)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.trim() },
                placeholder = { Text(stringResource(R.string.scan_paste_placeholder)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = parsed is QrParseResult.Ok) {
                Text(stringResource(R.string.scan_paste_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
