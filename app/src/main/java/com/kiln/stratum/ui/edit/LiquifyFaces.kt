package com.kiln.stratum.ui.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.face.FaceLandmarks
import com.kiln.stratum.engine.face.FaceMeshIndex
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.CanvasOverlayColors
import kotlin.math.roundToInt

/**
 * 얼굴 모드(얼굴 인식 보정)의 캔버스: 찾은 얼굴([faces], 화면에 보이는 자리의 문서 좌표)의 윤곽을 그리고, 톡하면 그 자리를 [onPick]으로 알린다(칠하지 않는다).
 * 고른 얼굴([selected])은 파란 윤곽, 나머지 얼굴은 흰 윤곽이다(2단계부터 특징점·좌표축·번호는 그리지 않는다, 계획 '화면').
 * 윤곽은 얼굴 보정과 손으로 민 만큼 따라 움직인다(앱이 보이는 자리로 넘김).
 * 손가락이 움직이지 않은 톡만 알린다. 두 손가락 조작(확대·이동·톡)은 부모([CanvasViewport])가 먼저 읽고 소비한다(소비된 손 떼기는 톡이 아니다).
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
        faces.getOrNull(selected)?.let { drawFaceOutline(it, screen, CanvasOverlayColors.FaceSelected) }
    }
}

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
