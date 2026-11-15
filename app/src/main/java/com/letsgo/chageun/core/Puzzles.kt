package com.letsgo.chageun.core

enum class StepKind {
    /** The player changes something in the car; a vehicle signal confirms it. */
    VEHICLE_ACTION,
    /** The player answers on screen about something the app cannot sense (e.g. airflow direction). */
    SCREEN_CONFIRM,
    QUIZ,
    /** The player turns the feature back off in the car; a vehicle signal confirms it. */
    VEHICLE_REVERT,
    /** Explanation only; the app does not ask the player to change anything. */
    INFO,
}

enum class GoalCheck { MET, NOT_MET, UNKNOWN }

/** A vehicle goal. Returns null when the event is about a different signal. */
enum class Goal {
    SEAT_HEAT_ON, SEAT_HEAT_OFF, SEAT_VENT_ON, SEAT_VENT_OFF, DEFROSTER_ON, CRUISE_STANDBY;

    fun check(e: VehicleEvent): GoalCheck? = when (this) {
        SEAT_HEAT_ON -> (e as? VehicleEvent.SeatHeat)?.let { if (it.level > 0) GoalCheck.MET else GoalCheck.NOT_MET }
        SEAT_HEAT_OFF -> (e as? VehicleEvent.SeatHeat)?.let { if (it.level <= 0) GoalCheck.MET else GoalCheck.NOT_MET }
        SEAT_VENT_ON -> (e as? VehicleEvent.SeatHeat)?.let { if (it.level < 0) GoalCheck.MET else GoalCheck.NOT_MET }
        // Ventilation is a negative level, so "off" for it is 0 or above (the heat goals use the opposite side).
        SEAT_VENT_OFF -> (e as? VehicleEvent.SeatHeat)?.let { if (it.level >= 0) GoalCheck.MET else GoalCheck.NOT_MET }
        DEFROSTER_ON -> (e as? VehicleEvent.FrontDefroster)?.let { if (it.on) GoalCheck.MET else GoalCheck.NOT_MET }
        CRUISE_STANDBY -> (e as? VehicleEvent.Cruise)?.let {
            when (it.state) {
                CruiseState.STANDBY -> GoalCheck.MET
                CruiseState.UNKNOWN -> GoalCheck.UNKNOWN
                else -> GoalCheck.NOT_MET
            }
        }
    }

    fun current(s: PuzzleSignals): GoalCheck? = when (this) {
        SEAT_HEAT_ON, SEAT_HEAT_OFF, SEAT_VENT_ON, SEAT_VENT_OFF -> s.seatHeat?.let { check(VehicleEvent.SeatHeat(it)) }
        DEFROSTER_ON -> s.frontDefroster?.let { check(VehicleEvent.FrontDefroster(it)) }
        CRUISE_STANDBY -> s.cruise?.let { check(VehicleEvent.Cruise(it)) }
    }
}

data class Quiz(val question: String, val options: List<String>, val answer: Int, val explanation: String) {
    /**
     * review-safety M8: the result is spoken too, so it never depends on reading colours or text.
     * The right answer is its own sentence, so no Korean particle has to fit an option that ends in "…요".
     */
    fun resultLine(choice: Int, detailed: Boolean): String {
        val head = if (choice == answer) "맞았어요." else "아쉬워요. 정답은 이거예요. ${options[answer].trimEnd('.')}."
        return if (detailed) "$head $explanation" else head
    }
}

data class Step(
    val label: String,
    val instruction: String,
    val voice: String,
    val kind: StepKind,
    val goal: Goal? = null,
    val quiz: Quiz? = null,
    val note: String? = null,
    val hints: List<String> = emptyList(),
)

data class Puzzle(
    val id: String,
    val feature: FeatureId,
    val chapter: String,
    val title: String,
    /** Safety functions are never toggled off by request and never restored by the app. */
    val safetyFeature: Boolean,
    val steps: List<Step>,
    val demoNote: String? = null,
    /** A next-day review: the result goes to the review axis (unaided or aided), see planning/puzzle-design.md §3.5. */
    val review: Boolean = false,
)

