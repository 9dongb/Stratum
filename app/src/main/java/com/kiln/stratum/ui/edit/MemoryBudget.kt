package com.kiln.stratum.ui.edit

import android.app.ActivityManager
import android.content.Context

/** 사진을 여는 데 쓸 수 있는 메모리를 안드로이드에 묻는다. */
object MemoryBudget {

    /**
     * 지금 사진을 여는 데 쓸 수 있는 메모리(바이트).
     * 시스템이 '메모리 부족'으로 보고 다른 앱을 정리하기 시작하는 선까지 남은 양이다.
     */
    fun importBudgetBytes(context: Context): Long {
        val manager = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        return budgetFrom(info.availMem, info.threshold)
    }

    /** [availableBytes]에서 [lowMemoryThresholdBytes]를 뺀 값. 이미 그 선 아래면 0이다. */
    fun budgetFrom(availableBytes: Long, lowMemoryThresholdBytes: Long): Long =
        (availableBytes - lowMemoryThresholdBytes).coerceAtLeast(0L)
}
