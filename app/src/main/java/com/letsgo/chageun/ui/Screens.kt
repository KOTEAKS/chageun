package com.letsgo.chageun.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.letsgo.chageun.AppViewModel
import com.letsgo.chageun.AutoOff
import com.letsgo.chageun.Screen
import com.letsgo.chageun.SpeedPermission
import com.letsgo.chageun.UiState
import com.letsgo.chageun.core.CorePuzzles
import com.letsgo.chageun.core.FeatureId
import com.letsgo.chageun.core.GateBlock
import com.letsgo.chageun.core.GateState
import com.letsgo.chageun.core.HelpOffer
import com.letsgo.chageun.core.Leftover
import com.letsgo.chageun.core.Leftovers
import com.letsgo.chageun.core.Progress
import com.letsgo.chageun.core.TurnDir
import com.letsgo.chageun.core.RestoreResult
import com.letsgo.chageun.core.Review
import com.letsgo.chageun.core.StepKind
import com.letsgo.chageun.core.StepStatus
import com.letsgo.chageun.core.StopReason
import com.letsgo.chageun.core.Support
import com.letsgo.chageun.data.Effects
import com.letsgo.chageun.data.Settings

@Composable
fun Root(s: UiState, vm: AppViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize().background(C.base)) {
        val compact = maxWidth < 1100.dp
        Box(Modifier.fillMaxSize().padding(start = if (compact) 24.dp else 64.dp, end = if (compact) 24.dp else 64.dp,
            top = if (compact) 24.dp else 48.dp, bottom = if (compact) 28.dp else 60.dp)) {
            when (s.screen) {
                Screen.FIRST_RUN -> FirstRunScreen(s.settings, vm::finishFirstRun)
                Screen.HOME -> HomeScreen(s, vm)
                Screen.CANNOT_START -> CannotStartScreen(s, vm)
                Screen.PUZZLE -> PuzzleScreen(s, vm)
                Screen.EPISODE -> EpisodeScreen(s, vm)
                Screen.STOPPED -> StoppedScreen(s, vm)
                Screen.DONE -> DoneScreen(s, vm)
                Screen.EPISODE_DONE -> EpisodeDoneScreen(s, vm)
                Screen.DEX -> DexScreen(s, vm::goHome)
                Screen.WRAP_UP -> WrapUpScreen(s, vm)
            }
        }
        // Honest demo label (Codex review 3-13): the signals on the emulator come from the Virtual Console.
        if (!compact) Text("에뮬레이터 차량 신호 재현(Virtual Console)", style = LocalType.current.caption.copy(color = C.text3), modifier = Modifier.align(Alignment.BottomCenter).offset(y = 52.dp))
    }
}

@Composable
internal fun TopBar(left: @Composable () -> Unit, right: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        left()
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically, content = right)
    }
}

// ---------------------------------------------------------------- first run
@Composable
fun FirstRunScreen(initial: Settings, onDone: (Settings) -> Unit) {
    val t = LocalType.current
    var s by remember { mutableStateOf(initial) }
    // Scrolls so large text never pushes the start buttons off screen.
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Text("차근차근", style = t.title.copy(color = C.warm))
        Column {
            Text("안내 방식을 골라 주세요", style = t.title)
            Text("언제든 설정에서 바꿀 수 있어요.", style = t.body.copy(color = C.text2))
        }
        Panel(Modifier.fillMaxWidth()) {
            Option("안내 방식", listOf("음성 + 자막", "자막만"), if (s.voice) 0 else 1) { s = s.copy(voice = it == 0) }
            Option("글자 크기", listOf("보통", "크게"), if (s.largeText) 1 else 0) { s = s.copy(largeText = it == 1) }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(C.warmSoft).padding(horizontal = 36.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🛡", style = t.headline)
            Column {
                Text("안전하게 주차한 곳에서만 이용해 주세요", style = t.label)
                Text("밀폐된 차고에서는 시동을 켠 채 이용하지 마세요.", style = t.body.copy(color = C.text2))
                Text("차가 멈췄는지 확인하려고 속도를 읽어요. 권한 창에는 '위치 정보'로 표시돼요.", style = t.body.copy(color = C.text2))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PillButton("시작하기", BtnStyle.PRIMARY, { onDone(s) }, icon = Icons.Filled.PlayArrow)
        }
    }
}

@Composable
private fun Option(name: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val t = LocalType.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, style = t.headline, modifier = Modifier.width(300.dp))
        options.forEachIndexed { i, o ->
            Box(
                Modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).background(if (i == selected) C.warm else C.surfaceHigh)
                    .clickable { onSelect(i) }.padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, style = t.label.copy(color = if (i == selected) C.onAccent else C.text)) }
        }
    }
}

