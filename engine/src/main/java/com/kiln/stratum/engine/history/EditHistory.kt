package com.kiln.stratum.engine.history

import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.document.Document

/**
 * 실행 취소·다시 실행 기록.
 *
 * 명령을 실행할 때마다 결과 문서를 보관하고, 실행 취소는 보관해 둔 이전 문서로 돌아간다.
 * 문서는 바뀌지 않는 값이고 바뀌지 않은 레이어·타일은 이전 상태와 같은 객체를 쓰므로,
 * 보관하는 데 드는 메모리는 각 단계에서 바뀐 부분만큼이다.
 *
 * 스레드 하나에서만 쓴다.
 *
 * @param initial 처음 문서
 * @property maxUndoSteps 실행 취소할 수 있는 최대 단계 수. 넘으면 가장 오래된 단계부터 버린다.
 */
class EditHistory(initial: Document, val maxUndoSteps: Int = DEFAULT_MAX_UNDO_STEPS) {

    init {
        require(maxUndoSteps >= 1) { "maxUndoSteps must be at least 1: $maxUndoSteps" }
    }

    /** 실행한 명령 하나와 그 결과 문서. */
    private class Step(val command: EditCommand, val after: Document)

    /** 남아 있는 가장 오래된 단계 바로 전의 문서. 이보다 이전으로는 돌아갈 수 없다. */
    private var base: Document = initial

    /** 실행한 단계들. [position]부터 뒤쪽은 실행 취소해 둔(다시 실행할 수 있는) 단계다. */
    private val steps = ArrayList<Step>()

    /** 지금 문서까지 적용된 단계 수. */
    private var position = 0

    /** 지금 문서. */
    val document: Document
        get() = if (position == 0) base else steps[position - 1].after

    val canUndo: Boolean
        get() = position > 0

    val canRedo: Boolean
        get() = position < steps.size

    /** 실행 취소하면 되돌려지는 명령. 없으면 null. */
    val undoCommand: EditCommand?
        get() = steps.getOrNull(position - 1)?.command

    /** 다시 실행하면 다시 적용되는 명령. 없으면 null. */
    val redoCommand: EditCommand?
        get() = steps.getOrNull(position)?.command

    /**
     * [command]를 지금 문서에 적용하고 기록한다. 문서가 바뀌었을 때만 true를 돌려준다.
     *
     * 바뀐 것이 없으면 기록하지 않고, 다시 실행할 단계도 그대로 둔다.
     * [command]가 오류를 내면 기록은 바뀌지 않는다.
     */
    fun execute(command: EditCommand): Boolean {
        val current = document
        val after = command.applyTo(current)
        if (after == current) return false
        // 실행 취소해 둔 단계(다시 실행 목록)는 버린다
        steps.subList(position, steps.size).clear()
        steps.add(Step(command, after))
        if (steps.size > maxUndoSteps) {
            // removeFirst()는 안드로이드 13·14에 없는 함수라 기기에서 오류가 나므로 removeAt(0)을 쓴다
            base = steps.removeAt(0).after
        }
        position = steps.size
        return true
    }

    /** 한 단계 이전 문서로 돌아간다. 돌아갈 단계가 없으면 false를 돌려준다. */
    fun undo(): Boolean {
        if (!canUndo) return false
        position--
        return true
    }

    /** 실행 취소한 단계를 다시 적용한다. 다시 실행할 단계가 없으면 false를 돌려준다. */
    fun redo(): Boolean {
        if (!canRedo) return false
        position++
        return true
    }

    companion object {
        /** 기본 실행 취소 단계 수. 포토샵 기본값과 같다. */
        const val DEFAULT_MAX_UNDO_STEPS = 50
    }
}
