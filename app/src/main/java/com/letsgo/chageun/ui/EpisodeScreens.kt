package com.letsgo.chageun.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.letsgo.chageun.AppViewModel
import com.letsgo.chageun.core.Leftovers
import com.letsgo.chageun.UiState
import com.letsgo.chageun.core.Difficulty
import com.letsgo.chageun.core.Episode1
import com.letsgo.chageun.core.EpisodeScript
import com.letsgo.chageun.core.EpisodeStage
import com.letsgo.chageun.core.EpisodeView
import com.letsgo.chageun.core.HelpOffer
import com.letsgo.chageun.core.TurnDir
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun EpisodeScreen(s: UiState, vm: AppViewModel) {
    val t = LocalType.current
    val v = s.episode ?: return
    val stage = v.stage ?: return
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        TopBar(
            left = { Column { Text("${EpisodeScript.CHAPTER} · 장면 ${stage.scene}", style = t.label.copy(color = C.cyan)); Text(EpisodeScript.TITLE, style = t.title) } },
            right = {
                Chip("빛 단서 ${v.clues}", if (v.clues > 0) C.warm else C.text3, C.surfaceHigh)
                GateChip(s.gate, s.speedPermission)
                PillButton("나가기", BtnStyle.GHOST, { vm.exitPuzzle() }, icon = Icons.AutoMirrored.Filled.ExitToApp)
            },
        )
        // Pills for the current scene only; all eight stages would not fit on one line.
        StepPills(stage.sceneStages.map { it.label }, stage.sceneStages.indexOf(stage))
        Panel(Modifier.fillMaxWidth().weight(1f), color = C.night) {
            when (stage) {
                EpisodeStage.SIGN -> SignStage(v, s, vm)
                EpisodeStage.CODE -> CodeStage(v, vm)
                EpisodeStage.DIAL -> DialStage(v, s, vm)
                EpisodeStage.CENTER -> CenterStage(s, vm)
                EpisodeStage.BOILER -> BoilerStage(v, s, vm)
                EpisodeStage.TEMP_BACK -> TempBackStage(v, s, vm)
                EpisodeStage.LIGHTS -> LightsStage(v, s, vm)
                EpisodeStage.LIGHTS_OFF -> LightsOffStage(v, s, vm)
            }
        }
        // The stage panels are full, so a hint takes the caption line instead of being clipped below the keypad or dial.
        val hint = if (s.hintLevel > 0) EpisodeScript.hints(v).getOrNull(s.hintLevel - 1) else null
        when {
            hint != null -> VoiceCaption("힌트 ${s.hintLevel}", hint)
            s.restNotice -> VoiceCaption("쉬어 가기", AppViewModel.REST_LINE)
            s.helpOffer -> VoiceCaption("도움", HelpOffer.LINE)
            else -> VoiceCaption("안내", AppViewModel.episodeLine(v, s.settings))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                PillButton("다시 말해 줘", BtnStyle.SECONDARY, vm::repeatVoice, icon = Icons.Filled.Refresh)
                PillButton("힌트", BtnStyle.SECONDARY, vm::hint)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                when (stage) {
                    EpisodeStage.SIGN, EpisodeStage.DIAL, EpisodeStage.BOILER, EpisodeStage.LIGHTS ->
                        PillButton("화면으로 하기", BtnStyle.SECONDARY, vm::episodeUseScreen, enabled = !v.onScreen)
                    else -> Unit
                }
                PillButton("건너뛰기", BtnStyle.GHOST, vm::episodeSkip)
            }
        }
    }
}

// ---------------------------------------------------------------- P1 sign
/**
 * Layout note (measured 2026-11-01): turning a signal on makes the system show a side-camera view over the top-left of the
 * app window (about 750 × 420 px). The sign and the progress therefore sit on the right, and the spoken caption at the
 * bottom repeats what to do, so nothing needed for the puzzle hides under that view.
 */
