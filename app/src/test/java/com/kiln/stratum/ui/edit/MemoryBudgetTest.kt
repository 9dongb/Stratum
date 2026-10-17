package com.kiln.stratum.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryBudgetTest {

    @Test
    fun budget_isAvailableMemoryAboveLowMemoryLine() {
        // 남은 3GB 중 시스템이 '메모리 부족'으로 보는 선(200MB) 위까지만 쓴다
        assertEquals(2_800_000_000L, MemoryBudget.budgetFrom(3_000_000_000L, 200_000_000L))
    }

    @Test
    fun budget_isZeroWhenAlreadyBelowLowMemoryLine() {
        assertEquals(0L, MemoryBudget.budgetFrom(150_000_000L, 200_000_000L))
    }
}
