package com.letsgo.chageun.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** review-safety M8: a quiz result is spoken, not only shown in colour. */
class QuizVoiceTest {
    private val quiz = Quiz("등이 더울 때는?", listOf("시트 열선", "시트 통풍", "앞유리 서리 제거"), answer = 1,
        explanation = "통풍은 시트 안쪽으로 바람을 보내요.")

    @Test fun `a right answer says so, then the explanation`() {
        assertEquals("맞았어요. 통풍은 시트 안쪽으로 바람을 보내요.", quiz.resultLine(1, detailed = true))
    }

    @Test fun `a wrong answer names the right one in its own sentence`() {
        assertEquals("아쉬워요. 정답은 이거예요. 시트 통풍. 통풍은 시트 안쪽으로 바람을 보내요.", quiz.resultLine(0, detailed = true))
    }

    @Test fun `the short setting speaks only the result`() {
        assertEquals("맞았어요.", quiz.resultLine(1, detailed = false))
        assertEquals("아쉬워요. 정답은 이거예요. 시트 통풍.", quiz.resultLine(2, detailed = false))
    }

    @Test fun `an option ending in a full stop is not doubled`() {
        val q = quiz.copy(options = listOf("가.", "나."), answer = 1)
        assertEquals("아쉬워요. 정답은 이거예요. 나.", q.resultLine(0, detailed = false))
    }
}
