package app.hdri.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import app.hdri.capture.CaptureUi
import kotlin.math.*

/** The production capture HUD also renders without a camera for deterministic UI verification. */
@Composable
fun CaptureOverlay(
    state: CaptureUi,
    leave: () -> Unit,
    captureNow: () -> Unit,
    resumeHere: () -> Unit = {},
    reference: (@Composable () -> Unit)? = null,
) {
    val guide = state.guide
    val guiding =
        state.ready && !state.busy && !state.needsAnchor && state.error == null && guide != null
    val aligned = guiding && state.aimLocked && !state.positionBlocked
    val color = if (state.positionBlocked) Amber else Lime
    val pulse by
        rememberInfiniteTransition(label = "Guide glow")
            .animateFloat(
                .55f,
                1f,
                infiniteRepeatable(tween(1100), RepeatMode.Reverse),
                label = "Breathing glow",
            )
    val dwell by animateFloatAsState(state.dwell, tween(120), label = "Auto capture ring")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelHeight = maxHeight * if (state.needsAnchor) .65f else .43f
        Canvas(
            Modifier.fillMaxSize().semantics {
                contentDescription =
                    if (guiding)
                        "Capture guidance: ${if (state.positionBlocked) guide!!.positionInstruction else guide!!.instruction}"
                    else "Capture reticle"
            }
        ) {
            val center = Offset(size.width / 2, size.height / 2)
            if (guiding && !aligned) {
                val dx =
                    if (state.positionBlocked) guide!!.returnVector.x.toFloat() * 120
                    else guide!!.yaw
                val dy =
                    if (state.positionBlocked) -guide!!.returnVector.y.toFloat() * 120
                    else -guide!!.pitch
                val magnitude = hypot(dx, dy).coerceAtLeast(.01f)
                val direction = Offset(dx / magnitude, dy / magnitude)
                val edge =
                    center +
                        Offset(direction.x * size.width * .52f, direction.y * size.height * .5f)
                drawRect(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = .30f * pulse), Color.Transparent),
                        edge,
                        max(size.width, size.height) * .7f,
                    )
                )
                val distance = min(size.width * .32f, 118.dp.toPx())
                val point = center + direction * distance
                val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (magnitude > .1f)
                    rotate(angle, point) {
                        repeat(2) { i ->
                            val x = point.x + i * 13.dp.toPx()
                            val path =
                                Path().apply {
                                    moveTo(x - 7.dp.toPx(), point.y - 10.dp.toPx())
                                    lineTo(x + 3.dp.toPx(), point.y)
                                    lineTo(x - 7.dp.toPx(), point.y + 10.dp.toPx())
                                }
                            drawPath(
                                path,
                                Ink.copy(alpha = .8f),
                                style =
                                    Stroke(
                                        8.dp.toPx(),
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round,
                                    ),
                            )
                            drawPath(
                                path,
                                color.copy(alpha = if (i == 0) 1f else pulse),
                                style =
                                    Stroke(
                                        3.dp.toPx(),
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round,
                                    ),
                            )
                        }
                    }
            }
            state.markers.forEach { m ->
                val pos = Offset(m.x * size.width, m.y * size.height)
                val tint =
                    if (m.complete) Lime.copy(alpha = .35f)
                    else if (m.active) Lime else Color.White.copy(alpha = .65f)
                drawCircle(Ink.copy(alpha = .75f), 17.dp.toPx(), pos)
                drawCircle(tint, if (m.complete) 5.dp.toPx() else 8.dp.toPx(), pos)
                if (m.active) drawCircle(tint, 17.dp.toPx(), pos, style = Stroke(1.5.dp.toPx()))
            }
            if (aligned || state.busy)
                drawCircle(
                    Brush.radialGradient(
                        listOf(Lime.copy(alpha = .18f * pulse), Color.Transparent),
                        center,
                        75.dp.toPx(),
                    ),
                    75.dp.toPx(),
                    center,
                )
            val radius = 38.dp.toPx()
            drawCircle(Ink.copy(alpha = .7f), radius, center, style = Stroke(6.dp.toPx()))
            drawCircle(
                if (aligned) Lime else Color.White.copy(alpha = .8f),
                radius,
                center,
                style = Stroke(2.dp.toPx()),
            )
            repeat(4) { i ->
                val angle = i * PI / 2
                val v = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawLine(
                    Color.White.copy(alpha = .5f),
                    center + v * 46.dp.toPx(),
                    center + v * 52.dp.toPx(),
                    1.dp.toPx(),
                )
            }
            val sweep = if (state.busy) state.bracket else dwell
            if (sweep > 0)
                drawArc(
                    Lime,
                    -90f,
                    360f * sweep,
                    false,
                    center - Offset(45.dp.toPx(), 45.dp.toPx()),
                    Size(90.dp.toPx(), 90.dp.toPx()),
                    style = Stroke(5.dp.toPx(), cap = StrokeCap.Round),
                )
            drawCircle(if (aligned) Lime else Color.White, 2.dp.toPx(), center)
        }

        Column(
            Modifier.align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Ink.copy(alpha = .94f), Color.Transparent))
                )
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(leave) { Icon(Icons.Outlined.Close, "Save and leave capture") }
                Column(Modifier.weight(1f)) {
                    Text("Capture sphere", fontWeight = FontWeight.SemiBold)
                    Text("AUTO CAPTURE", color = Lime, fontSize = 10.sp, letterSpacing = 1.5.sp)
                }
                Text(
                    "${state.captured} / ${state.total}",
                    color = Lime,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (guiding)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GuidePill(
                        if (abs(guide!!.yaw) <= 4.5) "↔ On target"
                        else "${if(guide.yaw>0) "→" else "←"} ${abs(guide.yaw).roundToInt()}°",
                        Modifier.weight(1f),
                    )
                    GuidePill(
                        if (abs(guide.pitch) <= 4.5) "↕ On target"
                        else "${if(guide.pitch>0) "↑" else "↓"} ${abs(guide.pitch).roundToInt()}°",
                        Modifier.weight(1f),
                    )
                    LevelAssist(guide.roll, guide.canLevel)
                }
            if (!state.ready && state.error == null)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        }

        Column(
            Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
                .fillMaxWidth()
                .heightIn(max = panelHeight)
                .background(Ink.copy(alpha = .95f), RoundedCornerShape(24.dp))
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            reference?.invoke()
            if (state.notice != null) Text(state.notice, color = Lime, fontSize = 12.sp)
            Text(
                state.message,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 25.sp,
            )
            Text(state.detail, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
            LinearProgressIndicator(
                progress = {
                    if (state.busy) state.bracket
                    else if (aligned) state.dwell
                    else state.captured.toFloat() / max(1, state.total)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = color,
            )
            when {
                state.busy ->
                    Text(
                        "${(state.bracket*100).roundToInt()}% of this bracket saved",
                        color = Lime,
                        fontSize = 12.sp,
                    )
                state.needsAnchor -> PrimaryAction("Resume here", resumeHere, enabled = state.ready)
                else -> {
                    Text(
                        if (aligned) "Auto shutter is settling · ${(state.dwell*100).roundToInt()}%"
                        else "Align a dot · the shutter fires for you",
                        color = Lime,
                        fontSize = 12.sp,
                    )
                    OutlinedButton(
                        captureNow,
                        enabled = state.manualReady && state.error == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Capture now", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GuidePill(text: String, modifier: Modifier) {
    Text(
        text,
        modifier
            .background(Ink.copy(alpha = .8f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        color = Lime,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}

@Composable
private fun LevelAssist(roll: Float, available: Boolean) {
    val level = abs(roll) < 5
    val tint = if (!available || level) Lime else Color.White
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier.width(88.dp)
                .background(Ink.copy(alpha = .8f), RoundedCornerShape(14.dp))
                .padding(4.dp)
                .semantics {
                    contentDescription =
                        if (!available) "Roll is free when looking up or down"
                        else if (level) "Phone is level"
                        else
                            "Rotate phone ${if(roll>0) "clockwise" else "counterclockwise"} ${abs(roll).roundToInt()} degrees; leveling is optional"
                },
    ) {
        Canvas(Modifier.size(56.dp, 28.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val phone = Size(14.dp.toPx(), 23.dp.toPx())
            drawRoundRect(
                Lime.copy(alpha = .35f),
                c - Offset(phone.width / 2, phone.height / 2),
                phone,
                CornerRadius(3.dp.toPx()),
                style = Stroke(1.dp.toPx()),
            )
            rotate(if (available) -roll else 0f, c) {
                drawRoundRect(
                    tint,
                    c - Offset(phone.width / 2, phone.height / 2),
                    phone,
                    CornerRadius(3.dp.toPx()),
                    style = Stroke(1.8.dp.toPx()),
                )
            }
        }
        Text(
            if (!available) "Roll free"
            else if (level) "Level" else "${if(roll>0) "↻" else "↺"} ${abs(roll).roundToInt()}°",
            color = tint,
            fontSize = 10.sp,
        )
    }
}