@Composable
private fun SignStage(v: EpisodeView, s: UiState, vm: AppViewModel) {
    val t = LocalType.current
    val onScreen = v.onScreen
    val lever = when (s.signals.turnSignal) { TurnDir.LEFT -> "왼쪽"; TurnDir.RIGHT -> "오른쪽"; TurnDir.OFF -> "꺼짐"; null -> "확인 중" }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (!onScreen) DeviceTag("차에서 조작 · 방향지시등" + EpisodeScript.role(v))
            Text(EpisodeScript.instruction(v), style = t.headline)
            if (!onScreen) Text(EpisodeScript.ROADSIDE_NOTE, style = t.body.copy(color = C.warm))
        }
        Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SignBoard(v, s.signBeat)
            Text(if (v.difficulty == Difficulty.EASY) "긴 막대 = 오른쪽 · 짧은 막대 = 왼쪽" else "긴 막대 = 오른쪽", style = t.headline.copy(color = C.text2))
            Text("지금 방향지시등: $lever · ${v.signProgress} / ${Episode1.SIGN_ANSWER.size}", style = t.body.copy(color = C.text2))
            when {
                v.signMistake -> Text("순서가 달라요. 처음부터 다시 해 볼까요?", style = t.headline.copy(color = C.warm))
                v.signProgress == 0 && !onScreen && s.signals.turnSignal != null && s.signals.turnSignal != TurnDir.OFF ->
                    Text("먼저 방향지시등을 꺼 주세요", style = t.headline.copy(color = C.warm))
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        PillButton("소리로 듣기", BtnStyle.SECONDARY, vm::playSignPattern, icon = Icons.Filled.PlayArrow, enabled = s.signBeat == null)
        if (onScreen) {
            PillButton("왼쪽", BtnStyle.SECONDARY, { vm.episodeScreenTurn(TurnDir.LEFT) }, icon = Icons.AutoMirrored.Filled.ArrowBack)
            PillButton("오른쪽", BtnStyle.SECONDARY, { vm.episodeScreenTurn(TurnDir.RIGHT) }, icon = Icons.AutoMirrored.Filled.ArrowForward)
        }
    }
}

/** The blinking sign. The highlight walks one bar per second at most (no flashing faster than once a second). */
@Composable
private fun SignBoard(v: EpisodeView, beat: Int?) {
    val t = LocalType.current
    val walk by produceState(0) { while (true) { delay(1_000); value = (value + 1) % (Episode1.SIGN_ANSWER.size + 1) } }
    // Normal difficulty reveals the bars one at a time: the ones already entered plus the next.
    val visible = if (v.difficulty == Difficulty.EASY) Episode1.SIGN_ANSWER.size else (v.signProgress + 1).coerceAtMost(Episode1.SIGN_ANSWER.size)
    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Episode1.SIGN_ANSWER.forEachIndexed { i, dir ->
            val shape = RoundedCornerShape(28.dp)
            if (i >= visible) {
                Box(Modifier.width(110.dp).height(56.dp).clip(shape).border(3.dp, C.line, shape), contentAlignment = Alignment.Center) {
                    Text("?", style = t.label.copy(color = C.text3))
                }
                return@forEachIndexed
            }
            val done = i < v.signProgress || v.awaitingFinalOff
            val lit = if (beat != null) beat == i else (!done && walk == i)
            Box(Modifier.width(if (dir == TurnDir.RIGHT) 240.dp else 110.dp).height(56.dp).clip(shape)
                .background(when { done -> C.success; lit -> C.warm; else -> C.surfaceHigh }))
        }
    }
}

