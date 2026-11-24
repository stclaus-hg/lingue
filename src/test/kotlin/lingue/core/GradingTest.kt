package lingue.core

import io.github.openspacedrepetition.Rating
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val QUICK: Duration = Duration.ofSeconds(2)
private val SLOW: Duration = Duration.ofSeconds(30)

class GradingChoiceTest {

    @Test
    fun `wrong pick resets the card`() {
        assertEquals(Rating.AGAIN, Grading.forChoice(correct = false, elapsed = QUICK))
    }

    @Test
    fun `correct pick is GOOD`() {
        assertEquals(Rating.GOOD, Grading.forChoice(correct = true, elapsed = QUICK))
    }

    @Test
    fun `a slow correct pick is only HARD`() {
        assertEquals(Rating.HARD, Grading.forChoice(correct = true, elapsed = SLOW))
    }

    @Test
    fun `recognition never earns EASY`() {
        val ratings = listOf(QUICK, Duration.ofMillis(400), SLOW)
            .map { Grading.forChoice(correct = true, elapsed = it) }
        assertFalse(Rating.EASY in ratings)
    }
}

class GradingTypingTest {

    @Test
    fun `a short word keeps the six-second allowance`() {
        assertEquals(Rating.EASY, Grading.forTyping("cozy", "cozy", Duration.ofSeconds(6)))
        assertEquals(Rating.GOOD, Grading.forTyping("cozy", "cozy", Duration.ofMillis(6_500)))
    }

    @Test
    fun `a long phrase gets more time to count as fluent`() {
        // 3 s + 0.4 s for each of its 20 characters: 11 s.
        assertEquals(Rating.EASY, Grading.forTyping("to get along with sb", "to get along with sb", Duration.ofMillis(10_900)))
        assertEquals(Rating.GOOD, Grading.forTyping("to get along with sb", "to get along with sb", Duration.ofMillis(11_500)))
    }

    @Test
    fun `exact answer typed fast is EASY`() {
        assertEquals(Rating.EASY, Grading.forTyping("apple", "apple", QUICK))
    }

    @Test
    fun `exact answer typed slowly is GOOD`() {
        assertEquals(Rating.GOOD, Grading.forTyping("apple", "apple", SLOW))
    }

    @Test
    fun `case and padding are not what is being tested`() {
        assertEquals(Rating.EASY, Grading.forTyping("apple", "  Apple ", QUICK))
    }

    @Test
    fun `a single wrong letter is a failure, not a near miss`() {
        // Spelling is what a production card measures, so it is graded strictly.
        assertEquals(Rating.AGAIN, Grading.forTyping("apple", "aple", QUICK))
        assertEquals(false, Grading.isCorrect("apple", "aple"))
    }

    @Test
    fun `a different word is AGAIN`() {
        assertEquals(Rating.AGAIN, Grading.forTyping("apple", "orange", QUICK))
    }

    @Test
    fun `empty input is AGAIN`() {
        assertEquals(Rating.AGAIN, Grading.forTyping("apple", "   ", QUICK))
    }

    @Test
    fun `length buys no leniency`() {
        assertEquals(Rating.AGAIN, Grading.forTyping("accommodation", "acommodaton", QUICK))
        assertEquals(Rating.AGAIN, Grading.forTyping("accommodation", "accomodation", QUICK))
    }

    @Test
    fun `a different word is a failure too`() {
        assertEquals(Rating.AGAIN, Grading.forTyping("cat", "dog", QUICK))
    }

    @Test
    fun `formatting is still forgiven — only letters are graded`() {
        // Case, spacing and the particle are not letters of the word.
        assertEquals(Rating.EASY, Grading.forTyping("to run", "  RUN ", QUICK))
        assertEquals(Rating.EASY, Grading.forTyping("run", "to  run", QUICK))
    }

    @Test
    fun `grading and exactness never disagree about the same answer`() {
        // They used to: grading split "lift / elevator" into alternatives while
        // exactness compared the whole stored string, so a correct answer was
        // reported as inexact and the card stopped to show a pointless diff.
        listOf(
            "lift / elevator" to "elevator",
            "to run" to "run",
            "run" to "to run",
            "apple" to "APPLE",
            "apple" to "aple",
            "apple" to "orange",
        ).forEach { (expected, typed) ->
            assertEquals(
                Grading.isCorrect(expected, typed),
                Grading.matchesExactly(expected, typed),
                "$expected vs $typed",
            )
        }
    }

    @Test
    fun `a slash means the alternatives are interchangeable, not a sequence`() {
        // Worth knowing before writing irregular verbs as "go / went / gone":
        // that spelling accepts any single form as a complete answer.
        assertEquals(Rating.EASY, Grading.forTyping("go / went / gone", "went", QUICK))
    }

