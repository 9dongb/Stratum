package com.kiln.stratum.engine.command

import com.kiln.stratum.engine.document.Document

/**
 * 편집 하나. 앱은 엔진에 이 명령만 보낸다.
 *
 * [applyTo]는 받은 문서를 바꾸지 않고, 편집을 적용한 새 문서를 돌려준다.
 * 바뀐 것이 없으면 받은 문서를 그대로 돌려주고, 그러면 실행 취소 기록에도 남지 않는다.
 * 되돌리기는 명령이 아니라 실행 취소 기록이 이전 상태를 보관해서 처리한다.
 *
 * 화면이 한발 늦은 정보로 보낸 명령(이미 없는 레이어 등)은 오류 대신 "변화 없음"으로 처리한다.
 * 값 자체가 잘못된 경우(범위 밖 위치 등)에만 오류를 낸다.
 */
interface EditCommand {
    fun applyTo(document: Document): Document
}
