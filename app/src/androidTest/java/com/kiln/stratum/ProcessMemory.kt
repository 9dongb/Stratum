package com.kiln.stratum

import android.os.Debug

/** 앱 프로세스의 메모리(KB). 모델 시험(`InpaintModelProbeTest`·`SegmentProbeTest`)이 불러오기 전후를 견준다. */
internal data class ProcessMemory(val pssKb: Int, val graphicsKb: Int, val nativeKb: Int) {
    companion object {
        fun now(): ProcessMemory {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            fun stat(name: String) = info.getMemoryStat(name)?.toIntOrNull() ?: 0
            return ProcessMemory(info.totalPss, stat("summary.graphics"), stat("summary.native-heap"))
        }
    }
}