// ---------------------------------------------------------------- P2 code
@Composable
private fun CodeStage(v: EpisodeView, vm: AppViewModel) {
    val t = LocalType.current
    var digits by remember { mutableStateOf(listOf<Int>()) }
    Text("화면에서 답하기 · 계기판 보기", style = t.label.copy(color = C.cyan))
    Text(EpisodeScript.instruction(v), style = t.title)
    Text(EpisodeScript.codeClue(v), style = t.headline)
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(2) { i ->
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(20.dp)).background(C.surfaceHigh)
                .border(3.dp, if (i == digits.size) C.cyan else C.line, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                Text(digits.getOrNull(i)?.toString() ?: "", style = t.title)
            }
        }
        if (v.codeWrong && digits.isEmpty()) Text("번호가 달라요. 다시 볼까요?", style = t.headline.copy(color = C.warm))
    }
    // One row of ten keys: two rows did not fit the panel with large text (review-safety H3).
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        (listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)).forEach { d ->
            Box(Modifier.size(width = 96.dp, height = 88.dp).clip(RoundedCornerShape(24.dp)).background(C.surfaceHigh)
                .clickable {
                    val next = digits + d
                    if (next.size == 2) { vm.episodeEnterCode(next[0], next[1]); digits = emptyList() } else digits = next
                }, contentAlignment = Alignment.Center) { Text("$d", style = t.title) }
        }
        PillButton("지우기", BtnStyle.GHOST, { digits = digits.dropLast(1) })
    }
    Text(if (v.codeFromCar) "주행거리는 금고 번호에만 쓰고 저장하지 않아요 · 에뮬레이터: Virtual Console 주행거리" else "이 차의 주행거리를 읽지 못해 기둥 번호를 써요",
        style = t.caption.copy(color = C.text3))
}

// ---------------------------------------------------------------- P3 dial
@Composable
private fun DialStage(v: EpisodeView, s: UiState, vm: AppViewModel) {
    val t = LocalType.current
    val angle = if (v.onScreen) v.screenAngle else s.signals.steeringDeg
    if (!v.onScreen) DeviceTag("차에서 조작 · 핸들" + EpisodeScript.role(v))
    Text(EpisodeScript.instruction(v), style = t.headline)
    Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        DialGauge(angle, v.dialTarget)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (v.difficulty == Difficulty.NORMAL) Text("눈금 한 칸 = 10°", style = t.label.copy(color = C.cyan))
            // The screen dial moves in notches, the wheel in degrees: easy mode names both.
            StateRow(if (v.dialStep > 0) RowState.DONE else RowState.TODO, "1단계 · 왼쪽 ${if (v.difficulty == Difficulty.EASY) "${v.code.first}칸(${v.code.first * 10}°)" else "${v.code.first}칸"}")
            StateRow(if (v.dialStep > 1) RowState.DONE else RowState.TODO, "2단계 · 오른쪽 ${if (v.difficulty == Difficulty.EASY) "${v.code.second}칸(${v.code.second * 10}°)" else "${v.code.second}칸"}")
            Text(angleText(angle), style = t.body.copy(color = C.text2))
            if (!v.onScreen) HoldBar(v.holdFraction)
        }
    }
    when {
        v.onScreen -> Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton("왼쪽 한 칸", BtnStyle.SECONDARY, { vm.episodeNudge(-1) }, icon = Icons.AutoMirrored.Filled.ArrowBack)
            PillButton("오른쪽 한 칸", BtnStyle.SECONDARY, { vm.episodeNudge(1) }, icon = Icons.AutoMirrored.Filled.ArrowForward)
            PillButton("이 위치로 맞추기", BtnStyle.PRIMARY, vm::episodeConfirmDial, icon = Icons.Filled.Check)
            if (v.dialWrong) Text("눈금이 달라요", style = t.headline.copy(color = C.warm))
        }
        s.signals.steeringDeg == null -> Text("핸들 각도를 읽지 못했어요. 화면으로 해도 돼요.", style = t.body.copy(color = C.warm))
        else -> Text("천천히 돌려 주세요 · 90°를 넘기지 않아요 · 건너뛰어도 이야기는 이어져요", style = t.body.copy(color = C.text2))
    }
}

