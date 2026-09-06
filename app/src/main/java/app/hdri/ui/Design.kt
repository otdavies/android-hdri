package app.hdri.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlin.math.*

val Ink = Color(0xFF151719)
val Panel = Color(0xFF232629)
val Lime = Color(0xFFF2CB92)
val Muted = Color(0xFFB5B9BD)
val Amber = Color(0xFFFFCA8A)

@Composable
fun SphereTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = Lime,
                onPrimary = Ink,
                secondary = Lime,
                secondaryContainer = Color(0xFF40382D),
                onSecondaryContainer = Lime,
                primaryContainer = Color(0xFF40382D),
                onPrimaryContainer = Lime,
                background = Ink,
                surface = Ink,
                surfaceVariant = Panel,
                onSurface = Color(0xFFF3F1ED),
                onSurfaceVariant = Muted,
                outline = Color(0xFF4B4F53),
                error = Color(0xFFFFB4AB),
            ),
        content = {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                content = content,
            )
        },
    )
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        color = Lime,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.8.sp,
    )
}

@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick,
        modifier.fillMaxWidth().heightIn(min = 56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SphereArt(modifier: Modifier = Modifier, progress: Float = 0f) {
    Canvas(modifier) {
        val r = min(size.width, size.height) * .4f
        val center = Offset(size.width * .5f, size.height * .48f)
        drawCircle(
            Brush.radialGradient(
                listOf(Lime.copy(alpha = .09f), Color.Transparent),
                center,
                r * 1.5f,
            ),
            r * 1.5f,
            center,
        )
        fun p(lat: Double, lon: Double): Pair<Offset, Double> {
            val x = cos(lat) * sin(lon)
            val y = sin(lat)
            val z = cos(lat) * cos(lon)
            val tilt = .28
            val ry = y * cos(tilt) - z * sin(tilt)
            val rz = y * sin(tilt) + z * cos(tilt)
            return Offset(center.x + (x * r).toFloat(), center.y - (ry * r).toFloat()) to rz
        }
        for (lat in -60..60 step 30) {
            for (lon in 0 until 360 step 3) {
                val a = p(Math.toRadians(lat.toDouble()), Math.toRadians(lon.toDouble()))
                val b = p(Math.toRadians(lat.toDouble()), Math.toRadians((lon + 3).toDouble()))
                drawLine(Lime.copy(alpha = if (a.second > 0) .5f else .1f), a.first, b.first, 1f)
            }
        }
        for (lon in 0 until 360 step 30) {
            for (lat in -90 until 90 step 3) {
                val a = p(Math.toRadians(lat.toDouble()), Math.toRadians(lon.toDouble()))
                val b = p(Math.toRadians((lat + 3).toDouble()), Math.toRadians(lon.toDouble()))
                drawLine(Lime.copy(alpha = if (a.second > 0) .5f else .1f), a.first, b.first, 1f)
            }
        }
        drawCircle(Lime.copy(alpha = .6f), r, center, style = Stroke(1.5f))
        val point = p(.3, .7).first
        drawCircle(Ink, 9f, point)
        drawCircle(Lime, 5f, point)
        drawCircle(Lime.copy(alpha = .35f), 14f, point, style = Stroke(1.5f))
        if (progress > 0)
            drawArc(
                Lime,
                -90f,
                360f * progress,
                false,
                Offset(center.x - r - 9, center.y - r - 9),
                androidx.compose.ui.geometry.Size((r + 9) * 2, (r + 9) * 2),
                style = Stroke(3f),
            )
    }
}

@Composable
fun InfoCard(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(body, color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
    }
}