// ---------------------------------------------------------------- home
@Composable
fun HomeScreen(s: UiState, vm: AppViewModel) {
    val t = LocalType.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val compact = maxWidth < 1100.dp
      Column(
        modifier = if (compact) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 32.dp),
      ) {
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("차근차근", style = t.title.copy(color = C.warm))
                Text("내 차 기능을 하나씩 익혀 보세요", style = t.body.copy(color = C.text2))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton("안내 방식", BtnStyle.GHOST, vm::openSettings, icon = Icons.Filled.Settings)
                    GateChip(s.gate, s.speedPermission)
                }
            }
        } else TopBar(
            left = { Column { Text("차근차근", style = t.display.copy(color = C.warm)); Text("내 차 기능을 하나씩 익혀 보세요", style = t.body.copy(color = C.text2)) } },
            right = { PillButton("안내 방식", BtnStyle.GHOST, vm::openSettings, icon = Icons.Filled.Settings); GateChip(s.gate, s.speedPermission) },
        )
        if (s.speedPermission == SpeedPermission.DENIED || s.speedPermission == SpeedPermission.BLOCKED) SpeedPermissionPanel(s.speedPermission, vm)
        // The last session ended without its check (the app was closed mid-way): what it left on or moved, once.
        val left = if (s.leftFromLastTime) Leftovers.find(s.baseline, s.signals).filter { it.warn } else emptyList()
        if (left.isNotEmpty()) Panel(Modifier.fillMaxWidth(), color = C.warmSoft) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("지난번 사용한 기능을 확인해 주세요", style = t.headline)
                    LeftoverRows(left)
                }
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LightsOffButton(s, vm)
                    PillButton("확인했어요", BtnStyle.GHOST, vm::dismissLeftovers, icon = Icons.Filled.Check)
                }
            }
        }
        s.dueReviews.firstOrNull()?.let { due ->
            Panel(Modifier.fillMaxWidth(), color = C.cyanSoft) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("어제 익힌 '${due.title}', 다른 상황에서 한 번 더 해 볼까요?", style = t.headline)
                        Text("다른 상황에서 다시 해 보고, 익힌 내용을 확인해요.", style = t.body.copy(color = C.text2))
                        if (s.dayOffset != 0) Text("시연: 날짜를 ${s.dayOffset}일 뒤로 설정함", style = t.caption.copy(color = C.text3))
                    }
                    PillButton("복습하기", BtnStyle.PRIMARY, { vm.startReview(due) }, icon = Icons.Filled.PlayArrow)
                }
            }
        }
        if (s.pendingEffectRestore) {
            Panel(Modifier.fillMaxWidth(), color = C.warmSoft) {
                Text("지난번에 앱이 바꾼 조명이 남아 있을 수 있어요. 되돌릴까요?", style = t.body)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    PillButton("되돌리기", BtnStyle.PRIMARY, { vm.restorePendingEffects() })
                    PillButton("그대로 두기", BtnStyle.SECONDARY, vm::dismissPendingEffects)
                }
            }
        }
        if (compact) {
            Panel(Modifier.fillMaxWidth(), border = C.warm) {
                Text("내 차 기능 익히기", style = t.label.copy(color = C.warm))
                Text("어떤 기능부터 알아볼까요?", style = t.title)
                Text("상황을 읽고 차에서 직접 해 본 뒤, 결과와 사용 방법을 확인할 수 있어요.", style = t.body.copy(color = C.text2))
                Text("확인한 기능 ${s.dex.vehicleConfirmed + s.dex.guidanceSeen} / ${s.dex.available}", style = t.label)
                Text("차량 신호 확인 ${s.dex.vehicleConfirmed} · 안내 확인 ${s.dex.guidanceSeen}", style = t.caption.copy(color = C.text2))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CorePuzzles.all.forEach { p ->
                        val resume = p.id in s.savedRuns
                        PillButton(if (resume) "이어서: ${p.title}" else p.title, if (resume) BtnStyle.PRIMARY else BtnStyle.SECONDARY, { vm.startPuzzle(p.id) })
                    }
                }
            }
            Panel(Modifier.fillMaxWidth()) {
                Text("기능 확인 현황", style = t.headline)
                Text("확인 가능 ${s.dex.available}개 · 확인 완료 ${s.dex.vehicleConfirmed + s.dex.guidanceSeen}개", style = t.body.copy(color = C.text2))
                PillButton("전체 기능 보기", BtnStyle.SECONDARY, vm::openDex)
            }
            EpisodeCard(s, vm, Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Panel(Modifier.weight(1f).fillMaxHeight(), border = C.warm) {
                Text("내 차 기능 익히기", style = t.label.copy(color = C.warm))
                Text("어떤 기능부터\n알아볼까요?", style = t.display)
                Text("상황을 읽고 차에서 직접 해 본 뒤, 결과와 사용 방법을 확인할 수 있어요.", style = t.body.copy(color = C.text2))
                Box(Modifier.weight(1f))
                Text("확인한 기능 ${s.dex.vehicleConfirmed + s.dex.guidanceSeen} / ${s.dex.available}", style = t.label)
                Text("차량 신호 확인 ${s.dex.vehicleConfirmed} · 안내 확인 ${s.dex.guidanceSeen}", style = t.caption.copy(color = C.text2))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CorePuzzles.all.forEach { p ->
                        val resume = p.id in s.savedRuns
                        PillButton(if (resume) "이어서: ${p.title}" else p.title, if (resume) BtnStyle.PRIMARY else BtnStyle.SECONDARY, { vm.startPuzzle(p.id) })
                    }
                }
            }
            Column(Modifier.width(520.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                Panel(Modifier.fillMaxWidth().weight(1f)) {
                    Text("기능 확인 현황", style = t.headline)
                    Text("확인 가능 ${s.dex.available}개 · 확인 완료 ${s.dex.vehicleConfirmed + s.dex.guidanceSeen}개", style = t.body.copy(color = C.text2))
                    PillButton("전체 기능 보기", BtnStyle.SECONDARY, vm::openDex)
                }
                EpisodeCard(s, vm, Modifier.fillMaxWidth().weight(1f))
            }
        }
        Text("안전하게 주차한 곳에서만 이용해 주세요 · 지하 주차장처럼 막힌 곳에서는 시동을 켠 채 오래 머물지 마세요", style = t.body.copy(color = C.text2))
      }
    }
}

