package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.skin.SkinColor
import com.kiln.stratum.engine.skin.SkinFaceArea
import com.kiln.stratum.engine.skin.SkinFaceTone
import com.kiln.stratum.engine.skin.SkinTone
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 피부 도구(결정 기록 '피부 보정'의 2c 화면)의 자리와 띠·막대: 고르면 한 번 [SkinActions.picked]를 보내고,
 * 그룹이 없으면 [피부 보정 만들기], 있으면 [마스크 보기](누르면 색 보정 레이어로 켜고 끔)를 보인다.
 */
@RunWith(AndroidJUnit4::class)
class SkinToolTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()

    private val recorder = object : SkinActions {
        override fun picked() {
            calls += "picked"
        }

        override fun create() {
            calls += "create"
        }

        override fun toggleMask(colorLayer: LayerId) {
            calls += "mask ${colorLayer.value}"
        }

        override fun autoReference(colorLayer: LayerId) {
            calls += "auto ${colorLayer.value}"
        }

        override fun healSpots(group: LayerId) {
            calls += "heal ${group.value}"
        }

        override fun rebuild(group: LayerId) {
            calls += "rebuild ${group.value}"
        }
    }

    private val document = PixelSize(1000, 1000)
    private val preview = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)

    private fun summary(id: Long, name: String, parent: Long? = null) =
        LayerSummary(LayerId(id), name, 1f, BlendMode.NORMAL, Adjustments.NONE, parent = parent?.let(::LayerId))

    /** 얼굴 하나를 잰 피부 색(슬라이더는 부분 붉은기 35%) */
    private val skinColor = SkinColor(
        local = 0.35f,
        faces = listOf(SkinFaceTone(SkinFaceArea(500f, 500f, 200f, 120f), SkinTone(0.7f, 0.85f, 0.055f, 0.055f, 0f, 0.05f, 0.006f)))
    )

    /** 사진 하나 */
    private val photoOnly = listOf(summary(0, "Photo"))

    /** 사진 위 [피부 보정] 그룹(원본 0, 색 보정 2 + 마스크, 피부 정돈 3, 잡티 제거 4, 그룹 1) */
    private val withGroup = listOf(
        summary(0, "Photo"),
        summary(4, "Spots", parent = 1),
        summary(3, "Smooth", parent = 1).copy(opacity = 0f, hasMask = true),
        summary(2, "Color", parent = 1).copy(adjustment = true, hasMask = true, skinColor = skinColor),
        summary(1, "Skin", parent = null).copy(group = true, skin = SkinSummary(LayerId(0), LayerId(2), LayerId(3), LayerId(4), 1, true, 0.5f))
    )

    private var layers by mutableStateOf(photoOnly)

    /** [active]를 고른 편집 화면. [veil]이면 그 레이어의 빨간 막(마스크 보기)을 보이는 중이다. */
    private fun show(widthDp: Int, heightDp: Int, active: LayerId = LayerId(0), veil: Boolean = false) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                CompositionLocalProvider(LocalHinges provides emptyList()) {
                    StratumTheme {
                        EditScreen(
                            state = EditorState.Ready(
                                PreviewImage(preview, PreviewLevels.wholeRegion(document, 1)),
                                document,
                                layers = layers,
                                activeLayer = active,
                                editingMask = veil,
                                maskOverlay = if (veil) PreviewImage(preview, PreviewLevels.wholeRegion(document, 1)) else null
                            ),
                            onBack = {},
                            onCanvasSize = { _, _ -> },
                            onViewport = {},
                            onChooseSize = {},
                            skinActions = recorder
                        )
                    }
                }
            }
        }
    }

    @Test
    fun main_skinFollowsRetouch_andPickingItOnceAsksForTheGroup() {
        show(930, 700)
        val tools = EditTool.railTools
        assertEquals(tools.indexOf(EditTool.Retouch) + 1, tools.indexOf(EditTool.Skin))

        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Skin)).performClick()

        // 그룹이 없으면 띠에 [피부 보정 만들기]만 보인다
        composeRule.onNodeWithTag(EditScreenTags.SKIN_CREATE).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(EditScreenTags.SKIN_MASK).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf("picked", "create"), calls) }
    }

    @Test
    fun main_withAGroup_theStripShowsTheMaskView() {
        layers = withGroup
        show(930, 700)

        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Skin)).performClick()

        composeRule.onNodeWithTag(EditScreenTags.SKIN_CREATE).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.SKIN_MASK).assertIsDisplayed().assertIsOff().performClick()
        composeRule.runOnIdle { assertEquals(listOf("picked", "mask 2"), calls) }
    }

    @Test
    fun main_withMeasuredFaces_theStripHasTheReference_andTheThreeColorSliders() {
        layers = withGroup
        show(930, 700)

        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Skin)).performClick()

        for (key in listOf("skinLocal", "skinGlobal", "skinEven")) composeRule.onNodeWithTag(EditScreenTags.strip(key)).assertExists()
        // 기준: 스포이드를 고르면 캔버스가 스포이드 손가락이 되고, 자동을 고르면 기준을 지운다
        composeRule.onNodeWithTag(EditScreenTags.strip(SKIN_REFERENCE_KEY)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.skinReference(SkinReferenceChoice.PICK)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.EYEDROPPER_OVERLAY).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.strip(SKIN_REFERENCE_KEY)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.skinReference(SkinReferenceChoice.AUTO)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.EYEDROPPER_OVERLAY).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf("picked", "auto 2"), calls) }
    }

    @Test
    fun cover_skinIsInTheMoreRow_andTheBarHasTheSameButtons() {
        show(411, 800)

        composeRule.chooseCoverTool(EditTool.Skin)

        composeRule.onNodeWithTag(EditScreenTags.SKIN_CREATE).assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("picked", "create"), calls)
            layers = withGroup
        }
        // 커버 막대의 켜고 끄는 칩은 고른 모양(선택)으로 켜짐을 알린다
        composeRule.onNodeWithTag(EditScreenTags.SKIN_MASK).assertIsDisplayed().assertIsNotSelected().performClick()
        composeRule.runOnIdle { assertEquals(listOf("picked", "create", "mask 2"), calls) }
    }

    @Test
    fun main_theStripHealsSpots_andRebuilds_forTheGroup() {
        // 결정 기록 '피부 보정'의 4·5단계: [잡티 자동 제거]·[다시 만들기]는 그룹에 한다
        layers = withGroup
        show(930, 700)

        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Skin)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.SKIN_HEAL).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(EditScreenTags.SKIN_REBUILD).assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals(listOf("picked", "heal 1", "rebuild 1"), calls) }
    }

    @Test
    fun healingSpots_isOff_whenTheSpotsLayerIsGone() {
        layers = withGroup.filterNot { it.id == LayerId(4) }
        show(930, 700)

        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Skin)).performClick()

        composeRule.onNodeWithTag(EditScreenTags.SKIN_HEAL).assertIsNotEnabled()
    }

    @Test
    fun cover_theBarHealsSpots() {
        layers = withGroup
        show(411, 800)

        composeRule.chooseCoverTool(EditTool.Skin)
        composeRule.onNodeWithTag(EditScreenTags.SKIN_HEAL).performScrollTo().assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(listOf("picked", "heal 1"), calls) }
    }

    @Test
    fun maskView_isOff_whenTheColorLayerIsGone() {
        layers = withGroup.filterNot { it.id == LayerId(2) }
        show(930, 700)

        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Skin)).performClick()

        composeRule.onNodeWithTag(EditScreenTags.SKIN_MASK).assertIsNotEnabled()
    }

    @Test
    fun maskView_isOn_whileTheColorLayerShowsTheRedVeil() {
        layers = withGroup
        show(930, 700, active = LayerId(2), veil = true)

        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Skin)).performClick()

        composeRule.onNodeWithTag(EditScreenTags.SKIN_MASK).assertIsOn()
    }
}
