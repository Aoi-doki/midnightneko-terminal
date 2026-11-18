package dev.aoidoki.yeoldekeys

/** The little slice of a text field the translator needs; the keyboard backs it with an InputConnection. */
interface TextTarget {
    fun textBeforeCursor(max: Int): String
    fun deleteBefore(count: Int)
    fun insert(text: String)
}

/**
 * Rewrites the sentence that is currently being typed, in place, each time a word is finished.
 * Only the part of the sentence that actually changed is deleted and retyped, so the text before it
 * (and the caret) is never disturbed.
 */
object LiveTranslator {
    const val WINDOW = 200
    private const val SENTENCE_ENDS = ".!?\n"

    /** A character that finishes a word: anything that is not a letter, digit or apostrophe. */
    fun isBoundary(c: Char) = !(c.isLetterOrDigit() || c == '\'' || c == '’')

    fun apply(target: TextTarget, atEnd: Boolean) {
        val before = target.textBeforeCursor(WINDOW)
        val lastLetter = before.indexOfLast { it in 'A'..'Z' || it in 'a'..'z' }
        if (lastLetter < 0) return

        var start = 0
        for (i in lastLetter downTo 0) {
            if (before[i] in SENTENCE_ENDS) {
                start = i + 1
                break
            }
        }
        // The window cut a word in half: begin after its first space instead.
        if (start == 0 && before.length >= WINDOW) {
            val space = before.indexOf(' ')
            if (space >= 0) start = space + 1
        }

        val clause = before.substring(start)
        val translated = OldeEnglish.translate(clause, atEnd)
        if (translated == clause) return

        var same = 0
        val limit = minOf(clause.length, translated.length)
        while (same < limit && clause[same] == translated[same]) same++
        if (same > 0 && same < clause.length && Character.isHighSurrogate(clause[same - 1])) same--

        target.deleteBefore(clause.length - same)
        target.insert(translated.substring(same))
    }
}
