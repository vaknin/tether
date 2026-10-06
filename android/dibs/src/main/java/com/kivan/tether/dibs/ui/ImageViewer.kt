package com.kivan.tether.dibs.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.ui.theme.Palette

/** The most a picture zooms in, and how far a double tap goes. */
private const val MAX_ZOOM = 6f
private const val TAP_ZOOM = 2.5f

/**
 * A picture full screen (a tap on a thumbnail): pinch to zoom, drag while zoomed, double tap to
 * zoom in or back out; back or ✕ closes it. [full] is the sharpest copy there is, null while it
 * loads (then [thumb] stands in).
 */
@Composable
internal fun ImageViewer(thumb: ImageBitmap, full: ImageBitmap?, name: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var box by remember { mutableStateOf(IntSize.Zero) }
        // Keeps the zoomed picture over the screen: no dragging it off past its edges.
        fun clamp(o: Offset, s: Float): Offset {
            val mx = box.width * (s - 1) / 2
            val my = box.height * (s - 1) / 2
            return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black).onSizeChanged { box = it }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { at ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            // Zoom in on the tapped point.
                            val center = Offset(box.width / 2f, box.height / 2f)
                            scale = TAP_ZOOM
                            offset = clamp((center - at) * (TAP_ZOOM - 1), TAP_ZOOM)
                        }
                    })
                }
                .pointerInput(Unit) {
                    // Two fingers zoom; one drags only while zoomed, so a plain swipe does nothing.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (fingers >= 2 || scale > 1f) {
                                val s = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                val c = event.calculateCentroid(useCurrent = true)
                                val center = Offset(box.width / 2f, box.height / 2f)
                                // Zoom about the fingers, not the middle.
                                val o = if (c == Offset.Unspecified) offset + pan else (offset + pan) * (s / scale) + (c - center) * (1 - s / scale)
                                scale = s
                                offset = if (s == 1f) Offset.Zero else clamp(o, s)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            Image(
                full ?: thumb,
                name,
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
                contentScale = ContentScale.Fit,
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
            ) { Icon(painterResource(R.drawable.lucide_x), "Close", Modifier.size(22.dp), tint = Palette.Text) }
        }
    }
}