    @Test
    fun `spacing around commas does not matter for verb forms`() {
        listOf("go, went, gone", "go,went,gone", "go ,went ,  gone", "GO, WENT, GONE")
            .forEach { typed ->
                assertEquals(Rating.EASY, Grading.forTyping("go, went, gone", typed, QUICK), typed)
            }
    }

    @Test
    fun `a missing form is still a failure`() {
        assertEquals(Rating.AGAIN, Grading.forTyping("go, went, gone", "go, gone", QUICK))
        assertEquals(Rating.AGAIN, Grading.forTyping("go, went, gone", "go", QUICK))
        assertEquals(Rating.AGAIN, Grading.forTyping("go, went, gone", "go, goed, gone", QUICK))
    }

    @Test
    fun `verb forms may be separated by spaces instead of commas`() {
        listOf("go went gone", "go  went   gone", "go, went gone", "go,went gone")
            .forEach { typed ->
                assertEquals(Rating.EASY, Grading.forTyping("go, went, gone", typed, QUICK), typed)
            }
    }

    @Test
    fun `space-separated verb forms are graded as strictly as comma-separated ones`() {
        assertEquals(Rating.AGAIN, Grading.forTyping("go, went, gone", "go gone", QUICK))
        assertEquals(Rating.AGAIN, Grading.forTyping("go, went, gone", "go goed gone", QUICK))
        assertEquals(Rating.AGAIN, Grading.forTyping("go, went, gone", "go went gone gone", QUICK))
    }

    @Test
    fun `in an ordinary phrase spaces are not separators`() {
        // Only a list of verb forms gets the space-as-comma reading; anywhere
        // else commas would turn a phrase into a different answer.
        assertEquals(Rating.EASY, Grading.forTyping("to get along with sb", "get along with sb", QUICK))
        assertEquals(Rating.AGAIN, Grading.forTyping("to get along with sb", "get, along, with, sb", QUICK))
    }

    @Test
    fun `either alternative is accepted`() {
        assertEquals(Rating.EASY, Grading.forTyping("lift / elevator", "elevator", QUICK))
        assertEquals(Rating.EASY, Grading.forTyping("lift / elevator", "lift", QUICK))
    }

    @Test
    fun `leading to is optional on verbs`() {
        assertEquals(Rating.EASY, Grading.forTyping("to run", "run", QUICK))
        assertEquals(Rating.EASY, Grading.forTyping("run", "to run", QUICK))
    }

    @Test
    fun `isCorrect follows the same rules as the rating`() {
        assertTrue(Grading.isCorrect("apple", "APPLE"))
        assertFalse(Grading.isCorrect("apple", "aple"))
        assertFalse(Grading.isCorrect("apple", "orange"))
    }
}

class LevenshteinTest {

    @Test
    fun `distance counts single edits`() {
        assertEquals(0, Grading.levenshtein("apple", "apple"))
        assertEquals(1, Grading.levenshtein("apple", "aple"))
        assertEquals(1, Grading.levenshtein("apple", "apples"))
        assertEquals(1, Grading.levenshtein("apple", "ample"))
    }

    @Test
    fun `distance to an empty string is the length`() {
        assertEquals(5, Grading.levenshtein("apple", ""))
        assertEquals(5, Grading.levenshtein("", "apple"))
    }

    @Test
    fun `distance is symmetric`() {
        assertEquals(
            Grading.levenshtein("kitten", "sitting"),
            Grading.levenshtein("sitting", "kitten"),
        )
        assertEquals(3, Grading.levenshtein("kitten", "sitting"))
    }
}

class VerbFormsTest {

    @Test
    fun `three comma-separated single words are verb forms`() {
        assertTrue(Grading.isVerbForms("go, went, gone"))
        assertTrue(Grading.isVerbForms("write,wrote,written"))
    }

    @Test
    fun `phrases, alternatives and single words are not verb forms`() {
        listOf("to get along with sb", "lift / elevator", "besides", "a doubt", "go, went")
            .forEach { assertFalse(Grading.isVerbForms(it), it) }
    }

    @Test
    fun `a typed list of forms is written back with commas`() {
        // What is shown in the diff and kept in the history: separators the user
        // chose must not surface as spelling mistakes.
        assertEquals("go, went, gonne", Grading.asAnswered("go, went, gone", "go went gonne"))
        assertEquals("Go, went, Gone", Grading.asAnswered("go, went, gone", "  Go went,Gone "))
    }

    @Test
    fun `any other answer is kept as typed`() {
        assertEquals("get along with sb", Grading.asAnswered("to get along with sb", " get along with sb "))
    }
}