/** Chapter 0 core three, written to the 5-step learning model in planning/puzzle-design.md §4.1. */
object CorePuzzles {
    val coldMorning = Puzzle(
        id = "0-2", feature = FeatureId.SEAT_HEAT, chapter = "내 차 기능 익히기", title = "추운 아침", safetyFeature = false,
        steps = listOf(
            Step(
                label = "열선 켜기", kind = StepKind.VEHICLE_ACTION, goal = Goal.SEAT_HEAT_ON,
                instruction = "운전석 열선 버튼을 찾아 켜 주세요",
                voice = "아침이라 시트가 차가워요. 운전석 열선을 켜 볼까요?",
                note = "단계는 상관없어요. 공조 버튼 근처나 화면 아래 공조 막대에 있어요.",
                hints = listOf("공조 버튼이 모인 곳을 살펴보세요.", "시트 모양에 물결 세 줄이 그려진 버튼이에요.", "화면 아래 공조 막대의 시트 아이콘을 눌러도 돼요."),
            ),
            Step(
                label = "이유 퀴즈", kind = StepKind.QUIZ,
                instruction = "에어컨을 켰는데 등이 땀으로 젖어요. 무엇을 쓸까요?",
                voice = "이번엔 여름이에요. 등이 덥다면 무엇을 쓰면 좋을까요?",
                quiz = Quiz("에어컨을 켰는데 등이 땀으로 젖어요. 무엇을 쓸까요?", listOf("시트 열선", "시트 통풍", "앞유리 서리 제거"), 1,
                    "시트 통풍은 시트 안쪽으로 바람을 보내 등과 엉덩이를 식혀 줘요. 같은 버튼 근처에 있는 경우가 많아요."),
            ),
            Step(
                label = "열선 끄기", kind = StepKind.VEHICLE_REVERT, goal = Goal.SEAT_HEAT_OFF,
                instruction = "이제 운전석 열선을 꺼 주세요",
                voice = "잘했어요. 마지막으로 열선을 꺼 볼까요?",
                note = "켜는 법과 끄는 법을 둘 다 해 봐야 다음에 혼자서도 쓸 수 있어요.",
                hints = listOf("아까 누른 버튼을 다시 눌러 보세요.", "단계가 있는 버튼은 여러 번 눌러야 꺼질 수 있어요."),
            ),
        ),
    )

    val foggyWindshield = Puzzle(
        id = "0-4", feature = FeatureId.FRONT_DEFROST, chapter = "시야 확보 기능", title = "김 서린 앞유리", safetyFeature = true,
        steps = listOf(
            Step(
                label = "버튼 찾기", kind = StepKind.VEHICLE_ACTION, goal = Goal.DEFROSTER_ON,
                instruction = "앞유리 서리 제거 버튼을 찾아 켜 주세요",
                voice = "비 오는 날 앞유리가 뿌옇게 됐어요. 앞유리 서리 제거를 켜 볼까요?",
                note = "부채꼴 유리 그림에 물결 화살표가 있는 버튼이에요.",
                hints = listOf("공조 버튼 중 유리 모양 그림을 찾아보세요.", "뒷유리 열선(네모 유리)이 아니라 앞유리(부채꼴)예요."),
            ),
            Step(
                label = "송풍 확인", kind = StepKind.SCREEN_CONFIRM,
                instruction = "바람이 앞유리 쪽으로 나오나요?",
                voice = "잘 찾았어요! 송풍 방향이 앞유리 쪽인지 확인해 주세요.",
                note = "송풍 방향이 앞유리 쪽인지 확인해 주세요.",
            ),
            Step(
                label = "이유 퀴즈", kind = StepKind.QUIZ,
                instruction = "히터를 켰더니 유리가 더 뿌옇게 됐어요. 왜 내기 순환이 아니라 서리 제거일까요?",
                voice = "히터를 켰는데 유리가 더 뿌예졌다면, 어떻게 해야 할까요?",
                quiz = Quiz("유리가 뿌옇게 될 때 가장 먼저 할 일은?", listOf("내기 순환 켜기", "앞유리 서리 제거 켜기", "실내 온도 낮추기"), 1,
                    "서리 제거는 마른 바람을 앞유리로 보내 김을 빨리 걷어 내요. 내기 순환은 실내 습기를 가둬 김이 더 생길 수 있어요."),
            ),
            Step(
                label = "끄는 법", kind = StepKind.INFO,
                instruction = "필요 없을 때는 같은 버튼을 다시 누르면 꺼져요",
                voice = "김이 다 걷히면 같은 버튼을 다시 눌러 꺼 주세요. 안전을 위해 앱이 대신 끄지는 않아요.",
                note = "앞유리 서리 제거는 시야 확보 기능이라 앱이 자동으로 끄지 않아요. 필요 없을 때 차에서 직접 꺼 주세요.",
            ),
        ),
    )

