package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.multiTouchSwipe
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 화면 반전(사용자 피드백 7차, 결정 기록 '화면 반전'): 캔버스만 뒤집혀 보이고, 터치는 같은 변환을 거꾸로 거쳐
 * 문서의 반대쪽 자리로 가며, 두 손가락 이동·세 손가락 밀기는 화면의 손가락 방향을 따른다. 문서는 바뀌지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class ViewFlipTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val document = PixelSize(400, 200)
    private val viewState = CanvasViewState(CanvasView.Fit)
    private var flip by mutableStateOf(ViewFlip.None)

    /** 도구 화면이 받은 톡(문서 자리) */
    private val taps = mutableListOf<Offset>()
    private val swipes = mutableListOf<Pair<SwipeAxis, Float>>()

    private val recorder = object : ThreeFingerActions {
        override fun tap() = Unit

        override fun swipe(axis: SwipeAxis, distance: Dp) {
            swipes += axis to distance.value
        }

        override fun swipeEnd() = Unit
    }

    /** 왼쪽 반은 빨강, 오른쪽 반은 파랑이고 위 1/4은 초록 띠인 문서의 축소본(원본 크기) */
    private fun picture(): Bitmap {
        val bitmap = Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.BLUE)
        val paint = Paint()
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, document.width / 2f, document.height.toFloat(), paint)
        paint.color = Color.GREEN
        canvas.drawRect(0f, 0f, document.width.toFloat(), document.height / 4f, paint)
        return bitmap
    }

    private fun ready() = EditorState.Ready(PreviewImage(picture(), PreviewLevels.wholeRegion(document, 0)), document)

    /** 문서와 같은 비율(2:1)의 캔버스 영역. 맞춤이면 문서가 영역을 꽉 채운다. */
    private fun showViewport() {
        val ready = ready()
        composeRule.setContent {
            StratumTheme {
                Box(Modifier.size(400.dp, 200.dp)) {
                    CanvasViewport(ready, viewState, Modifier.fillMaxSize().testTag(TAG), flip = flip, threeFinger = recorder) {
                        // 한 손가락 톡을 문서 자리로 바꿔 적는 도구 화면(글자 도구처럼)
                        Box(
                            Modifier.fillMaxSize().pointerInput(Unit) {
                                detectTapGestures { position ->
                                    val transform = viewState.view.transform(PixelSize(size.width, size.height), document)
                                    taps += Offset(transform.toDocumentX(position.x.toDouble()).toFloat(), transform.toDocumentY(position.y.toDouble()).toFloat())
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    /** 캔버스 그림에서 비율 자리([fx], [fy])의 색 */
    private fun colorAt(tag: String, fx: Float, fy: Float): Int {
        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        return image.getPixel((image.width * fx).toInt(), (image.height * fy).toInt())
    }

    private fun assertColor(expected: Int, actual: Int) {
        assertTrue("expected ${Integer.toHexString(expected)} but was ${Integer.toHexString(actual)}",
            abs(Color.red(expected) - Color.red(actual)) < 40 && abs(Color.green(expected) - Color.green(actual)) < 40 && abs(Color.blue(expected) - Color.blue(actual)) < 40)
    }

    @Test
    fun flipping_mirrorsOnlyTheDrawing() {
        showViewport()
        assertColor(Color.RED, colorAt(TAG, 0.1f, 0.6f))
        assertColor(Color.BLUE, colorAt(TAG, 0.9f, 0.6f))

        composeRule.runOnIdle { flip = ViewFlip(horizontal = true) }
        assertColor(Color.BLUE, colorAt(TAG, 0.1f, 0.6f))
        assertColor(Color.RED, colorAt(TAG, 0.9f, 0.6f))

        // 상하 반전이면 위의 초록 띠가 아래에 보인다
        composeRule.runOnIdle { flip = ViewFlip(vertical = true) }
        assertColor(Color.GREEN, colorAt(TAG, 0.1f, 0.9f))
        assertColor(Color.RED, colorAt(TAG, 0.1f, 0.1f))
        // 보기만 바뀌고 보기 상태(맞춤)는 그대로다
        composeRule.runOnIdle { assertEquals(CanvasView.Fit, viewState.view) }
    }

    @Test
    fun touchingTheRightOfAMirroredView_reachesTheLeftOfTheDocument() {
        flip = ViewFlip(horizontal = true)
        showViewport()

        composeRule.onNodeWithTag(TAG).performTouchInput { click(Offset(width * 0.9f, height * 0.5f)) }

        composeRule.runOnIdle {
            val tap = taps.single()
            // 반전하지 않았으면 x 360(오른쪽)이다
            assertEquals(40f, tap.x, 4f)
            assertEquals(100f, tap.y, 4f)
        }
    }

    @Test
    fun touchingTheTopOfAnUpsideDownView_reachesTheBottomOfTheDocument() {
        flip = ViewFlip(vertical = true)
        showViewport()

        composeRule.onNodeWithTag(TAG).performTouchInput { click(Offset(width * 0.25f, height * 0.1f)) }

        composeRule.runOnIdle {
            val tap = taps.single()
            assertEquals(100f, tap.x, 4f)
            assertEquals(180f, tap.y, 4f)
        }
    }

    @Test
    fun twoFingerDrag_onAMirroredView_movesThePictureWithTheFingers() {
        flip = ViewFlip(horizontal = true)
        showViewport()

        // 두 손가락을 화면에서 오른쪽으로 200px 끈다. 뒤집힌 그림이 손가락을 따라 오른쪽으로 가므로,
        // 가운데에 보이는 문서 자리는 문서의 오른쪽(x가 큰 쪽)으로 간다(반전하지 않으면 왼쪽).
        composeRule.onNodeWithTag(TAG).performTouchInput {
            val left = center - Offset(100f, 0f)
            val right = center + Offset(100f, 0f)
            multiTouchSwipe(
                curves = listOf(
                    { t: Long -> left + Offset(200f * t / 500f, 0f) },
                    { t: Long -> right + Offset(200f * t / 500f, 0f) }
                ),
                durationMillis = 500
            )
        }

        composeRule.runOnIdle {
            val view = viewState.view as CanvasView.Free
            assertTrue("centerX ${view.centerX}", view.centerX > 200.0 + 100.0 / view.scale)
        }
    }

    @Test
    fun threeFingerSwipes_followTheScreenDirection_evenWhenFlipped() {
        flip = ViewFlip(horizontal = true, vertical = true)
        showViewport()

        // 위로 밀면 +(크기 키우기), 왼쪽으로 밀면 -(경도 줄이기): 뒤집지 않았을 때와 같다
        composeRule.onNodeWithTag(TAG).performTouchInput { threeFingerSwipe(Offset(0f, -150f)) }
        composeRule.runOnIdle {
            assertTrue(swipes.all { it.first == SwipeAxis.VERTICAL })
            assertTrue("${swipes.last()}", swipes.last().second > 0f)
            swipes.clear()
        }
        composeRule.onNodeWithTag(TAG).performTouchInput { threeFingerSwipe(Offset(-150f, 0f)) }
        composeRule.runOnIdle {
            assertTrue(swipes.all { it.first == SwipeAxis.HORIZONTAL })
            assertTrue("${swipes.last()}", swipes.last().second < 0f)
        }
    }

    /** 편집 화면: 레이어 탭의 반전 버튼이 캔버스만 뒤집고, 편집 명령은 보내지 않으며, 다시 만들어져도 남는다 */
    @Test
    fun layerTabButtons_flipTheCanvas_withoutEditing_andSurviveRecreation() {
        val commands = mutableListOf<EditCommand>()
        val ready = ready()
        val tester = StateRestorationTester(composeRule)
        tester.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(state = ready, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {}, onEdit = { commands += it })
                }
            }
        }
        composeRule.togglePanel(MainPanel.LAYERS)
        val horizontal = composeRule.onNodeWithTag(EditScreenTags.VIEW_FLIP_HORIZONTAL)
        horizontal.assertIsOff().performClick()
        horizontal.assertIsOn()
        composeRule.onNodeWithTag(EditScreenTags.VIEW_FLIP_VERTICAL).assertIsOff()
        // 레이어를 닫으면 캔버스 전체가 보인다: 왼쪽이 파랑(오른쪽 반이 뒤집혀 보임)
        composeRule.togglePanel(MainPanel.LAYERS)
        assertColor(Color.BLUE, colorAt(EditScreenTags.CANVAS, 0.1f, 0.5f))
        assertColor(Color.RED, colorAt(EditScreenTags.CANVAS, 0.9f, 0.5f))

        // 접기·펼치기처럼 화면이 다시 만들어져도 남는다
        tester.emulateSavedInstanceStateRestore()
        assertColor(Color.BLUE, colorAt(EditScreenTags.CANVAS, 0.1f, 0.5f))
        composeRule.togglePanel(MainPanel.LAYERS)
        composeRule.onNodeWithTag(EditScreenTags.VIEW_FLIP_HORIZONTAL).assertIsOn().performClick().assertIsOff()
        composeRule.runOnIdle { assertTrue("$commands", commands.isEmpty()) }
    }

    /** 메인 화면: 떠 있는 레이어 패널 제목 줄에도 같은 버튼이 있다 */
    @Test
    fun mainScreen_floatingLayerPanelButtons_flipTheCanvas() {
        val ready = ready()
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(930.dp, 700.dp))) {
                StratumTheme {
                    EditScreen(state = ready, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {})
                }
            }
        }
        // 맞춤이면 문서(2:1)가 캔버스 폭을 채우고 위아래 가운데다. 왼쪽 아래(문서의 아래쪽)는 빨강이다.
        assertColor(Color.RED, colorAt(EditScreenTags.CANVAS, 0.1f, 0.75f))

        composeRule.onNodeWithTag(EditScreenTags.panelToggle(MainPanel.LAYERS)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.VIEW_FLIP_VERTICAL).performClick().assertIsOn()

        // 패널은 오른쪽 위라 왼쪽 아래를 본다: 문서 위쪽의 초록 띠가 아래에 보인다
        assertColor(Color.GREEN, colorAt(EditScreenTags.CANVAS, 0.1f, 0.75f))
    }

    /**
     * 줄여서 한쪽으로 옮긴 사진은 반전해도 그 자리에서 뒤집힌다(사용자 피드백 13차 11: "제자리에서 반전이 아닌 캔버스 위치가 반대편으로 이동하면서
     * 반전 됩니다"). 전에는 캔버스 영역 가운데를 기준으로 뒤집혀 사진이 반대편으로 건너갔다.
     */
    @Test
    fun flipping_keepsAZoomedOutPhotoWhereItIs() {
        val ready = ready()
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(930.dp, 700.dp))) {
                StratumTheme {
                    EditScreen(state = ready, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {})
                }
            }
        }
        val canvas = composeRule.onNodeWithTag(EditScreenTags.CANVAS)
        // 두 손가락으로 반쯤 줄이고(맞춤의 절반까지) 왼쪽으로 옮긴다: 사진은 캔버스 왼쪽 반에, 오른쪽은 빈 작업 공간
        canvas.performTouchInput {
            val a0 = center - Offset(width * 0.3f, 0f)
            val a1 = center - Offset(width * 0.1f, 0f)
            val b0 = center + Offset(width * 0.3f, 0f)
            val b1 = center + Offset(width * 0.1f, 0f)
            multiTouchSwipe(
                curves = listOf({ t: Long -> a0 + (a1 - a0) * (t / 400f) }, { t: Long -> b0 + (b1 - b0) * (t / 400f) }),
                durationMillis = 400
            )
        }
        canvas.performTouchInput {
            val a0 = center - Offset(width * 0.05f, 0f)
            val b0 = center + Offset(width * 0.05f, 0f)
            val shift = Offset(-width * 0.4f, 0f)
            multiTouchSwipe(
                curves = listOf({ t: Long -> a0 + shift * (t / 400f) }, { t: Long -> b0 + shift * (t / 400f) }),
                durationMillis = 400
            )
        }
        val workspace = colorAt(EditScreenTags.CANVAS, 0.9f, 0.5f)
        assertColor(Color.RED, colorAt(EditScreenTags.CANVAS, 0.08f, 0.6f))
        assertColor(Color.BLUE, colorAt(EditScreenTags.CANVAS, 0.42f, 0.6f))

        composeRule.onNodeWithTag(EditScreenTags.panelToggle(MainPanel.LAYERS)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.VIEW_FLIP_HORIZONTAL).performClick().assertIsOn()
        composeRule.onNodeWithTag(EditScreenTags.panelToggle(MainPanel.LAYERS)).performClick()

        // 사진은 왼쪽 반에 그대로 남고 좌우만 뒤집힌다(오른쪽은 여전히 빈 작업 공간)
        assertColor(Color.BLUE, colorAt(EditScreenTags.CANVAS, 0.08f, 0.6f))
        assertColor(Color.RED, colorAt(EditScreenTags.CANVAS, 0.42f, 0.6f))
        assertColor(workspace, colorAt(EditScreenTags.CANVAS, 0.9f, 0.5f))
    }

    private companion object {
        const val TAG = "viewport"
    }
}
