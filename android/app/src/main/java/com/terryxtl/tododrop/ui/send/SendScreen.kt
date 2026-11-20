package com.terryxtl.tododrop.ui.send

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.terryxtl.tododrop.ui.export.NotesCard
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.terryxtl.tododrop.LocalAppContainer
import com.terryxtl.tododrop.R
import com.terryxtl.tododrop.transfer.TransferClient
import com.terryxtl.tododrop.transfer.TransferError
import com.terryxtl.tododrop.ui.components.AppIcon
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SendScreen(onBack: () -> Unit, onDone: () -> Unit, onRescan: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: SendViewModel = viewModel {
        SendViewModel(container.repository, container.transferClient, container.deviceName, createSavedStateHandle())
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(state::class) {
        when (state) {
            is SendUiState.Success -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            is SendUiState.Failed -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.send_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { AppIcon(R.drawable.ic_arrow_back, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        // motionScheme 只能在组合中读取，transitionSpec 不是 @Composable，所以先取出来。
        val motion = MaterialTheme.motionScheme
        val enterEffects = motion.defaultEffectsSpec<Float>()
        val enterSpatial = motion.defaultSpatialSpec<Float>()
        val exitEffects = motion.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = {
                (fadeIn(enterEffects) + scaleIn(enterSpatial, initialScale = 0.92f))
                    .togetherWith(fadeOut(exitEffects))
            },
            modifier = Modifier.fillMaxSize().padding(padding),
            label = "send",
        ) { s ->
            // 成功页可能列出多个文件和提醒，内容放不下时可以滚动；放得下时仍然垂直居中
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val minHeight = maxHeight
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = minHeight)
                        .navigationBarsPadding()
                        .padding(horizontal = 32.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                when (s) {
                    is SendUiState.Connecting -> Progress(
                        s.pcName?.let { stringResource(R.string.send_connecting, it) }
                            ?: stringResource(R.string.send_connecting_unknown)
                    )
                    is SendUiState.Uploading -> Progress(stringResource(R.string.send_uploading, s.count))
                    is SendUiState.Success -> Success(s, onDone)
                    is SendUiState.Failed -> Failure(s.reason, onRetry = vm::start, onRescan = onRescan, onBack = onBack)
                }
                }
            }
        }
    }
}

@Composable
private fun Progress(title: String) {
    ContainedLoadingIndicator(Modifier.size(120.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
}

@Composable
private fun Success(s: SendUiState.Success, onDone: () -> Unit) {
    SuccessBadge()
    Text(stringResource(R.string.send_success, s.pcName), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text(
        stringResource(R.string.send_success_count, s.count, s.files.size),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                s.files.forEach { file ->
                    Text(
                        file.savedAs,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
    NotesCard(s.notes)
    Button(
        onClick = onDone,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.padding(top = 16.dp).fillMaxWidth(0.6f),
    ) { Text(stringResource(R.string.send_done)) }
}

@Composable
private fun Failure(reason: FailureReason, onRetry: () -> Unit, onRescan: () -> Unit, onBack: () -> Unit) {
    val (title, body) = when (reason) {
        FailureReason.NothingToSend ->
            stringResource(R.string.send_err_nothing_title) to stringResource(R.string.send_err_nothing_body)
        is FailureReason.InvalidQr ->
            stringResource(R.string.send_err_invalid_title) to stringResource(reason.messageRes)
        is FailureReason.Transfer -> when (val e = reason.error) {
            TransferError.Unreachable ->
                stringResource(R.string.send_err_unreachable_title) to stringResource(R.string.send_err_unreachable_body)
            TransferError.FingerprintMismatch ->
                stringResource(R.string.send_err_fingerprint_title) to stringResource(R.string.send_err_fingerprint_body)
            TransferError.Unauthorized ->
                stringResource(R.string.send_err_unauthorized_title) to stringResource(R.string.send_err_unauthorized_body)
            TransferError.NotTodoDrop ->
                stringResource(R.string.send_err_not_tododrop_title) to stringResource(R.string.send_err_not_tododrop_body)
            is TransferError.ProtocolMismatch ->
                stringResource(R.string.send_err_protocol_title) to
                    stringResource(R.string.send_err_protocol_body, e.serverProtocol, TransferClient.PROTOCOL_VERSION)
            TransferError.UploadTimeout ->
                stringResource(R.string.send_err_timeout_title) to stringResource(R.string.send_err_timeout_body)
            TransferError.UploadInterrupted ->
                stringResource(R.string.send_err_interrupted_title) to stringResource(R.string.send_err_interrupted_body)
            is TransferError.Rejected -> stringResource(R.string.send_err_rejected_title) to rejectedBody(e)
            TransferError.BadResponse ->
                stringResource(R.string.send_err_failed_title) to stringResource(R.string.send_err_bad_response_body)
            TransferError.Failed ->
                stringResource(R.string.send_err_failed_title) to stringResource(R.string.send_err_failed_body)
        }
    }
    MorphBadge(
        from = MaterialShapes.Circle,
        to = MaterialShapes.Cookie4Sided,
        container = MaterialTheme.colorScheme.errorContainer,
    ) {
        val offline = reason is FailureReason.Transfer &&
            (reason.error == TransferError.Unreachable || reason.error == TransferError.UploadInterrupted)
        val icon = if (offline) {
            R.drawable.ic_wifi_off
        } else {
            R.drawable.ic_error
        }
        AppIcon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
    }
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text(
        body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (reason == FailureReason.NothingToSend) {
            Button(onClick = onBack, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.back)) }
        } else {
            OutlinedButton(onClick = onRescan, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.send_rescan)) }
            if (reason is FailureReason.Transfer) {
                Button(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

/** 电脑端拒绝时的说明：优先用电脑端返回的 `error`（我们自己的 CLI，中文），没有就按状态码给通用提示。 */
@Composable
private fun rejectedBody(e: TransferError.Rejected): String {
    val detail = e.serverMessage
    return when {
        detail != null -> stringResource(R.string.send_err_rejected_detail, detail, e.httpCode)
        e.httpCode == 400 -> stringResource(R.string.send_err_rejected_400)
        e.httpCode == 413 -> stringResource(R.string.send_err_rejected_413)
        else -> stringResource(R.string.send_err_rejected_http, e.httpCode)
    }
}

/**
 * 成功徽章：圆形底座弹出，里面的 [MaterialShapes.SoftBurst] 一边回正一边形变成对勾。
 * 弹出和形变都用 Expressive motionScheme 的空间弹簧。
 */
@Composable
private fun SuccessBadge() {
    val morph = remember { Morph(MaterialShapes.SoftBurst, CheckMark) }
    val containerScale = remember { Animatable(0.4f) }
    val progress = remember { Animatable(0f) }
    val motion = MaterialTheme.motionScheme
    val popSpec = motion.defaultSpatialSpec<Float>()
    val morphSpec = motion.slowSpatialSpec<Float>()
    LaunchedEffect(morph) {
        launch { containerScale.animateTo(1f, popSpec) }
        delay(CHECK_MORPH_DELAY_MS)
        progress.animateTo(1f, morphSpec)
    }
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val markColor = MaterialTheme.colorScheme.onPrimaryContainer
    val path = remember { android.graphics.Path() }
    val matrix = remember { android.graphics.Matrix() }
    Canvas(Modifier.size(144.dp)) {
        val pop = containerScale.value
        scale(pop) { drawCircle(containerColor) }

        // 弹簧会略微越过终点；形变进度限制在 0..1，避免对勾的凹角被外推得自相交
        val p = progress.value
        val markSize = size.minDimension * 0.62f * pop
        path.rewind()
        morph.toPath(p.coerceIn(0f, 1f), path)
        matrix.reset()
        matrix.setScale(markSize, markSize)
        matrix.postTranslate(center.x - markSize / 2f, center.y - markSize / 2f)
        path.transform(matrix)
        rotate(degrees = (1f - p) * -135f) { drawPath(path.asComposePath(), markColor) }
    }
}

private const val CHECK_MORPH_DELAY_MS = 120L

/** 对勾形状：一条粗折线的轮廓，坐标归一化到单位正方形，和 MaterialShapes 一致。 */
private val CheckMark: RoundedPolygon by lazy { checkMarkPolygon() }

private fun checkMarkPolygon(): RoundedPolygon {
    val start = Offset(0.17f, 0.47f)
    val corner = Offset(0.40f, 0.70f)
    val end = Offset(0.84f, 0.24f)
    val half = 0.085f // 笔画半宽

    fun dir(a: Offset, b: Offset): Offset = (b - a) / (b - a).getDistance()
    fun cross(a: Offset, b: Offset): Float = a.x * b.y - a.y * b.x
    val d1 = dir(start, corner)
    val d2 = dir(corner, end)
    // 法线指向对勾的外侧（下方）
    val n1 = Offset(-d1.y, d1.x)
    val n2 = Offset(-d2.y, d2.x)

    // 两段笔画的边线在拐角处的交点；side = 1 是外侧，-1 是内侧
    fun join(side: Float): Offset {
        val a = start + n1 * (half * side)
        val b = corner + n2 * (half * side)
        val t = cross(b - a, d2) / cross(d1, d2)
        return a + d1 * t
    }

    // 顶点绕向和 RoundedPolygon / MaterialShapes 一致（屏幕坐标下顺时针），形变时才不会翻面
    val points = listOf(
        end - n2 * half,
        end + n2 * half,
        join(1f),
        start + n1 * half,
        start - n1 * half,
        join(-1f),
    )
    val tip = CornerRounding(radius = 0.04f)
    val outer = CornerRounding(radius = 0.03f)
    val inner = CornerRounding(radius = 0.02f)
    return RoundedPolygon(
        vertices = points.flatMap { listOf(it.x, it.y) }.toFloatArray(),
        perVertexRounding = listOf(tip, tip, outer, tip, tip, inner),
        centerX = 0.5f,
        centerY = 0.5f,
    )
}

/** 出现时从 [from] 形变到 [to]，同时缓慢旋转、弹性放大的徽章。 */
@Composable
private fun MorphBadge(
    from: RoundedPolygon,
    to: RoundedPolygon,
    container: Color,
    content: @Composable () -> Unit,
) {
    val morph = remember(from, to) { Morph(from, to) }
    val progress = remember { Animatable(0f) }
    val scale = remember { Animatable(0.4f) }
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(morph) {
        launchAll(
            { progress.animateTo(1f, spatial) },
            { scale.animateTo(1f, spatial) },
        )
    }
    Box(Modifier.size(144.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    rotationZ = progress.value * 90f
                }
                .clip(MorphShape(morph, progress.value))
                .background(container)
        )
        Box(Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }) { content() }
    }
}

private suspend fun launchAll(vararg blocks: suspend () -> Unit) = coroutineScope {
    blocks.forEach { b -> launch { b() } }
}

/** 把 [Morph] 在给定进度下的轮廓画成 Shape（MaterialShapes 的多边形已归一化到单位正方形）。 */
private class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress)
        val matrix = android.graphics.Matrix().apply { setScale(size.width, size.height) }
        path.transform(matrix)
        return Outline.Generic(path.asComposePath())
    }
}
