package com.kiln.stratum.engine.document

import com.kiln.stratum.engine.face.FaceLandmarks
import com.kiln.stratum.engine.face.FaceSlider
import com.kiln.stratum.engine.face.FaceValues
import com.kiln.stratum.engine.liquify.LiquifyMesh
import com.kiln.stratum.engine.raster.Raster

/**
 * 다시 고칠 수 있는 픽셀 유동화의 기록(결정 기록 '얼굴 인식 보정' 3단계, 사용자 결정 "유동화 전체"). 레이어 픽셀([Layer.content])은
 * 이 기록을 유동화 전 원래 픽셀 [base]에 건 결과다. 유동화를 다시 열면 이 기록에서 이어서 고치고, 적용하면 [base]에서 다시 계산한다.
 * 그래서 값을 0으로 되돌린 곳은 원래 픽셀로 돌아간다.
 *
 * 한 번 만들면 바꾸지 않는다. [mesh]도 이 기록만의 사본이라 아무도 바꾸지 않는다(작업 모드는 사본을 만들어 고친다).
 * 레이어 픽셀을 바꾸는 다른 명령은 모두 이 기록을 지운다([Layer.withPixels], 사용자 결정 "묻지 않고 굳히기").
 *
 * @property base 유동화 전 원래 픽셀(문서 크기). 결과 픽셀과 바뀌지 않은 타일을 함께 써 메모리가 적다.
 * @property baseGainmap 유동화 전 원래 게인맵(HDR). 없으면 null.
 * @property mesh 손으로 민 변위 격자(문서 크기). 손으로 민 것이 없으면 null.
 * @property faces 찾은 얼굴(원래 그림의 문서 좌표, 왼쪽부터). 다시 열 때 다시 찾지 않는다(앱·모델이 바뀌어도 고친 값이 움직이지 않게).
 * @property faceValues 얼굴마다의 슬라이더 값([faces]와 같은 차례)
 * @property version 얼굴 보정 계산식의 판. 계산식을 바꾸어 같은 값이 다른 모양이 되면 올린다.
 */
class LiquifyRecord(
    val base: Raster,
    val baseGainmap: HdrGainmap?,
    val mesh: LiquifyMesh?,
    val faces: List<FaceLandmarks>,
    val faceValues: List<FaceValues>,
    val version: Int = VERSION
) {
    init {
        require(faceValues.size == faces.size) { "${faceValues.size} face values for ${faces.size} faces" }
    }

    /**
     * 지금 계산식으로 그리면 레이어 픽셀(기록할 때의 계산식으로 그림)과 모양이 다른지: 기록의 판과 지금 판에서 계산식이 다른 슬라이더([changedSince])에
     * 0이 아닌 값이 있다. 다시 연 유동화는 바꾼 것이 없어도 이때 적용할 수 있다(미리 보기가 지금 계산식이라).
     */
    val drawsDifferently: Boolean
        get() {
            val changed = changedSince(version)
            return changed.isNotEmpty() && faceValues.any { values -> changed.any { values[it] != 0f } }
        }

    companion object {
        /**
         * 지금 얼굴 보정 계산식의 판. 판 1·2 기록도 열고, 다시 적용하면 지금 계산식으로 그린다(앞 판 앱은 더 새 판의 기록을 버리고 결과 그림으로 연다).
         * - 2(2026-10-23): 뒷트임이 눈동자를 움직이지 않게 바뀌었다. 콧등 너비·콧볼·중안부·머리 크기가 생겼다.
         * - 3(2026-10-24): 뒷트임을 판 1 계산식으로 되돌리고, 콧볼(콧방울 덩어리)·콧등 너비(코끝까지)·중안부(콧등 아래부터 모양 그대로)를 바꿨다. 광대가 생겼다.
         */
        const val VERSION = 3

        /** 판 [version]의 계산식과 지금 계산식이 다른 슬라이더 */
        fun changedSince(version: Int): Set<FaceSlider> = when {
            version >= VERSION -> emptySet()
            version == 2 -> setOf(FaceSlider.EYE_TAIL, FaceSlider.NOSE_WINGS, FaceSlider.RIDGE_WIDTH, FaceSlider.MIDFACE)
            // 판 1: 뒷트임은 지금과 같고, 콧볼·콧등 너비·중안부는 판 2에서 생겨 판 1 기록에는 값이 없다
            else -> emptySet()
        }
    }
}

/**
 * 유동화를 시작할 픽셀의 레이어: 유동화 기록이 있으면 유동화 전 원래 픽셀(과 게인맵)로 돌린 레이어, 없으면 그대로다.
 * 다시 연 유동화 작업 모드의 미리 보기와 얼굴 찾기가 쓴다.
 */
val Layer.liquifySource: Layer
    get() = liquify?.let { withPixels(it.base, it.baseGainmap) } ?: this
