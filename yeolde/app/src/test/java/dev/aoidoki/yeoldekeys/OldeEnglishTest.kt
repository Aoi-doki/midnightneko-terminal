package dev.aoidoki.yeoldekeys

import org.junit.Assert.assertEquals
import org.junit.Test

class OldeEnglishTest {

    private fun t(s: String, atEnd: Boolean = true) = OldeEnglish.translate(s, atEnd)

    @Test fun greetings() {
        assertEquals("Hail, friend!", t("Hello, friend!"))
        assertEquals("Aye!", t("Yes!"))
        assertEquals("Nay.", t("No."))
        assertEquals("Prithee help me", t("Please help me"))
        assertEquals("Gramercy", t("Thanks"))
        assertEquals("Good morrow!", t("Good morning!"))
        assertEquals("Fare thee well", t("Goodbye"))
    }

    @Test fun youBecomesThouOrThee() {
        assertEquals("Thou art my friend.", t("You are my friend."))
        assertEquals("I love thee", t("I love you"))
        assertEquals("with thee", t("with you"))
        assertEquals("I shall see thee on the morrow", t("I'll see you tomorrow"))
        assertEquals("Hail, how art thou?", t("Hello, how are you?"))
        assertEquals("Where art thou going?", t("Where are you going?"))
        assertEquals("Blessed be unto ye", t("Blessed be to you all"))
    }

    @Test fun invertedQuestions() {
        assertEquals("Dost thou know?", t("Do you know?"))
        assertEquals("What dost thou want?", t("What do you want?"))
        assertEquals("Canst thou help me?", t("Can you help me?"))
        assertEquals("Dost thou have a carriage?", t("Do you have a car?"))
        assertEquals("Wilt thou come?", t("Will you come?"))
    }

    @Test fun verbEndingsAfterThou() {
        assertEquals("Thou hast an abode", t("You have a house"))
        assertEquals("I think thou knowest", t("I think you know"))
        assertEquals("If thou wantest it", t("If you want it"))
        assertEquals("Thou lovedst", t("You loved"))
        assertEquals("Thou dost not know", t("You don't know"))
        assertEquals("Thou canst not", t("You can't"))
    }

    @Test fun thirdPersonEth() {
        assertEquals("he goeth unto the store", t("he goes to the store"))
        assertEquals("she loveth", t("she loves"))
        assertEquals("it hath", t("it has"))
        assertEquals("he saith", t("he says"))
        assertEquals("it getteth", t("it gets"))
    }

    @Test fun possessivesAndArticles() {
        assertEquals("Thine eyes", t("Your eyes"))
        assertEquals("thy friend", t("your friend"))
        assertEquals("mine apple", t("my apple"))
        assertEquals("my friend", t("my friend"))
        assertEquals("an abode", t("a house"))
        assertEquals("a fair day", t("a beautiful day"))
        assertEquals("thine", t("yours"))
    }

    @Test fun contractions() {
        assertEquals("I cannot wait", t("I can't wait"))
        assertEquals("'Tis a fair day", t("It's a beautiful day"))
        assertEquals("I know not", t("I don't know"))
        assertEquals("I do not think thou understandest", t("I don't think you understand"))
        assertEquals("I am here", t("I'm here"))
        assertEquals("let us go", t("let's go"))
        assertEquals("I shall", t("I'll"))
        assertEquals("'twas", t("it was"))
    }

    @Test fun nayOnlyWhenStandingAlone() {
        assertEquals("no problem", t("no problem"))
        assertEquals("nay, thank thee", t("no, thank you"))
        assertEquals("nay", t("no", atEnd = true))
        assertEquals("no", t("no", atEnd = false))
    }

    @Test fun keepsCapitalisation() {
        assertEquals("THOU ART", t("YOU ARE"))
        assertEquals("Thou", t("You"))
        assertEquals("I", t("i"))
    }

    @Test fun leavesOtherTextAlone() {
        assertEquals("The quick brown fox jumps over 12 lazy dogs".replace("over", "o'er").replace("dogs", "dogs"), t("The quick brown fox jumps over 12 lazy dogs"))
        assertEquals("", t(""))
        assertEquals("   ", t("   "))
        assertEquals("12:30 pm", t("12:30 pm"))
    }

    @Test fun translatingTwiceChangesNothing() {
        val samples = listOf(
            "Hello, how are you? Do you know where your house is?",
            "I'll see you tomorrow, my friend. It's a beautiful day!",
            "He goes to the store and she loves it. Can you help me?",
            "You don't know what you're doing, and your eyes are great.",
            "Thanks so much. Yes! No. Maybe. Please, I don't know.",
            "What's up? Why are you so happy? Did you eat my food?",
        )
        for (s in samples) {
            val once = t(s)
            assertEquals("not idempotent for: $s", once, t(once))
        }
    }
}
