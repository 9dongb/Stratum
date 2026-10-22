package com.kiln.stratum.ui.layout

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme

/**
 * 다른 앱에서 끌어 온 사진을 받는 자리(사용자 피드백 12차 4: "사진 끌어다 놓아 열기"). 사진(image/…)을 끄는 동안만 받으며,
 * 받을 자리 위에 있는지를 [onHover]로 알린다. 놓으면 첫 사진 하나의 주소와 받은 읽기 권한을 놓을 일을 [onDrop]에 넘긴다.
 * 받은 쪽은 바로 앱 임시 폴더에 복사한 뒤 권한을 놓는다(권한은 화면이 다시 만들어지면 사라진다). 새 권한·라이브러리는 없다.
 * 이 수식어를 단 자리 전체가 받는 자리다. [enabled]가 false인 동안(창·작업 모드가 떠 있을 때 등)은 받지 않는다(받을 자리 표시도 없다).
 */
@Composable
fun rememberPhotoDropTarget(enabled: Boolean = true, onHover: (Boolean) -> Unit, onDrop: (Uri, release: () -> Unit) -> Unit): Modifier {
    val activity = LocalContext.current.findActivity()
    val currentEnabled by rememberUpdatedState(enabled)
    val currentHover by rememberUpdatedState(onHover)
    val currentDrop by rememberUpdatedState(onDrop)
    val target = remember(activity) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = currentHover(true)

            override fun onExited(event: DragAndDropEvent) = currentHover(false)

            override fun onEnded(event: DragAndDropEvent) = currentHover(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                currentHover(false)
                // 끄는 사이 창·작업 모드가 떴으면 받지 않는다
                if (!currentEnabled) return false
                val dragEvent = event.toAndroidDragEvent()
                // 다른 앱의 주소를 읽으려면 놓는 바로 그때 권한을 받아야 한다
                val permissions = activity?.requestDragAndDropPermissions(dragEvent)
                val uri = firstPhoto(dragEvent.clipData) { activity?.typeOf(it) }
                if (uri == null) {
                    permissions?.release()
                    return false
                }
                currentDrop(uri) { permissions?.release() }
                return true
            }
        }
    }
    return Modifier.dragAndDropTarget(shouldStartDragAndDrop = { event -> currentEnabled && isPhotoDrag(event.mimeTypes()) }, target = target)
}

/**
 * 사진을 끌어 받을 자리 위에 있을 때 보이는 표시: 옅은 막과 테두리, 가운데 안내 글([text]). 터치는 받지 않는다.
 * 받는 자리(편집 화면·홈) 맨 위에 화면 크기로 둔다.
 */
@Composable
fun PhotoDropHighlight(visible: Boolean, text: String, modifier: Modifier = Modifier) {
    if (!visible) return
    val colors = StratumTheme.colors
    Box(
        modifier
            .fillMaxSize()
            .background(colors.scrim)
            .border(StratumDimens.DropOutline, colors.accent),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            modifier = Modifier
                .background(colors.inverseBackground, StratumShapes.Card)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            color = colors.inverseText,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** 끄는 것이 사진인지(사진 형식이 하나라도 있으면) */
internal fun isPhotoDrag(mimeTypes: Set<String>): Boolean = mimeTypes.any { it.startsWith("image/") }

/**
 * 끌어 온 것 가운데 첫 사진의 주소(사진 여러 장이면 첫 장만 받는다). 형식([typeOf])이 사진인 첫 주소이고,
 * 형식을 아는 사진이 없으면 첫 주소다(여러 형식을 함께 끌어 와도 사진이 아닌 것을 고르지 않게).
 */
internal fun firstPhoto(clip: ClipData?, typeOf: (Uri) -> String? = { null }): Uri? {
    if (clip == null) return null
    val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    return uris.firstOrNull { typeOf(it)?.startsWith("image/") == true } ?: uris.firstOrNull()
}

/** [uri]의 형식(MIME). 공급자가 알려 주지 않거나 물을 수 없으면 null이다. */
private fun Context.typeOf(uri: Uri): String? =
    try {
        contentResolver.getType(uri)
    } catch (e: RuntimeException) {
        null
    }

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
