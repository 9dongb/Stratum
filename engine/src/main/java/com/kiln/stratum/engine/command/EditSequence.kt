package com.kiln.stratum.engine.command

import com.kiln.stratum.engine.document.Document

/**
 * 명령 여럿을 차례로 적용하는 명령. 실행 취소 한 단계로 남는다.
 * 슬라이더 두 개(보정·불투명도)를 거의 동시에 놓았을 때 한 번에 기록하는 데 쓴다.
 * 모두 바뀐 것이 없으면 받은 문서를 그대로 돌려준다(기록하지 않음).
 */
data class EditSequence(val commands: List<EditCommand>) : EditCommand {
    override fun applyTo(document: Document): Document =
        commands.fold(document) { current, command -> command.applyTo(current) }
}
