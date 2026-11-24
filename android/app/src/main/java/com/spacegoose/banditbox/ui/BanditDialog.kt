package com.spacegoose.banditbox.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * A [Dialog] whose content opens with a short zoom, fade and lift, so folders, menus and
 * prompts feel like they rise out of the home screen instead of popping in. Every dialog in
 * the launcher goes through this. The animation only touches a graphics layer, so it costs
 * no layout work and no extra memory.
 *
 * It also ignores an OK press that was already held when the dialog opened (see
 * [HeldSelectGuard]), so the long press that opens a menu can't also pick its first button.
 */
@Composable
fun BanditDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        val guard = remember { HeldSelectGuard() }
        Box(
            Modifier.onPreviewKeyEvent { event ->
                guard.shouldConsume(
                    isSelectKey = event.key in SELECT_KEYS,
                    isDown = event.type == KeyEventType.KeyDown,
                    repeatCount = event.nativeKeyEvent.repeatCount,
                )
            },
        ) {
            OpenAnimation { content() }
        }
    }
}

private val SELECT_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

@Composable
private fun OpenAnimation(content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    val lift = with(LocalDensity.current) { 24.dp.toPx() }
    LaunchedEffect(Unit) {
        launch { fade.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow)) }
        progress.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow))
    }
    Box(
        Modifier.graphicsLayer {
            val p = progress.value
            val scale = 0.9f + 0.1f * p
            scaleX = scale
            scaleY = scale
            translationY = (1f - p) * lift
            alpha = fade.value
        },
    ) {
        content()
    }
}
