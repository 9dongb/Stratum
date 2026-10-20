package com.kiln.stratum.engine.platform

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * AI 모델 계산을 하는 스레드 하나(결정 기록 'AI 제거·AI 선택').
 * 모델 객체는 이 스레드에서만 만들고, 쓰고, 닫는다. 그래서 GPU를 쓰는 모델끼리 겹치지 않는다.
 * 앱이 살아 있는 동안 남고, 앱이 끝나면 함께 끝난다(데몬 스레드).
 */
internal object AiThread {

    const val NAME = "stratum-ai"

    val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, NAME).apply { isDaemon = true } }
    }
}
