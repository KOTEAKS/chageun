package com.letsgo.chageun.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.letsgo.chageun.SpeedPermission
import com.letsgo.chageun.core.GateBlock
import com.letsgo.chageun.core.GateState

enum class BtnStyle { PRIMARY, SECONDARY, GHOST }

@Composable
fun PillButton(label: String, style: BtnStyle, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val t = LocalType.current
    val (base, ink) = when (style) {
        BtnStyle.PRIMARY -> C.warm to C.onAccent
        BtnStyle.SECONDARY -> C.surfaceHigh to C.text
        BtnStyle.GHOST -> Color.Transparent to C.text
    }
    // Disabled buttons are drawn with dimmed colours rather than a translucent layer (the layer dropped the background on the emulator).
    val bg = if (enabled || style == BtnStyle.GHOST) base else C.surfaceHigh
    val fg = if (enabled) ink else C.text3
    Row(
        modifier
            .height(96.dp)
            .clip(RoundedCornerShape(48.dp))
            .background(bg)
            .then(if (style == BtnStyle.GHOST) Modifier.border(3.dp, C.line, RoundedCornerShape(48.dp)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(40.dp))
        Text(label, style = t.label.copy(color = fg))
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, color: Color = C.surface, border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(40.dp))
            .background(color)
            .then(if (border != null) Modifier.border(BorderStroke(3.dp, border), RoundedCornerShape(40.dp)) else Modifier)
            .padding(horizontal = 44.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

/** Gate status chip. It states the condition, never instructs the driver to shift gear. */
@Composable
fun GateChip(gate: GateState, permission: SpeedPermission = SpeedPermission.GRANTED) {
    val (dot, bg, text) = when {
        permission == SpeedPermission.DENIED || permission == SpeedPermission.BLOCKED -> Triple(C.danger, C.dangerSoft, "속도 권한 필요")
        else -> gateLook(gate)
    }
    Chip(text, dot, bg)
}

private fun gateLook(gate: GateState): Triple<Color, Color, String> =
    when (gate) {
        GateState.Ready -> Triple(C.success, C.surfaceHigh, "P · 정차 중")
        GateState.Settling -> Triple(C.warm, C.warmSoft, "확인 중")
        is GateState.Blocked -> if (GateBlock.SIGNAL_UNAVAILABLE in gate.reasons) Triple(C.danger, C.dangerSoft, "차량 신호 확인 불가")
            else Triple(C.danger, C.dangerSoft, "지금은 시작할 수 없어요")
    }

@Composable
fun Chip(text: String, dot: Color, bg: Color) {
    Row(
        Modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).background(bg).padding(horizontal = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(dot))
        Text(text, style = LocalType.current.label)
    }
}

enum class StepPillState { DONE, CURRENT, TODO }

@Composable
fun StepPills(labels: List<String>, current: Int) {
    val t = LocalType.current
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        labels.forEachIndexed { i, label ->
            val state = when { i < current -> StepPillState.DONE; i == current -> StepPillState.CURRENT; else -> StepPillState.TODO }
            val shape = RoundedCornerShape(32.dp)
            Row(
                Modifier.height(64.dp).clip(shape)
                    .background(when (state) { StepPillState.DONE -> C.surface; StepPillState.CURRENT -> C.warmSoft; else -> Color.Transparent })
                    .border(if (state == StepPillState.CURRENT) 3.dp else 2.dp, if (state == StepPillState.CURRENT) C.warm else if (state == StepPillState.TODO) C.line else Color.Transparent, shape)
                    .padding(start = 12.dp, end = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(when (state) { StepPillState.DONE -> C.success; StepPillState.CURRENT -> C.warm; else -> C.surfaceHigh }),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state == StepPillState.DONE) Icon(Icons.Filled.Check, null, tint = C.base, modifier = Modifier.size(26.dp))
                    else Text("${i + 1}", style = t.label.copy(color = if (state == StepPillState.CURRENT) C.onAccent else C.text3))
                }
                Text(label, style = t.label.copy(color = when (state) { StepPillState.DONE -> C.text2; StepPillState.CURRENT -> C.text; else -> C.text3 }))
            }
        }
    }
}

@Composable
fun VoiceCaption(speaker: String, line: String, modifier: Modifier = Modifier) {
    val t = LocalType.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(C.surface).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Box(Modifier.size(80.dp).clip(CircleShape).background(C.warmSoft), contentAlignment = Alignment.Center) {
            Text("🔊", style = t.headline)
        }
        Column {
            Text(speaker, style = t.caption.copy(color = C.text2))
            Text(line, style = t.headline)
        }
    }
}

@Composable
fun DeviceTag(label: String) {
    Row(
        Modifier.clip(RoundedCornerShape(28.dp)).background(C.surfaceHigh).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("🚗", style = LocalType.current.label)
        Text(label, style = LocalType.current.label)
    }
}

enum class RowState { DONE, SEEN, UNSUPPORTED, WARN, TODO }

@Composable
fun StateRow(state: RowState, label: String) {
    val t = LocalType.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        val (bg, icon, tint) = when (state) {
            RowState.DONE -> Triple(C.success, Icons.Filled.Check, C.base)
            RowState.SEEN -> Triple(C.surfaceHigh, Icons.Filled.Info, C.text2)
            RowState.WARN -> Triple(C.warmSoft, Icons.Filled.Warning, C.warm)
            RowState.UNSUPPORTED, RowState.TODO -> Triple(Color.Transparent, null, C.text3)
        }
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(bg)
                .then(if (bg == Color.Transparent) Modifier.border(3.dp, C.line, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
            else if (state == RowState.UNSUPPORTED) Text("–", style = t.label.copy(color = C.text3))
        }
        Text(label, style = t.body.copy(color = when (state) { RowState.DONE, RowState.WARN -> C.text; RowState.SEEN -> C.text2; else -> C.text3 }))
    }
}

@Composable
fun Badge(symbol: String, bg: Color, size: Int = 112) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(symbol, style = LocalType.current.title, textAlign = TextAlign.Center)
    }
}

@Composable
fun Gap(w: Int = 0, h: Int = 0) = Spacer(Modifier.width(w.dp).height(h.dp))
