package dev.aoidoki.yeoldekeys

/**
 * Turns modern English into formal Early Modern ("Ye Olde") English.
 *
 * The engine is a pure function of its input, so the keyboard can run it again and again over the
 * sentence being typed: every rule is idempotent (already-translated text comes back unchanged),
 * and rules that depend on the *next* word (my -> mine eyes, a -> an abode) settle one word later.
 *
 * Pipeline: contractions are expanded, fixed phrases are swapped, then every word is rewritten
 * with the words around it as context (thou/thee, -est / -eth verb endings, inverted questions).
 */
object OldeEnglish {

    /**
     * @param atEnd true when the writer has finished (Enter pressed): the end of the text then
     *   counts as the end of a sentence, so a lone "no" can become "nay".
     */
    fun translate(input: String, atEnd: Boolean = false): String {
        if (input.isEmpty()) return input
        var s = input.replace('’', '\'')
        s = expandContractions(s)
        for (p in PHRASES) s = p.regex.replace(s, p.rewrite)
        s = rewriteWords(s, atEnd)
        return CANNOT.replace(s) { it.groupValues[1] + "annot" }
    }

    // ----------------------------------------------------------------------------------------
    // 1. Contractions
    // ----------------------------------------------------------------------------------------

    private val NOT_BASES = setOf(
        "do", "does", "did", "is", "are", "was", "were", "have", "has", "had",
        "would", "could", "should", "must", "might", "need", "dare",
    )
    private val PRONOUNS = setOf("i", "you", "he", "she", "it", "we", "they", "that", "who", "there", "this", "what")
    private val NOT_CONTRACTION = Regex("\\b[A-Za-z]+n't\\b", RegexOption.IGNORE_CASE)
    private val CLITIC = Regex("\\b([A-Za-z]+)'(s|d|ll|re|ve|m)\\b", RegexOption.IGNORE_CASE)
    private val SLANG = Regex(
        "\\b(dont|doesnt|didnt|cant|wont|isnt|arent|wasnt|werent|havent|hasnt|hadnt|wouldnt|couldnt|shouldnt|" +
            "im|ive|youre|gonna|wanna|gotta|kinda|lemme|gimme|dunno|idk)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val SLANG_FORMS = mapOf(
        "dont" to "do not", "doesnt" to "does not", "didnt" to "did not", "cant" to "can not",
        "wont" to "will not", "isnt" to "is not", "arent" to "are not", "wasnt" to "was not",
        "werent" to "were not", "havent" to "have not", "hasnt" to "has not", "hadnt" to "had not",
        "wouldnt" to "would not", "couldnt" to "could not", "shouldnt" to "should not",
        "im" to "I am", "ive" to "I have", "youre" to "you are", "gonna" to "going to",
        "wanna" to "wish to", "gotta" to "must", "kinda" to "somewhat", "lemme" to "let me",
        "gimme" to "give me", "dunno" to "know not", "idk" to "I know not",
    )
    private val SHALL_SUBJECTS = setOf("i", "we", "they", "he", "she", "it", "that", "there", "who", "this", "what")
    private val HAVE_SUBJECTS = PRONOUNS + setOf("would", "could", "should", "might", "must")