// ---------------------------------------------------------------- cannot start
@Composable
fun CannotStartScreen(s: UiState, vm: AppViewModel) {
    val t = LocalType.current
    val gate = s.gate
    val signalProblem = gate is GateState.Blocked && GateBlock.SIGNAL_UNAVAILABLE in gate.reasons
    val permissionProblem = s.speedPermission == SpeedPermission.DENIED || s.speedPermission == SpeedPermission.BLOCKED
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically)) {
        Badge("🛡", C.surfaceHigh, 160)
        if (permissionProblem) {
            Text("속도 권한이 필요해요", style = t.title)
            SpeedPermissionPanel(s.speedPermission, vm)
        } else if (signalProblem) {
            Text("차량 신호를 읽지 못했어요", style = t.title)
            Text("기어·속도·문 상태를 확인할 수 없어 시작하지 않아요. 잠시 뒤 다시 시도해 주세요.", style = t.body.copy(color = C.text2), textAlign = TextAlign.Center)
        } else {
            Text("안전하게 주차한 곳에서 이용해 주세요", style = t.title)
            Text("차근차근은 차가 완전히 멈추고, 기어가 P에 있고, 문이 모두 닫혀 있을 때만 시작할 수 있어요.", style = t.body.copy(color = C.text2), textAlign = TextAlign.Center)
            Text("운전 중이나 신호 대기 중에는 쉬어 가요.", style = t.body.copy(color = C.text2))
        }
        PillButton("처음 화면으로", BtnStyle.GHOST, vm::goHome)
    }
}

