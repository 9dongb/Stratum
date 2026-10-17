package com.kiln.stratum.ui.layout

import android.app.Activity
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.map

/** 힌지 양쪽으로 버튼을 두지 않는 너비. CLAUDE.md 6장: 힌지에는 버튼·슬라이더를 두지 않는다. */
val HingeMargin = 16.dp

/** 지금 창을 지나는 힌지들(창 좌표). MainActivity가 넣는다. 세 번 접는 기기는 둘이고, 접힌 폰·커버 화면은 비어 있다. */
val LocalHinges = compositionLocalOf<List<Hinge>> { emptyList() }

/**
 * [activity] 창을 지나는 힌지들. WindowManager가 접힘 정보를 알려 줄 때마다 바뀐다.
 *
 * 첫 화면에는 아직 정보가 없어 빈 목록이고, 곧 들어오면 한 번 다시 배치된다.
 * 완전히 펼친 상태(FLAT)여도 접히는 선이 있으면 넣는다.
 * WindowManager 문서대로 이 흐름은 Activity보다 오래 들고 있지 않으므로, ViewModel이 아니라 여기서 받는다.
 */
@Composable
fun rememberWindowHinges(activity: Activity): List<Hinge> {
    val hinges by remember(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { info ->
            val folds = info.displayFeatures.filterIsInstance<FoldingFeature>()
            // 에뮬레이터·기기가 실제로 무엇을 알려 주는지 볼 수 있게 남긴다(adb logcat -s WindowHinges)
            Log.d(TAG, "Folding features: " + folds.joinToString { "${it.bounds} ${it.orientation} ${it.state} separating=${it.isSeparating}" })
            folds.map { it.toHinge() }
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    return hinges
}

private fun FoldingFeature.toHinge(): Hinge =
    Hinge(
        left = bounds.left,
        top = bounds.top,
        right = bounds.right,
        bottom = bounds.bottom,
        isVertical = orientation == FoldingFeature.Orientation.VERTICAL
    )

private const val TAG = "WindowHinges"