@Composable
private fun CenterStage(s: UiState, vm: AppViewModel) {
    val t = LocalType.current
    DeviceTag("차에서 조작 · 핸들" + (s.episode?.let(EpisodeScript::role) ?: ""))
    Text("금고가 열렸어요! 핸들을 가운데로 돌려 주세요", style = t.title)
    Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        DialGauge(s.signals.steeringDeg, 0f)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(angleText(s.signals.steeringDeg), style = t.body.copy(color = C.text2))
            PillButton("가운데로 돌렸어요", BtnStyle.SECONDARY, vm::episodeConfirmCentered, icon = Icons.Filled.Check)
        }
    }
}

private fun angleText(angle: Float?): String = when {
    angle == null -> "지금 핸들: 확인 중"
    abs(angle) < 1f -> "지금 핸들: 가운데"
    angle < 0 -> "지금 핸들: 왼쪽 ${abs(angle).roundToInt()}°"
    else -> "지금 핸들: 오른쪽 ${angle.roundToInt()}°"
}

@Composable
private fun HoldBar(fraction: Float) {
    Box(Modifier.width(360.dp).height(20.dp).clip(RoundedCornerShape(10.dp)).background(C.surfaceHigh)) {
        Box(Modifier.width((360 * fraction).dp).height(20.dp).clip(RoundedCornerShape(10.dp)).background(C.cyan))
    }
}

/** Half dial from left 90° to right 90°. Ticks every 10°, the target window (±5°) in amber, the wheel as the needle. */
@Composable
private fun DialGauge(angle: Float?, target: Float?) {
    Canvas(Modifier.size(width = 520.dp, height = 290.dp)) {
        val r = size.width / 2f - 24f
        val c = Offset(size.width / 2f, size.height - 16f)
        fun at(deg: Float, radius: Float): Offset {
            val rad = Math.toRadians((deg - 90f).toDouble())
            return Offset(c.x + radius * cos(rad).toFloat(), c.y + radius * sin(rad).toFloat())
        }
        drawArc(C.line, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r), style = Stroke(6f))
        for (d in -90..90 step 10) {
            val long = d % 30 == 0
            drawLine(if (long) C.text2 else C.text3, at(d.toFloat(), r - if (long) 34f else 20f), at(d.toFloat(), r), strokeWidth = if (long) 5f else 3f)
        }
        if (target != null) {
            drawArc(C.warm, startAngle = target - 90f - Episode1.HOLD_TOLERANCE_DEG, sweepAngle = 2 * Episode1.HOLD_TOLERANCE_DEG, useCenter = false,
                topLeft = Offset(c.x - r + 14f, c.y - r + 14f), size = Size(2 * r - 28f, 2 * r - 28f), style = Stroke(26f))
        }
        if (angle != null) {
            val shown = angle.coerceIn(-120f, 120f)
            drawLine(C.cyan, c, at(shown, r - 46f), strokeWidth = 10f, cap = StrokeCap.Round)
            drawCircle(C.cyan, radius = 16f, center = c)
        }
    }
}

// ---------------------------------------------------------------- P4 boiler log
@Composable
private fun BoilerStage(v: EpisodeView, s: UiState, vm: AppViewModel) {
    val t = LocalType.current
    if (!v.onScreen) DeviceTag("차에서 조작 · 운전석 온도" + EpisodeScript.role(v))
    Text(EpisodeScript.instruction(v), style = t.headline)
    Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        BoilerStairs()
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(EpisodeScript.boilerLegend(v), style = t.headline.copy(color = C.text2))
            if (v.onScreen) {
                Text("${EpisodeScript.fmt(v.screenTemp)}°C", style = t.display)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton("−0.5", BtnStyle.SECONDARY, { vm.episodeNudgeTemp(-1) })
                    PillButton("+0.5", BtnStyle.SECONDARY, { vm.episodeNudgeTemp(1) })
                    PillButton("이 온도로 맞추기", BtnStyle.PRIMARY, vm::episodeConfirmTemp, icon = Icons.Filled.Check)
                }
                if (v.tempWrong) Text("기록계 숫자와 달라요", style = t.headline.copy(color = C.warm))
            } else {
                Text("지금 운전석 온도: ${s.signals.tempDriver?.let { EpisodeScript.fmt(it) + "°C" } ?: "확인 중"}", style = t.body.copy(color = C.text2))
                if (v.boilerAlreadyMet) Text("이미 그 온도예요. 한 번 바꿨다가 다시 맞춰 보세요.", style = t.headline.copy(color = C.warm))
            }
        }
    }
}

