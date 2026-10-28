package com.noahwallet.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Prototype SVG icons, drawn in their 24-unit viewBox. Callers add any accessibility label. */
@Composable
private fun Icon24(size: Dp, strokeWidth: Float, draw: DrawScope.(Stroke) -> Unit) {
    Canvas(Modifier.size(size)) {
        scale(this.size.width / 24f, Offset.Zero) { draw(Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    }
}

@Composable
internal fun GlobeIcon(color: Color, size: Dp = 18.dp) = Icon24(size, 1.7f) { stroke ->
    drawCircle(color, 9f, Offset(12f, 12f), style = stroke)
    drawLine(color, Offset(3f, 12f), Offset(21f, 12f), 1.7f)
    // The prototype's meridians: radius-14 arcs through the poles (12,3) and (12,21).
    val offset = kotlin.math.sqrt(14f * 14f - 9f * 9f)
    val sweep = Math.toDegrees(kotlin.math.asin(9.0 / 14.0)).toFloat()
    drawArc(color, -sweep, 2 * sweep, false, Offset(12f - offset - 14f, -2f), Size(28f, 28f), style = stroke)
    drawArc(color, 180f - sweep, 2 * sweep, false, Offset(12f + offset - 14f, -2f), Size(28f, 28f), style = stroke)
}

@Composable
internal fun CheckIcon(color: Color, size: Dp = 20.dp, progress: Float = 1f) = Icon24(size, 2.4f) { stroke ->
    drawPath(trimmed(Path().apply { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(19f, 7f) }, progress), color, style = stroke)
}

/** The email field's check: `M5 12.5l4.5 4.5L19 7.5`, drawn as it becomes valid. */
@Composable
internal fun EmailCheckIcon(color: Color, progress: Float, size: Dp = 24.dp) = Icon24(size, 2.6f) { stroke ->
    drawPath(trimmed(Path().apply { moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7.5f) }, progress), color, style = stroke)
}

@Composable
internal fun BackIcon(color: Color, size: Dp = 24.dp) = Icon24(size, 2f) { stroke ->
    drawPath(Path().apply { moveTo(15f, 18f); lineTo(9f, 12f); lineTo(15f, 6f) }, color, style = stroke)
}

@Composable
internal fun PasteIcon(color: Color, size: Dp = 18.dp) = Icon24(size, 1.8f) { stroke ->
    drawRoundRect(color, Offset(8f, 2f), Size(8f, 4f), androidx.compose.ui.geometry.CornerRadius(1f), style = stroke)
    drawPath(Path().apply {
        moveTo(16f, 4f); lineTo(18f, 4f); quadraticTo(20f, 4f, 20f, 6f); lineTo(20f, 20f); quadraticTo(20f, 22f, 18f, 22f)
        lineTo(6f, 22f); quadraticTo(4f, 22f, 4f, 20f); lineTo(4f, 6f); quadraticTo(4f, 4f, 6f, 4f); lineTo(8f, 4f)
    }, color, style = stroke)
}

/** The first [progress] of [path], like an SVG stroke-dashoffset reveal. */
internal fun trimmed(path: Path, progress: Float): Path {
    if (progress >= 1f) return path
    val measure = PathMeasure().apply { setPath(path, false) }
    return Path().also { measure.getSegment(0f, measure.length * progress.coerceIn(0f, 1f), it, true) }
}
