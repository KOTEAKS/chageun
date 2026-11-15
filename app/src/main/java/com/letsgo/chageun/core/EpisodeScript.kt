package com.letsgo.chageun.core

/** Episode 1 lines and hints. Hints go direction → concrete clue → answer (planning/puzzle-design.md §3.4). */
object EpisodeScript {
    const val TITLE = "지하 4층 정전"
    const val CHAPTER = "선택 콘텐츠 · 방탈출"

    fun voice(v: EpisodeView): String = when (v.stage) {
        EpisodeStage.SIGN -> when {
            v.awaitingFinalOff -> "맞았어요! 마지막으로 방향지시등을 꺼 주세요."
            v.onScreen -> "정전된 지하 4층이에요. 안내판이 깜빡이는 순서를 화면의 왼쪽, 오른쪽 버튼으로 따라 해 볼까요?" +
                if (v.difficulty == Difficulty.EASY) " 긴 막대는 오른쪽, 짧은 막대는 왼쪽이에요." else ""
            v.difficulty == Difficulty.EASY -> "정전된 지하 4층이에요. 방향지시등은 운전석의 어른이 맡아 주세요. 저 안내판이 길게, 짧게 깜빡여요. 긴 막대는 오른쪽, 짧은 막대는 왼쪽. 따라 해 볼까요?"
            else -> "정전된 지하 4층이에요. 안내판이 깜빡이는 순서를 방향지시등으로 따라 해 볼까요?"
        }
        EpisodeStage.CODE -> "${clue(v, 0).where}에 첫 번째 빛 단서가 생겼어요. 이제 금고예요. 번호는 이 차만 알고 있대요."
        // review-safety M9: in the family mode the voice also says what the dial picture means (the code is already found).
        EpisodeStage.DIAL -> when {
            v.onScreen && v.difficulty == Difficulty.EASY ->
                "번호를 찾았어요. 화면의 다이얼을 왼쪽으로 ${v.code.first}칸, 그다음 오른쪽으로 ${v.code.second}칸 맞춰 볼까요?"
            v.onScreen -> "번호를 찾았어요. 화면의 다이얼을 왼쪽부터 맞춰 볼까요?"
            v.difficulty == Difficulty.EASY -> "번호를 찾았어요. 이 금고는 핸들이 다이얼이에요. 번호 한 칸이 핸들 10도예요. " +
                "왼쪽으로 ${v.code.first}칸 돌려 잠깐 멈추고, 그다음 오른쪽으로 ${v.code.second}칸. 천천히 돌려 볼까요?"
            else -> "번호를 찾았어요. 이 금고는 핸들이 다이얼이에요. 천천히 돌려 볼까요?"
        }
        EpisodeStage.CENTER -> "철컥, 금고가 열렸어요! 핸들을 가운데로 돌려 주세요."
        EpisodeStage.BOILER -> "${clue(v, 1).where}에 두 번째 빛 단서가 생겼어요. 금고 안에 보일러 기록계가 있네요. 계단 그림이 숫자를 숨기고 있어요." +
            // M9: the legend is only drawn on screen; say it in the family mode, but leave the counting to the players.
            if (v.difficulty == Difficulty.EASY) " 계단 한 칸이 1도, 가운데 참은 반 칸이라 0.5도예요. 계단을 세어 볼까요?" else ""
        EpisodeStage.TEMP_BACK -> "보일러가 돌기 시작했어요! 바꾼 온도는 원래대로 돌려 둘까요?"
        EpisodeStage.LIGHTS -> "세 번째 빛 단서는 ${clue(v, 2).where}이에요. 마지막 문은 얻은 불빛을 거꾸로 " +
            (if (v.onScreen) "눌러야 열려요." else "켜야 열려요.") +
            if (v.difficulty == Difficulty.EASY && !v.onScreen) " 뒷자리 독서등은 아이가 맡아 주세요." else ""
        EpisodeStage.LIGHTS_OFF -> "문이 열렸어요. 탈출 성공! 켠 불은 모두 꺼 주세요."
        null -> "지하 4층에서 탈출했어요. 수고했어요!"
    }

