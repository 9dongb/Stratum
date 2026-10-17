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

/**
 * 오래 걸리는 편집(큰 이미지의 픽셀을 옮기거나 다시 계산하는 일). 화면은 백그라운드에서 취소 확인과 함께 부른다.
 * [checkCanceled]는 타일 사이마다 부르며, 취소됐으면 예외를 던지게 한다(사진 열기·축소본 그리기와 같은 방식).
 * 메모리가 모자라 편집하지 못하면 [NotEnoughMemoryException]을 던진다.
 */
interface CancellableCommand : EditCommand {
    fun applyTo(document: Document, checkCanceled: () -> Unit): Document

    override fun applyTo(document: Document): Document = applyTo(document) {}
}

/** 편집에 쓸 메모리가 모자라 편집하지 못했다. "변화 없음"(같은 문서를 돌려줌)과 구별하려고 따로 둔다. */
class NotEnoughMemoryException(cause: Throwable? = null) : RuntimeException("Not enough memory for the edit", cause)