    private fun expandContractions(text: String): String {
        var s = SLANG.replace(text) { matchCase(it.value, SLANG_FORMS.getValue(it.value.lowercase())) }
        s = NOT_CONTRACTION.replace(s) { m ->
            val low = m.value.lowercase()
            val aux = when (low) {
                "can't" -> "can"
                "won't" -> "will"
                "shan't" -> "shall"
                "ain't" -> "is"
                else -> low.dropLast(3)
            }
            if (aux !in NOT_BASES && low !in setOf("can't", "won't", "shan't", "ain't")) {
                m.value
            } else {
                val head = if (low in setOf("can't", "won't", "shan't", "ain't")) matchCase(m.value, aux) else m.value.dropLast(3)
                head + " " + cased(m.value, "not")
            }
        }
        return CLITIC.replace(s) { m ->
            val head = m.groupValues[1]
            val low = head.lowercase()
            val word: String? = when (m.groupValues[2].lowercase()) {
                "s" -> when {
                    low == "let" -> "us"
                    low in PRONOUNS || low in setOf("here", "where", "how") -> "is"
                    else -> null
                }
                "m" -> if (low == "i") "am" else null
                "re" -> if (low in setOf("you", "we", "they", "what", "who", "where")) "are" else null
                "ve" -> if (low in HAVE_SUBJECTS) "have" else null
                "ll" -> when {
                    low == "you" -> "will"
                    low in SHALL_SUBJECTS -> "shall"
                    else -> null
                }
                "d" -> if (low in PRONOUNS) "would" else null
                else -> null
            }
            if (word == null) m.value else head + " " + cased(m.value, word)
        }
    }

    /** [word] in the same letter case as [src]: all caps stays all caps, anything else is lower. */
    private fun cased(src: String, word: String): String {
        val letters = src.filter { it.isLetter() }
        return if (letters.length > 1 && letters.all { it.isUpperCase() }) word.uppercase() else word
    }

    // ----------------------------------------------------------------------------------------
    // 2. Fixed phrases
    // ----------------------------------------------------------------------------------------

    private class Phrase(pattern: String, val rewrite: (MatchResult) -> CharSequence) {
        val regex = Regex("(?<![A-Za-z])(?:$pattern)(?![A-Za-z])", RegexOption.IGNORE_CASE)
    }

    private fun fixed(pattern: String, replacement: String) = Phrase(pattern) { matchCase(it.value, replacement) }

    private const val CLAUSE_END = "(?=\\s*[?!.,]|\\s*$)"

    private val PHRASES = listOf(
        fixed("oh my (?:god|gosh|goodness)|omg", "good heavens"),
        Phrase("(?:what is up|whats up)$CLAUSE_END|wassup|wazzup") { matchCase(it.value, "what news") },
        fixed("good morning", "good morrow"),
        fixed("good evening", "good even"),
        fixed("good afternoon", "good den"),
        fixed("see you (?:later|soon)|see ya|catch you later", "until we meet again"),
        fixed("good ?bye|bye ?bye|bye|cya", "fare thee well"),
        fixed("(?:hello|hi|hey) there", "well met"),
        fixed("you guys|you all|you lot|y'all|yall", "ye"),
        fixed("right now|at once|asap", "forthwith"),
        fixed("go away|get lost|scram", "begone"),
        fixed("shut up|be quiet", "hold thy peace"),
        fixed("hurry up", "make haste"),
        Phrase("(?:very|so) much$CLAUSE_END") { matchCase(it.value, "most heartily") },
        Phrase("(i|we|they) do not (know|care|fear|doubt)") { m ->
            "${m.groupValues[1]} ${m.groupValues[2]} not"
        },
        Phrase("it (is|was)") { m -> matchCase(m.value, if (m.groupValues[1].lowercase() == "is") "'tis" else "'twas") },
    )

    private val CANNOT = Regex("\\b([Cc])an not\\b")

    // ----------------------------------------------------------------------------------------
    // 3. Word by word
    // ----------------------------------------------------------------------------------------

    private val WORD_OR_GAP = Regex("[A-Za-z]+(?:'[A-Za-z]+)*|[^A-Za-z]+")

    private class Tok(val text: String, val isWord: Boolean) {
        var out: String = text
    }