/** The speed permission is shown as "location" by the system, so say what it is for and how to turn it on again. */
@Composable
internal fun SpeedPermissionPanel(permission: SpeedPermission, vm: AppViewModel) {
    val t = LocalType.current
    Panel(Modifier.fillMaxWidth(), color = C.dangerSoft) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("속도 권한이 꺼져 있어 시작할 수 없어요", style = t.headline)
                Text(if (permission == SpeedPermission.BLOCKED) "설정 › 앱 › 차근차근 › 권한에서 '위치 정보'를 허용한 뒤 돌아오세요. 속도만 읽고 위치는 읽지 않아요."
                    else "차가 멈춰 있는지 확인하려고 속도만 읽어요. 권한 창에는 '위치 정보'로 표시되지만 위치는 읽지 않아요.", style = t.body.copy(color = C.text2))
            }
            if (permission == SpeedPermission.BLOCKED) PillButton("설정 열기", BtnStyle.PRIMARY, vm::fixSpeedPermission, icon = Icons.Filled.Settings)
            else PillButton("권한 허용하기", BtnStyle.PRIMARY, vm::fixSpeedPermission, icon = Icons.Filled.Check)
        }
    }
}

// ---------------------------------------------------------------- puzzle
@Composable
fun PuzzleScreen(s: UiState, vm: AppViewModel) {
    val t = LocalType.current
    val p = s.puzzle ?: return
    val step = p.steps.getOrNull(s.stepIndex) ?: return
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        TopBar(
            left = { Column { Text(p.chapter, style = t.label.copy(color = C.warm)); Text(p.title, style = t.title) } },
            right = {
                Text("${s.stepIndex + 1} / ${p.steps.size}", style = t.label.copy(color = C.text2))
                GateChip(s.gate, s.speedPermission)
                PillButton("나가기", BtnStyle.GHOST, { vm.exitPuzzle() }, icon = Icons.AutoMirrored.Filled.ExitToApp)
            },
        )
        StepPills(p.steps.map { it.label }, s.stepIndex)
        Panel(Modifier.fillMaxWidth().weight(1f)) {
            when (step.kind) {
                StepKind.VEHICLE_ACTION, StepKind.VEHICLE_REVERT -> if (s.settings.screenOnly) {
                    // "화면으로 대신": find the button in the car without pressing it, then confirm on screen.
                    Text("화면으로 대신", style = t.label.copy(color = C.cyan))
                    Text(step.instruction, style = t.title)
                    Text("차에서 누르지 않아도 돼요. 버튼이 어디 있는지만 찾아보세요.", style = t.body.copy(color = C.text2))
                    PillButton("찾았어요", BtnStyle.PRIMARY, vm::doOnScreen, icon = Icons.Filled.Check)
                } else {
                    DeviceTag("차에서 조작")
                    Text(step.instruction, style = t.title)
                    if (s.settings.detailedGuide) step.note?.let { Text(it, style = t.body.copy(color = C.text2)) }
                    when (s.status) {
                        StepStatus.AlreadyMet -> {
                            Text(when {
                                step.kind == StepKind.VEHICLE_REVERT -> "이미 꺼져 있어요. 다음으로 넘어가요."
                                p.safetyFeature -> "지금 작동 중이에요. 위치만 확인하고 넘어가요."
                                else -> "이미 켜져 있어요. 껐다가 다시 켜 봐도 돼요(선택)."
                            }, style = t.headline.copy(color = C.warm))
                            PillButton("이대로 다음", BtnStyle.SECONDARY, vm::continueAlreadyMet, icon = Icons.AutoMirrored.Filled.ArrowForward)
                        }
                        StepStatus.CannotConfirm -> {
                            Text("차에서 상태를 확인할 수 없어요. 화면으로 이어서 해도 돼요.", style = t.headline.copy(color = C.warm))
                        }
                        else -> Text("기다리는 중 · 차에서 바뀌면 자동으로 넘어가요", style = t.body.copy(color = C.text2))
                    }
                    p.demoNote?.let { Text(it, style = t.caption.copy(color = C.text3)) }
                }
                StepKind.QUIZ -> {
                    val q = step.quiz ?: return@Panel
                    Text(q.question, style = t.title)
                    val answered = s.status as? StepStatus.Answered
                    q.options.forEachIndexed { i, o ->
                        val color = when {
                            answered == null -> C.surfaceHigh
                            i == q.answer -> C.success
                            i == answered.choice -> C.dangerSoft
                            else -> C.surfaceHigh
                        }
                        // Marked in words too, not only by colour.
                        val mark = when {
                            answered == null -> ""
                            i == q.answer -> "✓ 정답 · "
                            i == answered.choice -> "✗ 내가 고른 답 · "
                            else -> ""
                        }
                        Box(Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(24.dp)).background(color)
                            .clickable(enabled = answered == null) { vm.answer(i) }.padding(horizontal = 32.dp),
                            contentAlignment = Alignment.CenterStart) {
                            Text(mark + o, style = t.headline.copy(color = if (answered != null && i == q.answer) C.base else C.text))
                        }
                    }
                    if (answered != null) {
                        // Explanation and "next" share a row so large text does not push the button out of the panel.
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(q.explanation, style = t.body, modifier = Modifier.weight(1f))
                            PillButton("다음", BtnStyle.PRIMARY, vm::afterAnswer, icon = Icons.AutoMirrored.Filled.ArrowForward)
                        }
                    }
                }
                StepKind.SCREEN_CONFIRM -> {
                    Text("화면에서 답하기", style = t.label.copy(color = C.cyan))
                    Text(step.instruction, style = t.title)
                    step.note?.let { Text(it, style = t.body.copy(color = C.text2)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        PillButton("네, 확인했어요", BtnStyle.SECONDARY, vm::confirm, icon = Icons.Filled.Check)
                        PillButton("잘 모르겠어요", BtnStyle.GHOST, vm::confirm)
                    }
                }
                StepKind.INFO -> {
                    Text(step.instruction, style = t.title)
                    step.note?.let { Text(it, style = t.body.copy(color = C.text2)) }
                    PillButton("알겠어요", BtnStyle.PRIMARY, vm::confirm, icon = Icons.Filled.Check)
                }
            }
            if (s.hintLevel > 0) step.hints.getOrNull(s.hintLevel - 1)?.let { Text("힌트 ${s.hintLevel}: $it", style = t.body.copy(color = C.warm)) }
        }
        when {
            s.restNotice -> VoiceCaption("쉬어 가기", AppViewModel.REST_LINE)
            s.helpOffer -> VoiceCaption("도움", HelpOffer.LINE)
            else -> VoiceCaption("차근차근", AppViewModel.stepLine(step, s.settings))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                PillButton("다시 말해 줘", BtnStyle.SECONDARY, vm::repeatVoice, icon = Icons.Filled.Refresh)
                PillButton("힌트", BtnStyle.SECONDARY, vm::hint, enabled = step.hints.isNotEmpty())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                val vehicleStep = step.kind == StepKind.VEHICLE_ACTION || step.kind == StepKind.VEHICLE_REVERT
                PillButton("화면으로 하기", BtnStyle.SECONDARY, vm::doOnScreen, enabled = vehicleStep && !s.settings.screenOnly)
                PillButton("건너뛰기", BtnStyle.GHOST, vm::skip)
            }
        }
    }
}

