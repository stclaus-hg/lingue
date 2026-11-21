package lingue.app

import lingue.core.Limits
import lingue.core.Question
import lingue.data.Database
import lingue.data.WordRepository
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DailyAllowanceTest {

    private lateinit var database: Database
    private lateinit var words: WordRepository

    private val utc: ZoneId = ZoneId.of("UTC")
    private val now: Instant = Instant.parse("2026-08-18T09:00:00Z")

    @BeforeTest
    fun setUp() {
        val directory = Files.createTempDirectory("lingue-allowance-test")
        database = Database(directory.resolve("test.db"))
        words = WordRepository(database.connection)
        (1..20).forEach { words.add("word$it", "переклад$it", now = now) }
    }

    @AfterTest
    fun tearDown() = database.close()

    private fun trainer(limits: Limits) = Trainer(words, limits = limits, zone = utc, clock = { now })

    private fun Trainer.answerCorrectly() {
        if (introducing) understood()
        when (val current = question) {
            is Question.Choice -> answerChoice(current.correctIndex)
            is Question.Typing -> answerTyping(current.expected)
            null -> error("nothing to answer")
        }
    }

    /**
     * Answers everything on offer and returns how many words that covered.
     *
     * Words, not answers: a new word is asked again within its learning circle,
     * and the allowance is about how many words a day brings.
     */
    private fun Trainer.workThrough(): Int {
        val words = mutableSetOf<Long>()
        while (question != null) {
            words += question!!.card.word.id
            answerCorrectly()
            advance()
        }
        return words.size
    }

    @Test
    fun `the day's allowance bounds the session`() {
        val trainer = trainer(Limits(newPerDay = 5, reviewsPerDay = 40))
        trainer.reload()

        assertEquals(5, trainer.workThrough())
        assertTrue(trainer.progress.budgetSpent, "the new-card allowance is used up")
    }

    @Test
    fun `reloading again does not hand out more than the allowance`() {
        val trainer = trainer(Limits(newPerDay = 5, reviewsPerDay = 40))
        trainer.reload()
        trainer.workThrough()

        trainer.reload()

        assertNull(trainer.question, "the cap holds across reloads within a day")
    }

    @Test
    fun `taking another day releases exactly one more allowance`() {
        val trainer = trainer(Limits(newPerDay = 5, reviewsPerDay = 40))
        trainer.reload()
        trainer.workThrough()

        trainer.takeAnotherDay()

        assertNotNull(trainer.question, "the next day's worth is drawn forward")
        assertEquals(5, trainer.workThrough(), "one allowance, not everything at once")
    }

    @Test
    fun `it can be taken repeatedly`() {
        val trainer = trainer(Limits(newPerDay = 4, reviewsPerDay = 40))
        trainer.reload()

        var total = trainer.workThrough()
        repeat(3) {
            trainer.takeAnotherDay()
            total += trainer.workThrough()
        }

        assertEquals(16, total, "four allowances of four")
    }

    @Test
    fun `the header shows the extended allowance, not the setting`() {
        val trainer = trainer(Limits(newPerDay = 5, reviewsPerDay = 40))
        trainer.reload()
        trainer.workThrough()

        trainer.takeAnotherDay()

        assertEquals(10, trainer.progress.allowedNew, "5 for today plus 5 drawn forward")
        assertEquals(80, trainer.progress.allowedReviews)
        assertEquals(5, trainer.progress.limits.newPerDay, "the saved setting is untouched")
    }

    @Test
    fun `waiting cards are reported by kind, not as one lump`() {
        // Twenty unseen words and no repeats yet: everything held back is new.
        val trainer = trainer(Limits(newPerDay = 5, reviewsPerDay = 40))
        trainer.reload()

        assertEquals(0, trainer.progress.waitingRepeats, "nothing has ever been answered")
        assertEquals(15, trainer.progress.waitingNew, "twenty words, five let through")
        assertTrue(trainer.progress.budgetSpent, "a cap is what is holding them")
    }

    @Test
    fun `a fresh trainer is back to the ordinary cap`() {
        val limits = Limits(newPerDay = 5, reviewsPerDay = 40)
        val first = trainer(limits)
        first.reload()
        first.workThrough()
        first.takeAnotherDay()
        first.workThrough()

        // Restarting the app must not inherit an evening of catching up.
        val second = trainer(limits)
        second.reload()

        assertNull(second.question)
        assertEquals(5, second.progress.allowedNew)
    }
}