    private fun rewriteWords(text: String, atEnd: Boolean): String {
        val toks = WORD_OR_GAP.findAll(text).map { Tok(it.value, it.value[0].isLetter()) }.toList()
        val words = toks.indices.filter { toks[it].isWord }

        // A neighbour only counts as context when just blank space lies between the two words:
        // a comma or a quote ends the thought.
        fun adjacent(k: Int): Boolean =
            k >= 0 && k + 1 < words.size && words[k] + 2 == words[k + 1] && toks[words[k] + 1].text.isBlank()

        fun lastWord(out: String) = out.substringAfterLast(' ').lowercase()

        for ((k, wi) in words.withIndex()) {
            val tok = toks[wi]
            val prev = if (adjacent(k - 1)) lastWord(toks[words[k - 1]].out) else null
            val prev2 = if (prev != null && adjacent(k - 2)) lastWord(toks[words[k - 2]].out) else null
            val next = if (adjacent(k)) toks[words[k + 1]].text.lowercase() else null
            val punctFollows = (wi + 1 < toks.size && !toks[wi + 1].isWord && toks[wi + 1].text[0] in ",.!?;:") ||
                (atEnd && wi == toks.lastIndex)
            val rep = rewrite(tok.text.lowercase(), prev, prev2, next, punctFollows)
            tok.out = if (rep == null) tok.text else matchCase(tok.text, rep)
        }

        // Settle the words that depend on how the *following* word ended up.
        for ((k, wi) in words.withIndex()) {
            if (!adjacent(k)) continue
            val tok = toks[wi]
            val nextTok = toks[words[k + 1]]
            val nextOut = nextTok.out.lowercase()
            when (tok.text.lowercase()) {
                "a", "an" -> if (nextTok.out != nextTok.text) {
                    tok.out = matchCase(tok.text, if (vowelSound(nextOut)) "an" else "a")
                }
                "my" -> if (startsWithVowel(nextOut)) tok.out = matchCase(tok.text, "mine")
                "your", "thy" -> tok.out = matchCase(tok.text, if (startsWithVowel(nextOut)) "thine" else "thy")
            }
        }
        return toks.joinToString("") { it.out }
    }

    private const val VOWELS = "aeiou"

    private fun startsWithVowel(w: String) = w.isNotEmpty() && w[0] in VOWELS

    private fun vowelSound(w: String): Boolean {
        val f = w.firstOrNull() ?: return false
        if (f in VOWELS) {
            return listOf("uni", "use", "usu", "eu", "one", "once").none { w.startsWith(it) }
        }
        return listOf("hour", "honest", "heir", "honor").any { w.startsWith(it) }
    }

    private fun rewrite(w: String, prev: String?, prev2: String?, next: String?, punct: Boolean): String? {
        when (w) {
            "i" -> return "I"
            "you", "ya", "u" -> return if (prev != null && prev in OBJECT_TRIGGERS) "thee" else "thou"
            "your", "ur" -> return "thy"
            "yours" -> return "thine"
            "yourself" -> return "thyself"
            "no" -> return if (punct) "nay" else null
            "to" -> return if (next != null && next in UNTO_BEFORE) "unto" else null
            "here" -> return if (prev in HITHER_AFTER) "hither" else null
            "there" -> return if (prev in YONDER_AFTER) "yonder" else null
        }

        val afterThou = prev == "thou" && prev2 !in AUX_OLDE
        val inverted = prev != "thou" && next in YOU_FORMS && (prev == null || prev !in SUBJECT_BLOCKERS)

        AUX_THOU[w]?.let { if (afterThou || inverted) return it }

        if (afterThou) {
            if (w in VERBS) return estForm(w)
            if (w.length >= 5 && w.endsWith("ed") && !w.endsWith("eed") && w !in NOT_PAST) return w + "st"
        }

        if (prev in THIRD_SUBJECTS && w.endsWith("s")) {
            if (w == "says") return "saith"
            val bases = buildList {
                if (w.endsWith("ies")) add(w.dropLast(3) + "y")
                if (w.endsWith("es")) add(w.dropLast(2))
                add(w.dropLast(1))
            }
            bases.firstOrNull { it in VERBS }?.let { return ethForm(it) }
        }

        return VOCAB[w]
    }