// ---------------------------------------------------------------- stopped
@Composable
fun StoppedScreen(s: UiState, vm: AppViewModel) {
    val t = LocalType.current
    val stopped = s.stopped
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically)) {
        Badge("⏸", C.warmSoft, 112)
        val blocks = stopped?.blocks.orEmpty()
        Text(when {
            stopped?.reason == StopReason.AUDIO_FOCUS_LOST -> "다른 소리가 나와서 멈췄어요"
            stopped?.reason != StopReason.GATE -> "잠시 멈췄어요"
            GateBlock.MOVING in blocks || GateBlock.NOT_IN_PARK in blocks -> "주행이 감지되어 멈췄어요"
            GateBlock.DOOR_OPEN in blocks -> "문이 열려서 멈췄어요"
            GateBlock.SIGNAL_UNAVAILABLE in blocks -> "차량 신호를 읽지 못해 멈췄어요"
            else -> "잠시 멈췄어요"
        }, style = t.title)
        Text(if (stopped?.reason == StopReason.GATE && GateBlock.DOOR_OPEN in blocks && blocks.size == 1) "문을 모두 닫으면 이어서 할 수 있어요. 자동으로 다시 시작하지는 않아요."
            else "자동으로 다시 시작하지 않아요. 안전하게 주차했는지 확인한 뒤 이어서 하세요.", style = t.body.copy(color = C.text2))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Panel {
                StateRow(RowState.DONE, "소리와 음성 안내를 멈췄어요")
                RestoreRows(s.restore)
                AutoOffRows(s.autoOff)
                StateRow(RowState.DONE, "진행을 저장했어요")
            }
            val left = Leftovers.find(s.baseline, s.signals)
            if (left.isNotEmpty()) Panel(color = C.warmSoft) {
                Text("출발 전에 확인해 주세요", style = t.headline)
                LeftoverRows(left)
                LightsOffButton(s, vm)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            PillButton("여기서 마치기", BtnStyle.GHOST, { vm.endFromStopped() }, icon = Icons.AutoMirrored.Filled.ExitToApp)
            PillButton("이어서 하기", BtnStyle.PRIMARY, vm::resume, icon = Icons.Filled.PlayArrow, enabled = s.gate == GateState.Ready)
        }
        Text("정차 조건(P단 · 정차 · 문 닫힘)이 맞을 때만 이어서 할 수 있어요", style = t.body.copy(color = C.text2))
    }
}

