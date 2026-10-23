package com.kiln.stratum.engine.document

import com.kiln.stratum.engine.face.FaceLandmarks

/**
 * [피부 보정] 그룹의 기록(결정 기록 '피부 보정'의 2c). 그룹 레이어([Layer.skin])에 붙고 프로젝트에 저장된다.
 * 그룹 안의 세 레이어(색 보정·피부 정돈·잡티 제거)는 보통 레이어라, 사용자가 따로 끄고 불투명도를 바꾸고 마스크를 칠할 수 있다.
 *
 * @property source 원본 사진 레이어(그룹 바로 아래, 바꾸지 않음). 피부 정돈·잡티 제거가 읽는다.
 * @property color 색 보정 조정 레이어(피부 색 종류), [smooth] 피부 정돈 레이어, [spots] 잡티 제거 레이어. 사용자가 지우면 그 기능은 쉰다.
 * @property faces 찾은 얼굴(문서 좌표, 왼쪽부터). 색 통계·정돈 반지름·잡티 찾기가 얼굴마다 쓴다.
 * @property segmented 분할 모델로 피부를 골랐는지. false면 얼굴 윤곽만으로 골랐다(목·귀·이마 위가 빠짐).
 * @property texture 질감 유지(0..1, 4단계): 피부 정돈이 고운 결을 얼마나 남기는지.
 */
data class SkinRecord(
    val source: LayerId,
    val color: LayerId,
    val smooth: LayerId,
    val spots: LayerId,
    val faces: List<FaceLandmarks>,
    val segmented: Boolean,
    val texture: Float = DEFAULT_TEXTURE
) {
    init {
        require(texture in 0f..1f) { "Texture must be within 0..1: $texture" }
    }

    /** 그룹을 복제할 때: 안의 레이어 번호를 [ids](원래 → 복사본)대로 바꾼 기록. 원본 레이어는 그대로다. */
    fun remapped(ids: Map<LayerId, LayerId>): SkinRecord =
        copy(color = ids[color] ?: color, smooth = ids[smooth] ?: smooth, spots = ids[spots] ?: spots)

    companion object {
        /** 질감 유지의 처음 값(0단계 맥 시험: 0은 매끈해 플라스틱 느낌, 50%가 알맞음) */
        const val DEFAULT_TEXTURE = 0.5f
    }
}