    fun instruction(v: EpisodeView): String = when (v.stage) {
        EpisodeStage.SIGN -> when {
            v.awaitingFinalOff -> "맞았어요! 방향지시등을 꺼 주세요"
            v.onScreen -> "깜빡이는 안내판을 왼쪽·오른쪽 버튼으로 따라 해 보세요"
            else -> "깜빡이는 안내판을 방향지시등으로 따라 해 보세요"
        }
        EpisodeStage.CODE -> "금고 번호 두 자리를 찾아 넣어 주세요"
        EpisodeStage.DIAL -> when {
            v.difficulty == Difficulty.NORMAL -> "금고 번호 ${v.code.first} · ${v.code.second}. 왼쪽부터 다이얼을 맞춰 주세요"
            v.onScreen -> "다이얼을 왼쪽 ${v.code.first}칸, 그다음 오른쪽 ${v.code.second}칸에 맞춰 주세요"
            else -> "핸들을 왼쪽 ${v.code.first * 10}°, 그다음 오른쪽 ${v.code.second * 10}°로 돌려 잠깐씩 멈춰 주세요"
        }
        EpisodeStage.CENTER -> "핸들을 가운데로 돌려 주세요"
        EpisodeStage.BOILER -> "운전석 온도를 기록계의 숫자에 맞춰 주세요"
        EpisodeStage.TEMP_BACK -> v.originalTemp?.let { "운전석 온도를 원래대로(${fmt(it)}°C) 돌려 주세요" } ?: "운전석 온도를 평소대로 돌려 주세요"
        EpisodeStage.LIGHTS -> if (v.onScreen) "얻은 불빛을 거꾸로 눌러 주세요" else "얻은 불빛을 거꾸로 켜 주세요"
        EpisodeStage.LIGHTS_OFF -> "탈출 성공! 켠 불을 모두 꺼 주세요"
        null -> ""
    }

    /** Where the code is. Never says the odometer value itself. */
    fun codeClue(v: EpisodeView): String = when {
        !v.codeFromCar -> "기둥에 번호가 적혀 있어요: B4-37"
        v.difficulty == Difficulty.EASY -> "계기판의 누적 주행거리(ODO), 그 마지막 두 자리가 금고 번호예요."
        else -> "이 차가 지금까지 달려온 길, 그 끝의 두 숫자."
    }

    fun boilerLegend(v: EpisodeView): String =
        if (v.difficulty == Difficulty.EASY) "계단 한 칸 = 1°C · 중간 참 = 0.5°C" else "계단 한 칸 = 1°C"

    fun hints(v: EpisodeView): List<String> = when (v.stage) {
        EpisodeStage.SIGN -> listOf(
            "긴 막대가 오른쪽, 짧은 막대가 왼쪽이에요.",
            "같은 방향을 두 번 할 때는 사이에 한 번 꺼야 해요.",
            "정답은 오른쪽, 왼쪽, 왼쪽, 오른쪽이에요.",
        )
        EpisodeStage.CODE -> if (v.codeFromCar) listOf(
            "계기판을 살펴보세요.",
            "누적 주행거리(ODO)를 찾아보세요. 트립 거리가 아니에요.",
            "누적 주행거리의 마지막 두 자리를 차례대로 넣어요.",
        ) else listOf("기둥에 적힌 번호를 보세요.", "B4 다음의 두 숫자예요.")
        EpisodeStage.DIAL -> listOf(
            "눈금 한 칸은 10°예요.",
            "첫 번째 숫자만큼 왼쪽, 두 번째 숫자만큼 오른쪽이에요.",
            "왼쪽 ${v.code.first * 10}°, 오른쪽 ${v.code.second * 10}°에서 1.5초씩 멈춰요.",
        )
        EpisodeStage.CENTER -> listOf("핸들을 똑바로 두면 돼요.")
        EpisodeStage.BOILER -> listOf("계단을 세어 보세요.", "중간 참은 반 칸이에요.", "21.5°C예요. 공조의 운전석 온도를 맞춰요.")
        EpisodeStage.TEMP_BACK -> listOf("공조에서 운전석 온도를 처음 값으로 바꿔요.")
        EpisodeStage.LIGHTS -> {
            val order = Episode1.lightAnswer(v.difficulty)
            listOf(
                "단서를 마지막 것부터 켜요. 먼저 세 불을 모두 꺼 두세요.",
                "3번 → 2번 → 1번 순서예요.",
                order.joinToString(" → ") { it.label } + " 순서로 켜요.",
            )
        }
        EpisodeStage.LIGHTS_OFF -> listOf("켠 독서등과 실내등을 다시 눌러 꺼요.")
        null -> emptyList()
    }

    /**
     * Who does a car task in family mode (planning/puzzle-design.md §3.7): the adult in the driver's seat handles the
     * wheel, the lever and the climate; a child only its own seat light and the screen clues.
     */
    fun role(v: EpisodeView): String {
        if (v.difficulty != Difficulty.EASY || v.onScreen) return ""
        return when (v.stage) {
            EpisodeStage.SIGN, EpisodeStage.DIAL, EpisodeStage.CENTER, EpisodeStage.BOILER, EpisodeStage.TEMP_BACK -> " · 운전석 어른"
            EpisodeStage.LIGHTS, EpisodeStage.LIGHTS_OFF -> " · 뒷자리 독서등은 아이"
            else -> ""
        }
    }

    /** Turn signals are a message to other road users, so the sign puzzle says where not to play it with the lever. */
    const val ROADSIDE_NOTE = "차가 다니는 길가라면 방향지시등 대신 '화면으로 하기'를 써 주세요. 다른 차가 출발 신호로 볼 수 있어요."

    private fun clue(v: EpisodeView, i: Int): LightSpot = Episode1.clueLights(v.difficulty)[i]

    fun fmt(c: Float): String = if (c % 1f == 0f) c.toInt().toString() else c.toString()
}
