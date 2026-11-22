// a soft squash while a finger is down and a springy release, so every tap visibly lands.
// inspired by @djemps2000's bouncy offline player. it follows AppMotion, so it stays still when animations are off.

package com.mochyy.mochymusic.ui.utils

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Scales the element down to [pressedScale] while [interactionSource] is pressed. Pass the same source to its clickable. */
fun Modifier.pressBounce(interactionSource: MutableInteractionSource, pressedScale: Float = 0.95f): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && AppMotion.enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressBounce",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** A fresh interaction source to pair [pressBounce] with a clickable. */
@androidx.compose.runtime.Composable
fun rememberBounceSource(): MutableInteractionSource = remember { MutableInteractionSource() }

/**
 * Same squash as [pressBounce] but it watches the finger itself (without consuming it), so it can wrap
 * rows and cards whose clickable lives somewhere else.
 */
fun Modifier.tapBounce(pressedScale: Float = 0.97f): Modifier = composed {
    var down by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (down && AppMotion.enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tapBounce",
    )
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down = true
                // let go on lift, or as soon as the finger starts scrolling instead of tapping
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == first.id } ?: break
                    if (!change.pressed) break
                    if ((change.position - first.position).getDistance() > viewConfiguration.touchSlop) break
                }
                down = false
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}