    private fun estForm(v: String): String = when {
        v in DOUBLING -> v + v.last() + "est"
        v.endsWith("e") -> v + "st"
        v.length > 1 && v.endsWith("y") && v[v.length - 2] !in VOWELS -> v.dropLast(1) + "iest"
        else -> v + "est"
    }

    private fun ethForm(v: String): String = when {
        v in DOUBLING -> v + v.last() + "eth"
        v.endsWith("e") -> v + "th"
        v.length > 1 && v.endsWith("y") && v[v.length - 2] !in VOWELS -> v.dropLast(1) + "ieth"
        else -> v + "eth"
    }

    /** [rep] dressed in the capitalisation of [src] ("You" -> "Thou", "YOU" -> "THOU"). */
    private fun matchCase(src: String, rep: String): String {
        if (rep.isEmpty() || src.isEmpty()) return rep
        val letters = src.filter { it.isLetter() }
        if (letters.length > 1 && letters.all { it.isUpperCase() }) return rep.uppercase()
        if (src.first().isUpperCase()) {
            val i = rep.indexOfFirst { it.isLetter() }
            if (i >= 0) return rep.substring(0, i) + rep[i].uppercaseChar() + rep.substring(i + 1)
        }
        return rep
    }

    // ----------------------------------------------------------------------------------------
    // Word lists
    // ----------------------------------------------------------------------------------------

    private val YOU_FORMS = setOf("you", "ya", "u", "thou")

    /** Before one of these, "are"/"have"/"were" belong to someone else, so the question isn't inverted. */
    private val SUBJECT_BLOCKERS = setOf("i", "we", "they", "he", "she", "it", "thou", "ye")

    /** After these, "you" is the object of the sentence, and becomes "thee". */
    private val OBJECT_TRIGGERS = setOf(
        "for", "with", "without", "to", "unto", "of", "from", "by", "at", "in", "on", "upon", "about", "like",
        "than", "as", "among", "near", "beside", "behind", "after", "before", "ere", "through", "toward",
        "towards", "against", "around", "except", "save", "love", "tell", "told", "thank", "give", "gave",
        "see", "saw", "help", "miss", "need", "want", "ask", "show", "warn", "bless", "beseech", "hate",
        "trust", "meet", "call", "hear", "heard", "find", "found", "bring", "send", "take", "make", "let",
        "have", "hath", "had", "know",
    )

    private val UNTO_BEFORE = setOf(
        "me", "him", "her", "us", "them", "thee", "you", "ye", "my", "mine", "thy", "your", "his", "their",
        "our", "the", "a", "an",
    )

    private val HITHER_AFTER = setOf("come", "came", "comes", "coming", "bring", "brought", "hurry")
    private val YONDER_AFTER = setOf("o'er", "look", "looking", "go", "went", "going", "walk")

    private val THIRD_SUBJECTS = setOf(
        "he", "she", "it", "who", "whoever", "everyone", "everybody", "nobody", "someone", "somebody", "god",
    )

    /** Auxiliaries: the form they take next to "thou" ("thou art", "dost thou"). */
    private val AUX_THOU = mapOf(
        "are" to "art", "were" to "wert", "was" to "wast", "have" to "hast", "had" to "hadst",
        "do" to "dost", "did" to "didst", "will" to "wilt", "shall" to "shalt", "can" to "canst",
        "would" to "wouldst", "could" to "couldst", "should" to "shouldst", "may" to "mayst",
        "might" to "mightst",
    )

    /** A verb after one of these is a bare infinitive ("dost thou know"), not "knowest". */
    private val AUX_OLDE = AUX_THOU.values.toSet() + AUX_THOU.keys + setOf("must")

    private val NOT_PAST = setOf("hundred", "indeed", "naked", "sacred", "wicked", "kindred", "hatred")

    /** Short verbs whose last consonant doubles: get -> gettest / getteth. */
    private val DOUBLING = setOf("get", "run", "sit", "put", "let", "win", "begin", "forget", "stop", "plan", "beg", "hit", "cut", "shut")

