package dev.aoidoki.yeoldekeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranslatorTest {

    /** A text field that records how much of it was rewritten, like a real InputConnection would. */
    private class Field : TextTarget {
        val text = StringBuilder()
        var deleted = 0
        override fun textBeforeCursor(max: Int) = text.takeLast(max).toString()
        override fun deleteBefore(count: Int) {
            deleted += count
            text.setLength(text.length - count)
        }
        override fun insert(text: String) {
            this.text.append(text)
        }
    }

    /** Types like the keyboard does: translate whenever a word is finished. */
    private fun typeInto(field: Field, s: String) {
        for (c in s) {
            field.insert(c.toString())
            if (LiveTranslator.isBoundary(c)) LiveTranslator.apply(field, atEnd = false)
        }
    }

    @Test fun wordsAreTranslatedAsTheyAreFinished() {
        val f = Field()
        typeInto(f, "hello ")
        assertEquals("hail ", f.text.toString())
        typeInto(f, "friend")
        assertEquals("hail friend", f.text.toString())
    }

    @Test fun invertedQuestionIsFixedOnceYouArrives() {
        val f = Field()
        typeInto(f, "do you know ")
        assertEquals("dost thou know ", f.text.toString())
    }

    @Test fun lookAheadRulesSettleOneWordLater() {
        val f = Field()
        typeInto(f, "my ")
        assertEquals("my ", f.text.toString())
        typeInto(f, "eyes ")
        assertEquals("mine eyes ", f.text.toString())

        val g = Field()
        typeInto(g, "your ")
        assertEquals("thy ", g.text.toString())
        typeInto(g, "eyes ")
        assertEquals("thine eyes ", g.text.toString())
    }

    @Test fun apostropheDoesNotEndAWord() {
        val f = Field()
        typeInto(f, "don't ")
        assertEquals("do not ", f.text.toString())
        typeInto(f, "you ")
        assertEquals("do not thou ", f.text.toString())
    }

    @Test fun sentencesAreIndependent() {
        val f = Field()
        typeInto(f, "Hello. You are great! ")
        assertEquals("Hail. Thou art splendid! ", f.text.toString())
    }

    @Test fun nayWaitsForPunctuation() {
        val f = Field()
        typeInto(f, "no ")
        typeInto(f, "problem")
        assertEquals("no problem", f.text.toString())

        val g = Field()
        typeInto(g, "no!")
        assertEquals("nay!", g.text.toString())
    }

    @Test fun onlyTheChangedTailIsRewritten() {
        val f = Field()
        typeInto(f, "A long sentence that is already settled, and now ")
        val before = f.deleted
        typeInto(f, "you ")
        // "you " -> "thou ": at most the new word and the space are touched
        assertTrue(f.deleted - before <= 8)
        assertEquals("A long sentence that is already settled, and now thou ", f.text.toString())
    }

    @Test fun finalPassOnEnterTranslatesTheLastWord() {
        val f = Field()
        typeInto(f, "tell me no")
        LiveTranslator.apply(f, atEnd = true)
        assertEquals("tell me nay", f.text.toString())
    }

    @Test fun nothingToDoWithoutLetters() {
        val f = Field()
        typeInto(f, "12 + 3 = 15 ")
        assertEquals("12 + 3 = 15 ", f.text.toString())
        assertEquals(0, f.deleted)
    }

    @Test fun longTextWithoutPunctuationStaysConsistent() {
        val f = Field()
        repeat(60) { typeInto(f, "you are my friend ") }
        val words = f.text.toString().trim().split(' ')
        assertEquals(60 * 4, words.size)
        assertTrue(words.chunked(4).all { it == listOf("thou", "art", "my", "friend") })
    }
}