/** 21 stairs with a half landing in the middle: 21.5. */
@Composable
private fun BoilerStairs() {
    Canvas(Modifier.size(width = 560.dp, height = 300.dp)) {
        val steps = 21
        val stepW = size.width / (steps + 2.5f)
        val stepH = (size.height - 20f) / (steps + 0.5f)
        var x = 0f
        var y = size.height
        for (i in 1..steps) {
            if (i == 11) { // the half landing
                drawLine(C.warm, Offset(x, y), Offset(x, y - stepH / 2), strokeWidth = 6f)
                y -= stepH / 2
                drawLine(C.warm, Offset(x, y), Offset(x + stepW * 1.5f, y), strokeWidth = 6f)
                x += stepW * 1.5f
            }
            drawLine(C.text2, Offset(x, y), Offset(x, y - stepH), strokeWidth = 4f)
            y -= stepH
            drawLine(C.text2, Offset(x, y), Offset(x + stepW, y), strokeWidth = 4f)
            x += stepW
        }
    }
}

@Composable
private fun TempBackStage(v: EpisodeView, s: UiState, vm: AppViewModel) {
    val t = LocalType.current
    DeviceTag("차에서 조작 · 운전석 온도" + EpisodeScript.role(v))
    Text(EpisodeScript.instruction(v), style = t.title)
    Text("지금 운전석 온도: ${s.signals.tempDriver?.let { EpisodeScript.fmt(it) + "°C" } ?: "확인 중"}", style = t.body.copy(color = C.text2))
    Text("사용한 기능은 출발 전에 원래 상태인지 확인해 주세요.", style = t.caption.copy(color = C.text3))
    val back = v.originalTemp != null && s.signals.tempDriver?.let { kotlin.math.abs(it - v.originalTemp) < Episode1.TEMP_TOLERANCE } == true
    PillButton(if (back) "다음" else "이대로 둘게요", if (back) BtnStyle.PRIMARY else BtnStyle.SECONDARY, vm::episodeConfirmClosing, icon = Icons.AutoMirrored.Filled.ArrowForward)
}

// ---------------------------------------------------------------- P5 reverse lights
@Composable
private fun LightsStage(v: EpisodeView, s: UiState, vm: AppViewModel) {
    val t = LocalType.current
    val clues = Episode1.clueLights(v.difficulty)
    if (!v.onScreen) DeviceTag("차에서 조작 · 독서등·실내등" + EpisodeScript.role(v))
    Text(EpisodeScript.instruction(v), style = t.title)
    if (v.lightsUnreadable) Text("이 차에서는 단서 불빛 일부를 앱이 읽을 수 없어 화면으로 해요", style = t.body.copy(color = C.text2))
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        clues.forEachIndexed { i, spot ->
            val on = s.signals.lights[spot] == true
            Column(Modifier.clip(RoundedCornerShape(24.dp)).background(C.surfaceHigh).padding(horizontal = 28.dp, vertical = 18.dp)) {
                Text("빛 단서 ${i + 1}", style = t.caption.copy(color = C.text2))
                Text(spot.label, style = t.label)
                if (!v.onScreen) Text(if (on) "켜짐" else "꺼짐", style = t.caption.copy(color = if (on) C.warm else C.text3))
            }
        }
    }
    when {
        v.lightMistake -> Text(if (v.onScreen) "순서가 달라요. 처음부터 다시 눌러 보세요." else "순서가 달라요. 세 불을 모두 끄고 다시 해 볼까요?", style = t.headline.copy(color = C.warm))
        !v.onScreen && !v.lightsArmed -> Text("먼저 세 불을 모두 꺼 주세요", style = t.headline.copy(color = C.warm))
        else -> Text("거꾸로 켜 보세요 · ${v.lightProgress} / ${clues.size}", style = t.headline.copy(color = C.text2))
    }
    if (v.onScreen) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        clues.forEach { spot -> PillButton(spot.label, BtnStyle.SECONDARY, { vm.episodeScreenLight(spot) }) }
    }
}