    private val VERBS = (
        "know love want think see go make take say come give need like feel look get mean tell ask try hear seek " +
            "speak find keep leave bring seem live believe hope wish hate wait play run sing write read eat drink " +
            "sleep dream fight fear work remember understand forget begin die stand sit walk talk call turn use " +
            "help show hold let put meet pay send spend win lose learn care miss trust laugh cry smile wear buy " +
            "sell build break bear hurt change move stay follow serve praise bless curse pray beg allow appear " +
            "remain become reach watch listen ride rise fall grow lead hide kill save steal throw wonder worry " +
            "enjoy expect order offer open close start stop finish carry dance answer"
        ).split(' ').toSet()

    private val VOCAB = mapOf(
        // greetings & replies
        "hello" to "hail", "hi" to "hail", "hey" to "hail", "heya" to "hail", "yo" to "hail",
        "yes" to "aye", "yeah" to "aye", "yep" to "aye", "yup" to "aye", "nope" to "nay", "nah" to "nay",
        "ok" to "very well", "okay" to "very well", "maybe" to "perchance", "perhaps" to "perchance",
        "please" to "prithee", "pls" to "prithee", "plz" to "prithee",
        "thanks" to "gramercy", "thx" to "gramercy", "ty" to "gramercy",
        // small words
        "before" to "ere", "over" to "o'er", "never" to "ne'er", "ever" to "e'er", "between" to "betwixt",
        "often" to "oft", "soon" to "anon", "quickly" to "apace", "why" to "wherefore", "really" to "verily",
        "truly" to "verily", "very" to "most", "actually" to "in truth", "obviously" to "plainly",
        "definitely" to "assuredly", "absolutely" to "assuredly", "probably" to "belike", "also" to "likewise",
        "almost" to "well-nigh", "has" to "hath", "does" to "doth", "stop" to "cease",
        // time
        "today" to "this day", "tonight" to "this night", "tomorrow" to "on the morrow", "morning" to "morn",
        "evening" to "eventide", "everything" to "all things", "nothing" to "naught", "anything" to "aught",
        // people
        "dude" to "good sir", "bro" to "good sir", "sis" to "sister", "mom" to "mother", "mommy" to "mother",
        "mama" to "mother", "mum" to "mother", "dad" to "father", "daddy" to "father", "papa" to "father",
        "kid" to "child", "kids" to "children", "baby" to "babe", "girl" to "maiden", "girls" to "maidens",
        "boy" to "lad", "boys" to "lads", "guy" to "fellow", "guys" to "fellows", "boss" to "liege",
        "police" to "watchmen", "doctor" to "physician", "idiot" to "fool",
        // things
        "money" to "coin", "cash" to "coin", "food" to "victuals", "beer" to "ale", "house" to "abode",
        "car" to "carriage", "cars" to "carriages", "phone" to "speaking-stone", "computer" to "thinking-engine",
        "dog" to "hound", "horse" to "steed", "message" to "missive", "email" to "missive",
        "bathroom" to "privy", "toilet" to "privy", "party" to "revelry",
        // feelings & looks
        "beautiful" to "fair", "pretty" to "comely", "cute" to "winsome", "great" to "splendid",
        "awesome" to "wondrous", "amazing" to "wondrous", "bad" to "foul", "terrible" to "dreadful",
        "stupid" to "foolish", "crazy" to "mad", "scared" to "afeard", "tired" to "weary", "angry" to "wroth",
        "happy" to "merry", "sad" to "woeful", "sleepy" to "drowsy",
        // chat
        "wow" to "zounds", "lol" to "ha ha ha", "lmao" to "ha ha ha", "haha" to "ha ha ha", "hahaha" to "ha ha ha",
        "brb" to "I shall return anon", "btw" to "by the by", "tbh" to "in truth",
    )
}
