package com.kiln.stratum.engine.history

import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.liquify.LiquifyMesh
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.selection.MaskTile
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 실행 취소·다시 실행 기록.
 *
 * 명령을 실행할 때마다 결과 문서를 보관하고, 실행 취소는 보관해 둔 이전 문서로 돌아간다.
 * 문서는 바뀌지 않는 값이고 바뀌지 않은 레이어·타일은 이전 상태와 같은 객체를 쓰므로,
 * 보관하는 데 드는 메모리는 각 단계에서 바뀐 부분만큼이다.
 *
 * 단계 수만으로는 메모리가 묶이지 않는다. 큰 사진을 돌리면 단계마다 레이어 한 장만큼 새 타일이 생기기 때문이다.
 * 그래서 남겨 둔 문서들의 타일이 [maxBytes]를 넘으면 가장 오래된 단계부터 버린다. 지금 문서는 버리지 않는다.
 *
 * 스레드 하나에서만 쓴다. 무거운 편집은 다른 스레드에서 계산한 뒤 [commit]으로 기록한다.
 *
 * @param initial 처음 문서
 * @property maxUndoSteps 실행 취소할 수 있는 최대 단계 수. 넘으면 가장 오래된 단계부터 버린다.
 * @property maxBytes 남겨 둔 문서들의 타일이 쓸 수 있는 메모리(바이트). 같은 타일 객체는 한 번만 센다.
 */
class EditHistory(
    initial: Document,
    val maxUndoSteps: Int = DEFAULT_MAX_UNDO_STEPS,
    val maxBytes: Long = Long.MAX_VALUE
) {

    init {
        require(maxUndoSteps >= 1) { "maxUndoSteps must be at least 1: $maxUndoSteps" }
        require(maxBytes >= 0) { "maxBytes must not be negative: $maxBytes" }
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
     * 남겨 둔 모든 문서(실행 취소·다시 실행할 것 포함)의 타일이 쓰는 메모리(바이트). 같은 타일 객체는 한 번만 센다.
     * 선택 영역의 알파 타일과 게인맵 타일도 센다.
     */
    val bytesInUse: Long
        get() {
            val usage = Usage()
            usage.add(base)
            for (step in steps) usage.add(step.after)
            return usage.bytes
        }

    /**
     * 오래된 실행 취소 단계를 모두 버려도 남는 메모리(바이트): 지금 문서와 다시 실행할 단계의 타일이다(같은 타일 객체는 한 번만 센다).
     * [releaseOldSteps]로 메모리를 비우기 전에, 비워서 될 일인지 보는 데 쓴다.
     */
    val unreleasableBytes: Long
        get() {
            val usage = Usage()
            usage.add(document)
            for (index in position until steps.size) usage.add(steps[index].after)
            return usage.bytes
        }

    /**
     * [command]를 지금 문서에 적용하고 기록한다. 문서가 바뀌었을 때만 true를 돌려준다.
     *
     * 바뀐 것이 없으면 기록하지 않고, 다시 실행할 단계도 그대로 둔다.
     * [command]가 오류를 내면 기록은 바뀌지 않는다.
     */
    fun execute(command: EditCommand): Boolean {
        val current = document
        return commit(command, current, command.applyTo(current))
    }

    /**
     * 다른 스레드에서 [before]에 [command]를 적용해 얻은 [after]를 기록한다. 기록했을 때만 true를 돌려준다.
     *
     * 그사이 지금 문서가 [before]가 아니게 됐으면(실행 취소 등) 한발 늦은 결과라 기록하지 않는다.
     * 바뀐 것이 없어도 기록하지 않고, 다시 실행할 단계도 그대로 둔다.
     */
    fun commit(command: EditCommand, before: Document, after: Document): Boolean {
        if (before !== document || after == before) return false
        // 실행 취소해 둔 단계(다시 실행 목록)는 버린다
        steps.subList(position, steps.size).clear()
        steps.add(Step(command, after))
        position = steps.size
        if (steps.size > maxUndoSteps) dropOldestStep()
        // 메모리 한도를 넘으면 가장 오래된 단계부터 버린다. 단계가 다 없어지면 지금 문서만 남는다.
        while (steps.isNotEmpty() && bytesInUse > maxBytes) dropOldestStep()
        return true
    }

    /**
     * 남겨 둔 문서들의 메모리가 [targetBytes] 이하가 될 때까지 가장 오래된 실행 취소 단계를 버린다.
     * 무거운 편집 전에 메모리를 비우는 데 쓴다. 지금 문서와 다시 실행할 단계는 버리지 않는다. 남은 메모리(바이트)를 돌려준다.
     */
    fun releaseOldSteps(targetBytes: Long): Long {
        var bytes = bytesInUse
        while (position > 0 && bytes > targetBytes) {
            dropOldestStep()
            bytes = bytesInUse
        }
        return bytes
    }

    /**
     * [document]가 아직 기록에 남아 있는지(지금 문서, 실행 취소·다시 실행으로 돌아갈 수 있는 문서). 같은 객체인지로 본다.
     * 기록 밖에서 문서를 붙잡아 두는 쪽(질감 향상의 마지막 채우기)이, 버려진 단계의 문서를 놓아 주는 데 쓴다.
     */
    fun contains(document: Document): Boolean = base === document || steps.any { it.after === document }

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

    /** 가장 오래된 단계를 버린다. 그 단계의 결과가 새 출발 문서가 된다. */
    private fun dropOldestStep() {
        // removeFirst()는 안드로이드 13·14에 없는 함수라 기기에서 오류가 나므로 removeAt(0)을 쓴다
        base = steps.removeAt(0).after
        position--
    }

    /**
     * 문서들이 쓰는 메모리 셈. 같은 타일·마스크 타일·격자 객체는 한 번만 센다(바뀌지 않은 부분은 단계끼리 함께 쓴다).
     * 레이어 픽셀·게인맵·마스크와, 유동화 기록의 원래 픽셀·게인맵·격자를 센다(원래 픽셀은 결과와 바뀌지 않은 타일을 함께 쓴다).
     */
    private class Usage {
        private val tiles = HashSet<Tile>()
        private val masks = HashSet<MaskTile>()
        private val meshes = Collections.newSetFromMap(IdentityHashMap<LiquifyMesh, Boolean>())

        fun add(document: Document) {
            document.selection?.let { masks.addAll(it.tiles.values) }
            for (layer in document.layers) {
                when (val content = layer.content) {
                    is RasterContent -> tiles.addAll(content.raster.tiles.values)
                }
                layer.gainmap?.let { tiles.addAll(it.raster.tiles.values) }
                layer.mask?.let { masks.addAll(it.tiles.values) }
                layer.liquify?.let { record ->
                    tiles.addAll(record.base.tiles.values)
                    record.baseGainmap?.let { tiles.addAll(it.raster.tiles.values) }
                    record.mesh?.let { meshes += it }
                }
            }
        }

        val bytes: Long
            get() = tiles.sumOf { it.byteCount.toLong() } + masks.sumOf { it.byteCount.toLong() } + meshes.sumOf { it.byteCount }
    }

    companion object {
        /** 기본 실행 취소 단계 수. 포토샵 기본값과 같다. */
        const val DEFAULT_MAX_UNDO_STEPS = 50
    }
}