    val driveAssist = Puzzle(
        id = "0-7", feature = FeatureId.DRIVE_ASSIST, chapter = "주행 보조 기능", title = "주행 보조 버튼", safetyFeature = true,
        demoNote = "에뮬레이터: 대기 신호 조회 시연",
        steps = listOf(
            Step(
                label = "대기 만들기", kind = StepKind.VEHICLE_ACTION, goal = Goal.CRUISE_STANDBY,
                instruction = "운전대의 주행 보조 버튼을 눌러 대기 상태로 만들어 주세요",
                voice = "고속도로에 가기 전에 주행 보조 버튼을 알아 둬요. 운전대에서 버튼을 찾아 눌러 볼까요?",
                note = "정차 중이라 실제로 작동하지는 않아요. 대기 상태만 확인해요.",
                hints = listOf("운전대 왼쪽이나 오른쪽 버튼 묶음을 보세요.", "핸들과 속도계 모양이 함께 그려진 버튼이에요."),
            ),
            Step(
                label = "계기판 읽기", kind = StepKind.QUIZ,
                instruction = "대기 상태일 때 계기판 표시는 어떤 색일까요?",
                voice = "계기판을 보세요. 대기 상태는 어떤 색으로 보이나요?",
                quiz = Quiz("대기 상태일 때 계기판의 주행 보조 표시는?", listOf("회색이나 흰색으로 표시돼요", "초록색으로 깜빡여요", "아무것도 안 보여요"), 0,
                    "대기일 때는 회색이나 흰색, 실제로 작동하면 초록색으로 바뀌는 차가 많아요. 차종마다 다를 수 있어요."),
            ),
            Step(
                label = "이유 퀴즈", kind = StepKind.QUIZ,
                instruction = "지금 버튼을 눌렀는데 차가 움직이지 않는 이유는?",
                voice = "버튼을 눌러도 차는 움직이지 않았어요. 왜 그럴까요?",
                quiz = Quiz("정차 중에 주행 보조가 작동하지 않는 이유는?", listOf("고장이라서", "대기는 준비 상태이고, 주행 중에 따로 켜야 작동해서", "기어가 P라서 영원히 못 써서"), 1,
                    "대기는 준비만 해 둔 상태예요. 실제 작동은 주행 중에 설정 버튼으로 켜야 하고, 언제든 브레이크로 해제돼요."),
            ),
            // The SDK has no trustworthy "off" value (the state goes to null or OTHER), so release is confirmed by the player.
            Step(
                label = "대기 해제", kind = StepKind.SCREEN_CONFIRM,
                instruction = "같은 버튼을 다시 눌러 대기를 해제하고, 계기판 표시가 꺼졌는지 확인해 주세요",
                voice = "마지막으로 같은 버튼을 다시 눌러 대기를 꺼 볼까요? 계기판 표시가 꺼졌는지 봐 주세요.",
                note = "차에서 보내는 해제 신호는 확실하지 않아서, 계기판을 직접 확인해요.",
                hints = listOf("아까 누른 버튼을 한 번 더 누르면 돼요."),
            ),
        ),
    )

    val all = listOf(coldMorning, foggyWindshield, driveAssist)

    /** Chapter-0 puzzles and their reviews. */
    fun byId(id: String): Puzzle? = all.firstOrNull { it.id == id } ?: (ReviewPuzzles.all + ReviewPuzzles.variants).firstOrNull { it.id == id }
}

/**
 * Next-day reviews (planning/puzzle-design.md §4.1): the same feature in a different situation, with no step-by-step
 * guidance up front. Succeeding without hints, screen alternatives or a wrong answer counts as "도움 없이 성공".
 */
object ReviewPuzzles {
    val seatVent = Puzzle(
        id = "r-0-2", feature = FeatureId.SEAT_HEAT, chapter = "어제 익힌 기능 복습", title = "더운 오후", safetyFeature = false, review = true,
        steps = listOf(
            Step(
                label = "통풍 켜기", kind = StepKind.VEHICLE_ACTION, goal = Goal.SEAT_VENT_ON,
                instruction = "에어컨을 켰는데 등이 덥대요. 어떤 버튼일까요?",
                voice = "이번엔 여름 오후예요. 에어컨을 켰는데 등이 덥대요. 차에서 직접 해 볼까요?",
                note = "어제 익힌 열선 버튼 근처를 떠올려 보세요. 통풍이 없는 차라면 화면으로 해도 돼요.",
                hints = listOf("어제 누른 시트 버튼 근처에 있어요.", "바람 모양이 그려진 시트 버튼이에요."),
            ),
            Step(
                label = "통풍 끄기", kind = StepKind.VEHICLE_REVERT, goal = Goal.SEAT_VENT_OFF,
                instruction = "확인했어요. 통풍을 꺼 주세요",
                voice = "잘했어요. 통풍을 꺼 볼까요?",
                hints = listOf("같은 버튼을 다시 눌러 보세요."),
            ),
        ),
    )