@Composable
internal fun RestoreRows(results: List<RestoreResult>) {
    if (results.isEmpty()) { StateRow(RowState.SEEN, "앱이 바꾼 조명은 없어요"); return }
    results.forEach {
        when (it) {
            is RestoreResult.Restored -> StateRow(RowState.DONE, "앱이 바꾼 조명을 되돌렸어요 · 확인됨")
            is RestoreResult.NotVerified -> StateRow(RowState.WARN, "조명을 되돌렸지만 확인하지 못했어요 · 직접 확인해 주세요")
            is RestoreResult.NoControl -> StateRow(RowState.WARN, "조명을 되돌릴 권한이 없어요 · 직접 확인해 주세요")
            is RestoreResult.LeftForUser -> StateRow(RowState.SEEN, "조명은 직접 바꾸셔서 그대로 뒀어요")
        }
    }
}

/** Lights the app switched off when driving was detected. */
@Composable
internal fun AutoOffRows(results: List<AutoOff>) {
    results.forEach {
        if (it.ok) StateRow(RowState.DONE, "앱에서 켠 ${it.spot.label}을 껐어요")
        else StateRow(RowState.WARN, "${it.spot.label}을 끄지 못했어요 · 직접 꺼 주세요")
    }
}

/**
 * One tap to switch off the lights the game had switched on. Shown only in P: the car refuses light control in D.
 */
@Composable
internal fun LightsOffButton(s: UiState, vm: AppViewModel) {
    if (s.gate != GateState.Ready || Leftovers.lightsToSwitchOff(s.baseline, s.signals).isEmpty()) return
    PillButton("앱에서 켠 실내등 끄기", BtnStyle.SECONDARY, { vm.switchOffLightsNow() })
}

/** What the session left on or moved; updates live as the player puts things back. */
@Composable
internal fun LeftoverRows(items: List<Leftover>) {
    items.forEach {
        when (it) {
            is Leftover.TurnSignalOn -> StateRow(RowState.WARN, "방향지시등(${if (it.dir == TurnDir.LEFT) "왼쪽" else "오른쪽"})이 켜져 있어요 · 꺼 주세요")
            is Leftover.WheelTurned -> StateRow(RowState.WARN, "핸들이 ${if (it.degrees < 0) "왼쪽" else "오른쪽"}으로 돌아가 있어요 · 출발 방향을 확인하세요")
            is Leftover.LightOn -> StateRow(RowState.WARN, "${it.spot.label}이 켜져 있어요 · 밤에는 앞유리에 비쳐요")
            is Leftover.SeatHeatOn -> StateRow(RowState.WARN, "운전석 열선이 켜져 있어요 · 필요 없으면 꺼 주세요")
            is Leftover.SeatVentOn -> StateRow(RowState.SEEN, "운전석 통풍이 켜져 있어요 · 필요 없으면 꺼 주세요")
            is Leftover.TempChanged -> StateRow(RowState.SEEN, "운전석 온도가 ${fmtTemp(it.from)}°C에서 ${fmtTemp(it.now)}°C로 바뀌었어요")
        }
    }
}

