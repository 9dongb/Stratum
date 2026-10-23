package com.kiln.stratum.ui.edit

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kiln.stratum.R
import com.kiln.stratum.engine.face.FacePart
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.tone.ToneRange
import com.kiln.stratum.engine.transform.Orientation

/*
 * 커버 화면 한 줄 막대([CoverToolBar])의 칸들(결정 기록 '커버 화면 개편'). 값 칩의 슬라이더(값·할 일·이름표)는 메인 화면 띠의 칸(ToolStrips.kt)을 그대로 쓴다.
 * 순서는 띠와 같은 규칙이다: 수치가 아닌 칸(색·범위)을 앞에, 그 뒤에 크기 → 경도 → 불투명도 → 강도.
 * 하위 도구는 막대에 두지 않고 탭·더보기 줄의 하위 도구 메뉴(꾹 누르기)로만 고른다(2026-10-23 사용자 요청, 메인 화면과 같다).
 * 띠에 없는 것: 선택 동작(메인은 아래 가운데 작업 표시줄).
 */

/** [cells] 가운데 슬라이더 칸만 값 칩으로 */
private fun valueChips(cells: List<StripItem>): List<BarItem> = cells.filterIsInstance<SliderCell>().map(::BarValue)

/** 브러시·지우개의 막대: (브러시만) 색, 크기, 경도, 불투명도 */
@Composable
internal fun brushBar(controls: BrushControls): List<BarItem> = buildList {
    controls.colors?.takeIf { !controls.erase }?.let { add(BarColors(it)) }
    addAll(valueChips(brushStrip(controls)))
}

/** 텍스트의 막대: 색, 크기 */
@Composable
internal fun textBar(controls: TextControls): List<BarItem> = buildList {
    controls.colors?.let { add(BarColors(it)) }
    addAll(valueChips(textStrip(controls)))
}

/** 명암의 막대([mode]는 닷지·번): 범위(어두운·중간·밝은 영역), 크기, 경도, 노출 */
@Composable
internal fun toneBar(mode: SubTool, controls: ToneControls): List<BarItem> {
    val options = controls.options
    return buildList {
        add(
            BarChoice(
                key = "range",
                caption = stringResource(R.string.strip_tone_range),
                options = ToneRange.entries.map { range ->
                    BarOption(
                        label = stringResource(toneRangeLabel(range)),
                        icon = toneRangeIcon(range),
                        selected = options.range == range,
                        tag = EditScreenTags.toneRange(range)
                    ) { controls.onChange(options.copy(range = range)) }
                }
            )
        )
        addAll(valueChips(toneStrip(mode, controls, close = {})))
    }
}

/** 흐림의 막대(흐림·선명·스머지가 같다): 크기, 경도, 강도 */
@Composable
internal fun blurBar(controls: BlurControls): List<BarItem> = valueChips(blurStrip(controls))

/** 모자이크의 막대: 크기, 경도, 셀 크기 */
@Composable
internal fun mosaicBar(controls: MosaicControls): List<BarItem> = valueChips(mosaicStrip(controls))

/**
 * 유동화 새 창(커버)의 막대: 크기, 압력. 도구(뒤틀기·오목·볼록·재구성·얼굴)는 맨 아래 X와 ✓ 사이의 탭이다(새 창 공통 규칙).
 * 얼굴 모드에서 얼굴을 골랐으면 얼굴 슬라이더 칩(눈 크기·코 너비·얼굴 너비, 누르면 가운데 0인 슬라이더 한 줄)과 얼굴 초기화 칩이다.
 * 고른 얼굴이 없으면 칩이 없어 막대에 안내 글(찾는 중·고르기)이 보인다.
 */
