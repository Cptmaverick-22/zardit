package io.github.cptmaverick_22.zardit.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Crisp vector platform logos rendered directly with Compose Canvas
 * (YouTube, Instagram, Facebook, X, Threads).
 */
@Composable
internal fun PlatformLogo(
    platform: String,
    modifier: Modifier = Modifier.size(34.dp),
) {
    when (platform) {
        "YouTube" -> YouTubeLogo(modifier)
        "Instagram" -> InstagramLogo(modifier)
        "Facebook" -> FacebookLogo(modifier)
        "X" -> XLogo(modifier)
        "Threads" -> ThreadsLogo(modifier)
        else -> XLogo(modifier)
    }
}

@Composable
private fun YouTubeLogo(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padY = h * 0.14f
        drawRoundRect(
            color = Color(0xFFFF0033),
            topLeft = Offset(0f, padY),
            size = Size(w, h - padY * 2f),
            cornerRadius = CornerRadius(w * 0.24f, w * 0.24f),
        )
        val tri = Path().apply {
            moveTo(w * 0.41f, h * 0.34f)
            lineTo(w * 0.68f, h * 0.50f)
            lineTo(w * 0.41f, h * 0.66f)
            close()
        }
        drawPath(tri, color = Color.White)
    }
}

@Composable
private fun InstagramLogo(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val grad = Brush.linearGradient(
            colors = listOf(
                Color(0xFFF58529),
                Color(0xFFDD2A7B),
                Color(0xFF8134AF),
                Color(0xFF515BD4),
            ),
            start = Offset(0f, h),
            end = Offset(w, 0f),
        )
        drawRoundRect(
            brush = grad,
            size = size,
            cornerRadius = CornerRadius(w * 0.28f, w * 0.28f),
        )
        val stroke = w * 0.085f
        val inset = w * 0.21f
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(inset, inset),
            size = Size(w - inset * 2f, h - inset * 2f),
            cornerRadius = CornerRadius(w * 0.17f, w * 0.17f),
            style = Stroke(width = stroke),
        )
        drawCircle(
            color = Color.White,
            radius = w * 0.145f,
            center = Offset(w / 2f, h / 2f),
            style = Stroke(width = stroke),
        )
        drawCircle(
            color = Color.White,
            radius = w * 0.048f,
            center = Offset(w * 0.675f, h * 0.325f),
        )
    }
}

@Composable
private fun FacebookLogo(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawCircle(
            color = Color(0xFF1877F2),
            radius = minOf(w, h) / 2f,
        )
        val fPath = Path().apply {
            moveTo(w * 0.56f, h * 0.96f)
            lineTo(w * 0.56f, h * 0.58f)
            lineTo(w * 0.685f, h * 0.58f)
            lineTo(w * 0.71f, h * 0.45f)
            lineTo(w * 0.56f, h * 0.45f)
            lineTo(w * 0.56f, h * 0.365f)
            quadraticTo(w * 0.56f, h * 0.30f, w * 0.64f, h * 0.30f)
            lineTo(w * 0.72f, h * 0.30f)
            lineTo(w * 0.72f, h * 0.19f)
            quadraticTo(w * 0.66f, h * 0.18f, w * 0.59f, h * 0.18f)
            quadraticTo(w * 0.435f, h * 0.18f, w * 0.435f, h * 0.34f)
            lineTo(w * 0.435f, h * 0.45f)
            lineTo(w * 0.325f, h * 0.45f)
            lineTo(w * 0.325f, h * 0.58f)
            lineTo(w * 0.435f, h * 0.58f)
            lineTo(w * 0.435f, h * 0.96f)
            close()
        }
        drawPath(fPath, color = Color.White)
    }
}

@Composable
private fun XLogo(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = Color(0xFF101319),
            size = size,
            cornerRadius = CornerRadius(w * 0.28f, w * 0.28f),
        )
        drawRoundRect(
            color = Color(0xFF2D3342),
            size = size,
            cornerRadius = CornerRadius(w * 0.28f, w * 0.28f),
            style = Stroke(width = w * 0.03f),
        )
        // Left slanted ribbon + cross stroke (classic X mark)
        val p1 = Path().apply {
            moveTo(w * 0.26f, h * 0.25f)
            lineTo(w * 0.38f, h * 0.25f)
            lineTo(w * 0.74f, h * 0.75f)
            lineTo(w * 0.62f, h * 0.75f)
            close()
        }
        drawPath(p1, color = Color.White)
        drawLine(
            color = Color.White,
            start = Offset(w * 0.71f, h * 0.26f),
            end = Offset(w * 0.28f, h * 0.74f),
            strokeWidth = w * 0.085f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ThreadsLogo(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawCircle(
            color = Color(0xFF101319),
            radius = minOf(w, h) / 2f,
        )
        drawCircle(
            color = Color(0xFF2D3342),
            radius = minOf(w, h) / 2f,
            style = Stroke(width = w * 0.03f),
        )
        val strokeW = w * 0.078f
        // Outer "@"-like open spiral and inner loop representing Threads
        drawArc(
            color = Color.White,
            startAngle = -35f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(w * 0.23f, h * 0.21f),
            size = Size(w * 0.54f, h * 0.58f),
            style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(
            color = Color.White,
            radius = w * 0.135f,
            center = Offset(w * 0.49f, h * 0.51f),
            style = Stroke(width = strokeW),
        )
        drawLine(
            color = Color.White,
            start = Offset(w * 0.625f, h * 0.43f),
            end = Offset(w * 0.625f, h * 0.60f),
            strokeWidth = strokeW,
            cap = StrokeCap.Round,
        )
    }
}