private fun fmtTemp(c: Float) = com.letsgo.chageun.core.EpisodeScript.fmt(c)

// ---------------------------------------------------------------- wrap-up (leaving mid-way)
@Composable
fun WrapUpScreen(s: UiState, vm: AppViewModel) {
    val t = LocalType.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterVertically)) {
        Badge("🚗", C.warmSoft, 112)
        Text("출발 전에 확인해 주세요", style = t.title)
        Text("앱 사용 중 바뀐 항목이 있어요. 출발 전에 상태를 확인해 주세요.", style = t.body.copy(color = C.text2))
        Panel {
            AutoOffRows(s.autoOff)
            LeftoverRows(Leftovers.find(s.baseline, s.signals))
            RestoreRows(s.restore)
            LightsOffButton(s, vm)
        }
        PillButton("처음 화면으로", BtnStyle.PRIMARY, vm::goHome, icon = Icons.Filled.Check)
    }
}

// ---------------------------------------------------------------- done
@Composable
fun DoneScreen(s: UiState, vm: AppViewModel) {
    val t = LocalType.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically)) {
        Badge("★", C.warmSoft, 140)
        Text(when (s.lastReview) {
            Review.UNAIDED_SUCCESS -> "복습 성공! 도움 없이 해냈어요"
            Review.AIDED -> "복습 완료! 다음엔 도움 없이 해 봐요"
            else -> "잘했어요!"
        }, style = t.title)
        Panel {
            Text("처음과 달라진 차량 상태를 확인해 주세요", style = t.headline)
            RestoreRows(s.restore)
            LeftoverRows(Leftovers.find(s.baseline, s.signals))
            if (s.signals.frontDefroster == true) StateRow(RowState.SEEN, "앞유리 서리 제거는 그대로 유지돼요")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    PillButton("전체 기능 보기", BtnStyle.SECONDARY, vm::openDex)
            PillButton("처음 화면으로", BtnStyle.PRIMARY, vm::goHome, icon = Icons.Filled.Check)
        }
    }
}

// ---------------------------------------------------------------- dex
@Composable
fun DexScreen(s: UiState, onHome: () -> Unit) {
    val t = LocalType.current
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        TopBar(left = { Column { Text("내 차 기능", style = t.label.copy(color = C.warm)); Text("기능 확인 현황", style = t.title) } },
            right = { PillButton("처음 화면으로", BtnStyle.GHOST, onHome, icon = Icons.AutoMirrored.Filled.ExitToApp) })
        Text("확인 가능 ${s.dex.available}개 · 차량 신호 확인 ${s.dex.vehicleConfirmed} · 안내 확인 ${s.dex.guidanceSeen} · 미지원 ${s.dex.unsupported} · 확인 전 ${s.dex.unknown}", style = t.body.copy(color = C.text2))
        Panel(Modifier.fillMaxWidth().weight(1f)) {
            val items = FeatureId.entries
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                for (col in items.chunked((items.size + 1) / 2)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        col.forEach { id ->
                            val r = s.records[id] ?: return@forEach
                            val support = if (id.detectable) r.support else Support.SUPPORTED
                            val (state, suffix) = when {
                                support == Support.UNSUPPORTED -> RowState.UNSUPPORTED to " · 이 차에 없음"
                                r.review == Review.UNAIDED_SUCCESS -> RowState.DONE to " · 복습 성공"
                                r.progress == Progress.VEHICLE_CONFIRMED -> RowState.DONE to if (r.review == Review.AIDED) " · 차에서 확인 · 복습(도움)" else " · 차에서 확인"
                                r.progress == Progress.GUIDANCE_SEEN -> RowState.SEEN to if (id.detectable) " · 안내 확인" else " · 설명"
                                r.progress == Progress.SKIPPED -> RowState.TODO to " · 건너뜀"
                                support == Support.UNKNOWN -> RowState.TODO to " · 확인 전"
                                else -> RowState.TODO to ""
                            }
                            StateRow(state, id.title + suffix)
                        }
                    }
                }
            }
        }
    }
}