@Composable
internal fun liquifyBar(
    state: LiquifyState,
    actions: LiquifyActions,
    sizePreview: SizePreviewState,
    facePart: FacePart,
    onFacePart: (FacePart) -> Unit
): List<BarItem> = buildList {
    val face = state.faceValues.takeIf { state.faceMode }
    // 얼굴 모드: [부위 ▾] [그 부위의 슬라이더 칩] [얼굴 초기화](계획 '화면')
    if (face != null) {
        add(
            BarChoice(
                key = "face_part",
                caption = stringResource(R.string.liquify_face_part),
                options = FacePart.entries.map { part ->
                    BarOption(
                        label = stringResource(facePartLabel(part)),
                        icon = null,
                        selected = part == facePart,
                        tag = EditScreenTags.facePart(part)
                    ) { onFacePart(part) }
                },
                enabled = !state.busy
            )
        )
    }
    addAll(valueChips(liquifyStrip(state, actions, sizePreview, facePart, onFacePart, close = {})))
    if (face == null) return@buildList
    add(
        BarButton(
            key = "face_reset",
            label = stringResource(R.string.liquify_face_reset),
            icon = R.drawable.ic_face_retouching_off,
            onClick = actions::resetFace,
            enabled = !state.busy && !face.isZero,
            tag = EditScreenTags.LIQUIFY_FACE_RESET
        )
    )
}

/**
 * 선택의 막대([mode]는 사각형·원형·올가미·자동·개체): 합치는 방법(새로·더하기·빼기·교차), 페더(자동 선택은 허용치와 '이어진 곳만'),
 * 동작(모두·해제·반전·지우기·복사·잘라내기·AI 채우기, 펼치면 버튼 줄). 개체 선택은 페더가 없다(띠와 같다).
 */
@Composable
internal fun selectionBar(mode: SubTool, controls: SelectionControls): List<BarItem> {
    val options = controls.options
    return buildList {
        add(
            BarChoice(
                key = "mode",
                caption = stringResource(R.string.bar_selection_mode),
                // 커버는 글자만이다(사용자 피드백 11차 2: 사각형 아이콘은 "펼친 화면 띠만")
                options = SelectionMode.entries.map { entry ->
                    BarOption(
                        label = stringResource(selectionModeLabel(entry)),
                        icon = null,
                        selected = options.mode == entry,
                        tag = EditScreenTags.selectionMode(entry)
                    ) { controls.onOptions(options.copy(mode = entry)) }
                }
            )
        )
        addAll(valueChips(selectionStrip(mode, controls)))
        if (mode == SubTool.SELECT_WAND) {
            add(
                BarToggle(
                    key = "contiguous",
                    label = stringResource(R.string.selection_contiguous),
                    checked = options.contiguous,
                    onChange = { controls.onOptions(options.copy(contiguous = it)) },
                    tag = EditScreenTags.SELECTION_CONTIGUOUS
                )
            )
        }
        add(
            BarGroup(
                "actions",
                stringResource(R.string.bar_selection_actions),
                columnContent = { SelectionActionButtons(controls, vertical = true) }
            ) { SelectionActionButtons(controls, compact = true) }
        )
    }
}

/**
 * 이동 도구의 막대: 고른 레이어 돌리기·뒤집기 칩 넷([LayerTurns], 사용자 피드백 12차 2). 자르기·회전 창과 같은 아이콘·이름이고,
 * 화면 읽기 기능은 '레이어를 …'으로 읽는다. 처리 중에는 꺼진다([enabled]).
 */
@Composable
internal fun moveBar(onOrient: (Orientation) -> Unit, enabled: Boolean): List<BarItem> =
    LayerTurns.map { turn ->
        BarButton(
            key = turn.key,
            label = stringResource(turn.label),
            icon = turn.icon,
            onClick = { onOrient(turn.orientation) },
            enabled = enabled,
            description = stringResource(turn.description),
            iconRotation = turn.iconRotation
        )
    }

/** 리터칭의 막대([mode]는 스팟 복구·복구·복제·AI 제거): (AI 제거) 고른 물체 지우기, 크기, (AI 제거가 아니면) 경도, (복제만) 불투명도 */
@Composable
internal fun retouchBar(mode: SubTool, controls: RetouchControls): List<BarItem> = buildList {
    if (mode == SubTool.RETOUCH_REMOVE) {
        // 톡 눌러 고른 물체를 지운다(고른 것이 있을 때만 켜지는 파란 버튼, 전 커버 옵션과 같은 이름표)
        add(
            BarButton(
                key = "removePicked",
                label = stringResource(R.string.retouch_remove_picked),
                icon = R.drawable.ic_auto_awesome,
                onClick = controls.onRemovePicked,
                enabled = controls.objectPicked && !controls.busy,
                accent = true,
                description = stringResource(R.string.retouch_remove_picked_description),
                tag = EditScreenTags.RETOUCH_REMOVE_PICKED
            )
        )
    }
    addAll(valueChips(retouchStrip(mode, controls)))
}
