package com.kiln.stratum.ui.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.face.FaceLandmarks
import com.kiln.stratum.engine.face.FaceMeshIndex
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.CanvasOverlayColors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 얼굴 모드(얼굴 인식 보정)의 캔버스: 찾은 얼굴([faces], 화면에 보이는 자리의 문서 좌표)을 그리고, 톡하면 그 자리를 [onPick]으로 알린다(칠하지 않는다).
 * 손가락이 움직이지 않은 톡만 알린다. 두 손가락 조작(확대·이동·톡)은 부모([CanvasViewport])가 먼저 읽고 소비한다(소비된 손 떼기는 톡이 아니다).
 *
 * 1단계 확인 화면: 고른 얼굴([selected])은 파란 윤곽과 478점, 얼굴 좌표축(빨강: 눈 선 쪽, 파랑: 턱 쪽, 길이는 크기 단위),
 * 계산에 쓰는 점의 번호를 그리고, 나머지 얼굴은 흰 윤곽만 그린다. 번호 글자는 화면 반전을 되돌려 늘 바로 읽힌다.
 * 얼굴이 화면에서 작으면(두 눈 사이 96dp 미만) 점을 가늘게 하고 번호는 숨긴다(두 손가락으로 확대하면 보인다).
 */
@Composable
fun FaceOverlay(
    view: () -> CanvasView,
    document: PixelSize,
    faces: List<FaceLandmarks>,
    selected: Int,
    enabled: Boolean,
    onPick: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentPick by rememberUpdatedState(onPick)
    val currentEnabled by rememberUpdatedState(enabled)
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = CanvasOverlayColors.FaceLabel,
        shadow = Shadow(CanvasOverlayColors.Dark, blurRadius = 3f)
    )
    val flip = LocalViewFlip.current
    val description = faces.getOrNull(selected)?.let { stringResource(R.string.liquify_face_selected, selected + 1, faces.size) }
    Canvas(
        modifier
            .testTag(EditScreenTags.LIQUIFY_FACE_OVERLAY)
            .semantics { if (description != null) stateDescription = description }
            .pointerInput(document) {
                // 톡만 고른다: 손가락이 터치 허용 거리보다 움직였거나, 두 번째 손가락이 닿았거나, 부모가 가져간(두 손가락 조작) 터치는 아니다
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!currentEnabled) return@awaitEachGesture
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) return@awaitEachGesture
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                        if (!change.pressed) {
                            change.consume()
                            val transform = view().transform(PixelSize(size.width, size.height), document)
                            currentPick(transform.toDocumentX(change.position.x.toDouble()).toFloat(), transform.toDocumentY(change.position.y.toDouble()).toFloat())
                            return@awaitEachGesture
                        }
                    }
                }
            }
    ) {
        val transform = view().transform(PixelSize(size.width.roundToInt(), size.height.roundToInt()), document)
        val screen = { face: FaceLandmarks, index: Int ->
            Offset(transform.toScreenX(face.x(index).toDouble()).toFloat(), transform.toScreenY(face.y(index).toDouble()).toFloat())
        }
        faces.forEachIndexed { i, face -> if (i != selected) drawFaceOutline(face, screen, CanvasOverlayColors.Light) }
        val face = faces.getOrNull(selected) ?: return@Canvas
        drawFaceOutline(face, screen, CanvasOverlayColors.FaceSelected)
        // 얼굴이 화면에서 작으면 점을 가늘게 하고 번호는 숨긴다(확대하면 보인다). 겹쳐서 읽을 수 없게 되지 않게.
        val eyesOnScreen = face.eyeDistance * transform.scaleX.toFloat()
        drawPoints(
            List(face.points.size / 2) { screen(face, it) },
            PointMode.Points,
            CanvasOverlayColors.FacePoint,
            strokeWidth = (eyesOnScreen / POINT_DIVISOR).coerceIn(1.dp.toPx(), 3.dp.toPx()),
            cap = StrokeCap.Round
        )
        drawFaceAxes(face, screen)
        if (eyesOnScreen >= LabelMinEyes.toPx()) {
            for (index in FaceMeshIndex.CALCULATION_POINTS) {
                drawLabel(measurer, index.toString(), screen(face, index), labelStyle, flip)
            }
        }
    }
}

/** 점 굵기 = 화면의 두 눈 사이 / 이것(1~3dp) */
private const val POINT_DIVISOR = 30f

