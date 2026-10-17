package com.kiln.stratum.ui.edit

import android.app.ActivityManager
import android.content.Context

/** 사진을 열거나 편집하는 데 쓸 수 있는 메모리를 안드로이드에 묻는다. */
object MemoryBudget {

    /**
     * 지금 더 쓸 수 있는 메모리(바이트). 사진을 열거나 큰 편집을 하기 전에 본다.
     * 시스템이 '메모리 부족'으로 보고 다른 앱을 정리하기 시작하는 선까지 남은 양이다.
     */
    fun availableBytes(context: Context): Long {
        val info = memoryInfo(context)
        return budgetFrom(info.availMem, info.threshold)
    }

    /** 지금 사진을 여는 데 쓸 수 있는 메모리(바이트). [availableBytes]와 같다. */
    fun importBudgetBytes(context: Context): Long = availableBytes(context)

    /** 실행 취소 기록이 쓸 수 있는 메모리(바이트). 기기 전체 메모리의 [HISTORY_SHARE]이다. */
    fun historyBudgetBytes(context: Context): Long = historyBudgetFrom(memoryInfo(context).totalMem)

    /** [availableBytes]에서 [lowMemoryThresholdBytes]를 뺀 값. 이미 그 선 아래면 0이다. */
    fun budgetFrom(availableBytes: Long, lowMemoryThresholdBytes: Long): Long =
        (availableBytes - lowMemoryThresholdBytes).coerceAtLeast(0L)

    /**
     * 기기 전체 메모리가 [totalBytes]일 때 실행 취소 기록의 한도. 12GB 기기면 1.5GB로, 50MP 사진 레이어 약 7장이다.
     * 큰 사진을 여러 번 돌려도 앱이 시스템에 정리될 만큼 메모리를 쓰지 않게 한다.
     */
    fun historyBudgetFrom(totalBytes: Long): Long = totalBytes / HISTORY_SHARE

    private fun memoryInfo(context: Context): ActivityManager.MemoryInfo {
        val manager = context.getSystemService(ActivityManager::class.java)
        return ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
    }

    /** 실행 취소 기록에 주는 몫(기기 전체 메모리의 1/8). */
    private const val HISTORY_SHARE = 8
}
