package com.kiln.stratum.ui.edit

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag

/**
 * 편집 화면 캔버스 영역 안의 그림(문서). 화면 읽기 기능이 머무는 곳이라, 지금 배율(상태 설명)과 '화면에 맞추기' 동작이 여기 있다
 * ([CanvasViewport]). 유동화 작업 모드의 캔버스는 편집 화면 캔버스 영역 밖이라 고르지 않는다.
 */
fun SemanticsNodeInteractionsProvider.canvasImage(): SemanticsNodeInteraction =
    onNode(hasAnyAncestor(hasTestTag(EditScreenTags.CANVAS)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