/** 화면의 두 눈 사이가 이보다 짧으면 번호를 그리지 않는다 */
private val LabelMinEyes = 96.dp

/** 얼굴 윤곽([FaceMeshIndex.FACE_OVAL])을 [color] 선(어두운 테두리 위)으로 */
private fun DrawScope.drawFaceOutline(face: FaceLandmarks, screen: (FaceLandmarks, Int) -> Offset, color: Color) {
    val path = Path()
    FaceMeshIndex.FACE_OVAL.forEachIndexed { k, index ->
        val point = screen(face, index)
        if (k == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    drawPath(path, CanvasOverlayColors.Dark, style = Stroke(width = 4.dp.toPx()))
    drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
}

/**
 * 얼굴 좌표축: 두 눈동자 가운데에서 눈 선 쪽(오른쪽 눈 → 왼쪽 눈, 빨강)과 그에 수직인 턱 쪽(파랑). 길이는 크기 단위
 * U = max(두 눈 사이, 눈 가운데–턱 끝의 반)이다(계획 '얼굴 좌표계'). 고개를 돌려 두 눈 사이가 좁아져도 축이 짧아지지 않는다.
 */
private fun DrawScope.drawFaceAxes(face: FaceLandmarks, screen: (FaceLandmarks, Int) -> Offset) {
    val right = screen(face, FaceMeshIndex.RIGHT_IRIS)
    val left = screen(face, FaceMeshIndex.LEFT_IRIS)
    val chin = screen(face, FaceMeshIndex.CHIN)
    val origin = (right + left) / 2f
    val eyes = hypot(left.x - right.x, left.y - right.y)
    if (eyes <= 0f) return
    val across = Offset((left.x - right.x) / eyes, (left.y - right.y) / eyes)
    var down = Offset(-across.y, across.x)
    if (down.x * (chin.x - origin.x) + down.y * (chin.y - origin.y) < 0f) down = -down
    val unit = max(eyes, hypot(chin.x - origin.x, chin.y - origin.y) / 2f)
    for ((direction, color) in listOf(across to CanvasOverlayColors.FaceAxisAcross, down to CanvasOverlayColors.FaceAxisDown)) {
        val end = origin + direction * unit
        drawLine(CanvasOverlayColors.Dark, origin, end, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(color, origin, end, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
    drawCircle(CanvasOverlayColors.Dark, 4.dp.toPx(), origin)
    drawCircle(CanvasOverlayColors.Light, 3.dp.toPx(), origin)
}

/** 점 [at]의 오른쪽 위에 번호 [text]. 캔버스가 반전되어 있으면 글자만 되돌려 바로 읽히게 한다. */
private fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, at: Offset, style: TextStyle, flip: ViewFlip) {
    val topLeft = at + Offset(3.dp.toPx(), -14.dp.toPx())
    if (!flip.isFlipped) {
        drawText(measurer, text, topLeft, style)
        return
    }
    withTransform({ scale(flip.scaleX, flip.scaleY, pivot = at) }) { drawText(measurer, text, topLeft, style) }
}

/** 얼굴 모드의 막대 안내(닫힌 화면): 찾는 중, 못 찾음, 여럿이라 고르기, 고른 얼굴 */
@Composable
internal fun faceHint(state: LiquifyState): String {
    val faces = state.faces
    return when {
        state.findingFaces -> stringResource(R.string.liquify_face_finding)
        // 찾지 못하고 실패했으면(쓸 수 없는 기기·메모리) 그 까닭
        faces == null -> state.faceNotice?.let { faceNoticeText(it) }.orEmpty()
        faces.isEmpty() -> stringResource(R.string.liquify_face_none)
        state.selectedFace in faces.indices -> stringResource(R.string.liquify_face_selected, state.selectedFace + 1, faces.size)
        else -> stringResource(R.string.liquify_face_pick)
    }
}

/** 얼굴 모드가 한 번 알릴 글자 */
@Composable
internal fun faceNoticeText(notice: FaceNotice): String = when (notice.kind) {
    FaceNotice.Kind.NOT_FOUND -> stringResource(R.string.liquify_face_none)
    FaceNotice.Kind.SEVERAL -> stringResource(R.string.liquify_face_several, notice.count)
    FaceNotice.Kind.UNAVAILABLE -> stringResource(R.string.liquify_face_unavailable)
    FaceNotice.Kind.NOT_ENOUGH_MEMORY -> stringResource(R.string.liquify_face_memory)
}