    val defroster = Puzzle(
        id = "r-0-4", feature = FeatureId.FRONT_DEFROST, chapter = "어제 익힌 기능 복습", title = "히터를 켰더니", safetyFeature = true, review = true,
        steps = listOf(
            Step(
                label = "버튼 고르기", kind = StepKind.VEHICLE_ACTION, goal = Goal.DEFROSTER_ON,
                instruction = "히터를 켰더니 유리가 더 뿌예요. 어떤 버튼을 누를까요?",
                voice = "겨울 아침, 히터를 켰더니 유리가 더 뿌예졌어요. 차에서 직접 눌러 볼까요?",
                hints = listOf("어제 찾은 유리 모양 버튼이에요.", "부채꼴 앞유리에 물결 화살표가 있는 버튼이에요."),
            ),
            Step(
                label = "끄는 법", kind = StepKind.INFO,
                instruction = "김이 걷히면 같은 버튼으로 꺼요",
                voice = "맞아요. 김이 걷히면 같은 버튼으로 끄면 돼요. 안전을 위해 앱이 대신 끄지는 않아요.",
            ),
        ),
    )

    val driveAssist = Puzzle(
        id = "r-0-7", feature = FeatureId.DRIVE_ASSIST, chapter = "어제 익힌 기능 복습", title = "고속도로 진입 전", safetyFeature = true, review = true,
        steps = listOf(
            Step(
                label = "위치 고르기", kind = StepKind.QUIZ,
                instruction = "주행 보조 버튼은 어디에 있었나요?",
                voice = "고속도로에 들어가기 전이에요. 주행 보조 버튼은 어디에 있었을까요?",
                quiz = Quiz("주행 보조 버튼은 어디에 있었나요?", listOf("운전대의 버튼 묶음", "센터 화면의 설정 메뉴", "기어 레버 옆"), 0,
                    "운전대에서 손을 떼지 않고 누를 수 있게 운전대 버튼 묶음에 있어요."),
            ),
        ),
    )

    /**
     * review-safety M7: a car without seat ventilation could only do "더운 오후" on screen, which always counts as help.
     * It gets the same feature in another situation instead: heat on a rainy evening, then off.
     */
    val seatHeatEvening = Puzzle(
        id = "r-0-2b", feature = FeatureId.SEAT_HEAT, chapter = "어제 익힌 기능 복습", title = "비 오는 퇴근길", safetyFeature = false, review = true,
        steps = listOf(
            Step(
                label = "열선 켜기", kind = StepKind.VEHICLE_ACTION, goal = Goal.SEAT_HEAT_ON,
                instruction = "비 오는 퇴근길, 몸이 으슬으슬해요. 어떤 버튼일까요?",
                voice = "이번엔 비 오는 퇴근길이에요. 몸이 으슬으슬하대요. 차에서 직접 해 볼까요?",
                hints = listOf("어제 누른 시트 버튼이에요.", "물결 모양이 그려진 시트 버튼이에요."),
            ),
            Step(
                label = "열선 끄기", kind = StepKind.VEHICLE_REVERT, goal = Goal.SEAT_HEAT_OFF,
                instruction = "확인했어요. 열선을 꺼 주세요",
                voice = "잘했어요. 열선을 꺼 볼까요?",
                hints = listOf("같은 버튼을 다시 눌러 보세요."),
            ),
        ),
    )

    val all = listOf(seatVent, defroster, driveAssist)
    /** Alternatives picked by [forFeature]; listed for lookup by id. */
    val variants = listOf(seatHeatEvening)

    fun forFeature(id: FeatureId, ventilation: Support = Support.UNKNOWN): Puzzle? =
        if (id == FeatureId.SEAT_HEAT && ventilation == Support.UNSUPPORTED) seatHeatEvening
        else all.firstOrNull { it.feature == id }
}
