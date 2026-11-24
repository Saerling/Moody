// MoodyRecord.kt
// moody's own mark, drawn instead of a picture: a black record with fine grooves, a white label with the three
// accents (blue, red, green) as arcs, and a slim tonearm resting on it. the welcome page and the tv greeting spin it.

package com.saerling.moody.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.saerling.moody.ui.theme.Moody
import kotlin.math.min

/** The record, square, turned by [degrees] (read while drawing, so spinning it never recomposes). */
@Composable
fun MoodyRecord(degrees: () -> Float, modifier: Modifier = Modifier, arm: Boolean = true) {
    Canvas(modifier) { drawMoodyRecord(degrees(), arm) }
}

fun DrawScope.drawMoodyRecord(degrees: Float, arm: Boolean = true) {
    val side = min(size.width, size.height)
    val r = side / 2f * if (arm) 0.86f else 1f
    val c = Offset(r + (side / 2f - r) * 0.2f, size.height / 2f)

    // a soft blue light under the disc so it lifts off the black page
    drawCircle(
        Brush.radialGradient(listOf(Moody.BlueShadow.copy(alpha = 0.35f), Color.Transparent), c, r * 1.25f),
        radius = r * 1.25f,
        center = c,
    )
    drawCircle(Color(0xFF050505), r, c)

    rotate(degrees, c) {
        // grooves: thin rings with a little shine that turns with the record
        var g = r * 0.97f
        var i = 0
        while (g > r * 0.42f) {
            drawCircle(Color.White.copy(alpha = if (i % 5 == 0) 0.07f else 0.035f), g, c, style = Stroke(side * 0.0016f))
            g -= r * 0.018f
            i++
        }
        listOf(-35f, 145f).forEach { start ->
            drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent), c),
                startAngle = start, sweepAngle = 40f, useCenter = true,
                topLeft = c - Offset(r, r), size = Size(r * 2, r * 2),
            )
        }

        // the label: white, with the three accents as arcs
        val label = r * 0.36f
        drawCircle(Moody.Ink, label, c)
        val ring = label * 0.72f
        val stroke = Stroke(label * 0.16f, cap = StrokeCap.Round)
        listOf(Moody.Blue to -80f, Moody.Red to 40f, Moody.Green to 160f).forEach { (color, start) ->
            drawArc(color, start, 95f, false, c - Offset(ring, ring), Size(ring * 2, ring * 2), style = stroke)
        }
        drawCircle(Moody.Night, label * 0.12f, c)
    }

    if (arm) {
        // the tonearm, from its pivot top right down onto the outer grooves
        val pivot = Offset(c.x + r * 1.02f, c.y - r * 0.92f)
        val head = Offset(c.x + r * 0.55f, c.y + r * 0.42f)
        drawCircle(Moody.Raised, side * 0.045f, pivot)
        drawCircle(Moody.Ink3, side * 0.018f, pivot)
        drawLine(Moody.Ink2, pivot, head, strokeWidth = side * 0.012f, cap = StrokeCap.Round)
        drawLine(Moody.Ink, head, head + Offset(-side * 0.03f, side * 0.035f), strokeWidth = side * 0.03f, cap = StrokeCap.Round)
    }
}
