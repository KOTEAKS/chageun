package com.letsgo.chageun.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** review-safety M11: one gentle offer of help after a minute on the same step. */
class HelpOfferTest {
    @Test fun `help is offered after a minute on one step`() {
        assertFalse(HelpOffer.due(59_999, alreadyOffered = false, hintLevel = 0, readingAnswer = false))
        assertTrue(HelpOffer.due(60_000, alreadyOffered = false, hintLevel = 0, readingAnswer = false))
    }

    @Test fun `only once, not after a hint, not while reading a quiz explanation`() {
        assertFalse(HelpOffer.due(90_000, alreadyOffered = true, hintLevel = 0, readingAnswer = false))
        assertFalse(HelpOffer.due(90_000, alreadyOffered = false, hintLevel = 1, readingAnswer = false))
        assertFalse(HelpOffer.due(90_000, alreadyOffered = false, hintLevel = 0, readingAnswer = true))
    }
}
