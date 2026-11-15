package com.letsgo.chageun.core

/**
 * review-safety-v0.1 M11 (design 3.4): someone stuck on one step who has not found the hint button is offered help
 * once, on screen and by voice. Not while reading the explanation of an answered quiz.
 */
object HelpOffer {
    const val AFTER_MILLIS = 60_000L
    const val LINE = "막혔다면 '힌트'를 눌러 보세요. '화면으로 하기'나 '건너뛰기'로 넘어가도 괜찮아요."

    fun due(onStepMillis: Long, alreadyOffered: Boolean, hintLevel: Int, readingAnswer: Boolean): Boolean =
        !alreadyOffered && hintLevel == 0 && !readingAnswer && onStepMillis >= AFTER_MILLIS
}