@Composable
private fun LightsOffStage(v: EpisodeView, s: UiState, vm: AppViewModel) {
    val t = LocalType.current
    DeviceTag("차에서 조작 · 독서등·실내등" + EpisodeScript.role(v))
    Text(EpisodeScript.instruction(v), style = t.title)
    Episode1.clueLights(v.difficulty).forEach { spot ->
        val on = s.signals.lights[spot] == true
        StateRow(if (on) RowState.WARN else RowState.DONE, spot.label + if (on) " · 켜져 있어요" else " · 꺼짐")
    }
    PillButton("이대로 둘게요", BtnStyle.SECONDARY, vm::episodeConfirmClosing, icon = Icons.AutoMirrored.Filled.ArrowForward)
}

// ---------------------------------------------------------------- end of the episode
@Composable
fun EpisodeDoneScreen(s: UiState, vm: AppViewModel) {
    val t = LocalType.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterVertically)) {
        Badge("🔦", C.cyanSoft, 140)
        Text("지하 4층 탈출 성공!", style = t.title)
        Panel {
            Text("처음과 달라진 차량 상태를 확인해 주세요", style = t.headline)
            RestoreRows(s.restore)
            AutoOffRows(s.autoOff)
            LeftoverRows(Leftovers.find(s.baseline, s.signals))
            LightsOffButton(s, vm)
        }
        PillButton("처음 화면으로", BtnStyle.PRIMARY, vm::goHome, icon = Icons.Filled.Check)
    }
}

/** Home card for episode 1: difficulty, then start or continue from the saved scene. */
@Composable
fun EpisodeCard(s: UiState, vm: AppViewModel, modifier: Modifier = Modifier) {
    val t = LocalType.current
    Panel(modifier, color = C.night) {
        Text("선택 콘텐츠 · 방탈출", style = t.label.copy(color = C.cyan))
        Text(EpisodeScript.TITLE, style = t.headline)
        val saved = s.savedRuns[Episode1.ID]?.let { EpisodeStage.entries.getOrNull(it) }
        // review-safety M10: the clue lights depend on the difficulty, so it stays as it was while a saved run waits.
        val locked = Episode1.ID in s.savedRuns
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(Difficulty.EASY to "쉬움(가족)", Difficulty.NORMAL to "보통").forEach { (d, label) ->
                val on = s.episodeDifficulty == d
                val tap = if (locked) Modifier else Modifier.clickable { vm.setEpisodeDifficulty(d) }
                Box(Modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(if (on) C.cyan else C.surfaceHigh)
                    .then(tap).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = t.label.copy(color = if (on) C.base else if (locked) C.text3 else C.text))
                }
            }
            if (locked) Text("이어 하는 중", style = t.caption.copy(color = C.text2))
        }
        PillButton(if (saved != null) "이어서: ${saved.label}" else "시작", if (saved != null) BtnStyle.PRIMARY else BtnStyle.SECONDARY, vm::startEpisode, icon = Icons.Filled.PlayArrow)
    }
}
