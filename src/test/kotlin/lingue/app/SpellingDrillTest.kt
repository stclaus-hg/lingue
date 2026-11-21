package lingue.app

import lingue.core.Direction
import lingue.core.Question
import lingue.data.Database
import lingue.data.WordRepository
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpellingDrillTest {

    private lateinit var database: Database
    private lateinit var words: WordRepository

    private val utc: ZoneId = ZoneId.of("UTC")
    private val now: Instant = Instant.parse("2026-08-03T09:00:00Z")

    @BeforeTest
    fun setUp() {
        val directory = Files.createTempDirectory("lingue-drill-test")
        database = Database(directory.resolve("test.db"))
        words = WordRepository(database.connection)
        listOf(
            "cozy" to "затишний",
            "reliable" to "надійний",
            "curious" to "допитливий",
            "a witness" to "свідок",
        ).forEach { (term, translation) -> words.add(term, translation, now = now) }
    }

    @AfterTest
    fun tearDown() = database.close()

    private fun trainer() = Trainer(words, zone = utc, clock = { now })

    /**
     * A session of production cards — the only cards a spelling drill can follow.
     *
     * Typing opens the day after recognition graduates, so every word's
     * recognition is put into review yesterday.
     */
    private fun productionSession(): Trainer {
        words.all().forEach { words.graduateRecognition(it.id, at = now.minus(Duration.ofDays(1))) }
        val trainer = trainer()
        trainer.reload()
        return trainer
    }

    private fun currentTyping(trainer: Trainer): Question.Typing =
        assertNotNull(trainer.question as? Question.Typing, "expected a production card")

    @Test
    fun `a wrong production answer starts the drill`() {
        val trainer = productionSession()
        currentTyping(trainer)

        trainer.answerTyping("совершенно другое слово")

        val drill = assertNotNull(trainer.drill)
        assertEquals(DRILL_REPETITIONS, drill.required)
        assertEquals(0, drill.done)
        assertTrue(drill.showsModel, "the first repetition is copied from the model")
    }

    @Test
    fun `a wrong recognition answer never starts a drill`() {
        val trainer = trainer()
        trainer.reload()
        val question = assertNotNull(trainer.question as? Question.Choice)
        trainer.understood()

        trainer.answerChoice((question.correctIndex + 1) % question.options.size)

        assertEquals(false, assertNotNull(trainer.feedback, "the answer must have been taken").correct)
        assertNull(trainer.drill, "picking the wrong option says nothing about spelling")
    }

    @Test
    fun `even a one-letter miss earns the exercise`() {
        val trainer = productionSession()
        val question = currentTyping(trainer)

        trainer.answerTyping(question.expected.dropLast(1))

        assertEquals(false, assertNotNull(trainer.feedback).correct, "spelling is graded strictly")
        assertNotNull(trainer.drill, "a misspelling is exactly what the exercise repairs")
    }

    @Test
    fun `repetitions count down only when the spelling is exact`() {
        val trainer = productionSession()
        val question = currentTyping(trainer)
        trainer.answerTyping("нечто иное")

        trainer.submitDrill("nonsense")
        assertEquals(0, assertNotNull(trainer.drill).done, "a wrong attempt must not count")
        assertTrue(assertNotNull(trainer.drill).lastAttemptWrong)

        trainer.submitDrill(question.expected)
        assertEquals(1, assertNotNull(trainer.drill).done)
        assertEquals(false, assertNotNull(trainer.drill).lastAttemptWrong)
        assertEquals(false, assertNotNull(trainer.drill).showsModel, "later repetitions are from memory")
    }

    @Test
    fun `finishing the exercise moves straight on to the next card`() {
        val trainer = productionSession()
        val question = currentTyping(trainer)
        trainer.answerTyping("нечто иное")

        repeat(DRILL_REPETITIONS) { trainer.submitDrill(question.expected) }

        assertNull(trainer.drill)
        assertNull(
            trainer.feedback,
            "the verdict panel must not come back asking to acknowledge a finished exercise",
        )
    }

    @Test
    fun `skipping returns to the verdict instead of moving on`() {
        val trainer = productionSession()
        trainer.answerTyping("нечто иное")

        trainer.skipDrill()

        assertNull(trainer.drill)
        assertNotNull(
            trainer.feedback,
            "whoever skips has not read the correct spelling yet — it stays on screen",
        )
    }

    @Test
    fun `case and stray spaces are forgiven, letters are not`() {
        val trainer = productionSession()
        val question = currentTyping(trainer)
        trainer.answerTyping("нечто иное")

        trainer.submitDrill("  ${question.expected.uppercase()} ")

        assertEquals(1, assertNotNull(trainer.drill).done)
    }

    @Test
    fun `skipping ends the exercise`() {
        val trainer = productionSession()
        trainer.answerTyping("нечто иное")
        assertNotNull(trainer.drill)

        trainer.skipDrill()

        assertNull(trainer.drill)
    }

    @Test
    fun `the drill writes nothing to the review history`() {
        val trainer = productionSession()
        val question = currentTyping(trainer)
        trainer.answerTyping("нечто иное")
        val logged = words.reviewedOn(LocalDate.of(2026, 8, 3), utc)

        repeat(DRILL_REPETITIONS) { trainer.submitDrill(question.expected) }

        assertEquals(
            logged,
            words.reviewedOn(LocalDate.of(2026, 8, 3), utc),
            "repetitions are practice, not answers — they must not reach the scheduler",
        )
    }

    @Test
    fun `moving on clears any unfinished drill`() {
        val trainer = productionSession()
        trainer.answerTyping("нечто иное")
        assertNotNull(trainer.drill)

        trainer.advance()

        assertNull(trainer.drill)
    }

    @Test
    fun `what was typed is kept for later analysis`() {
        val trainer = productionSession()
        currentTyping(trainer)

        trainer.answerTyping("cozzy")

        val misspellings = words.misspellings()
        assertTrue(
            misspellings.any { it.typed == "cozzy" },
            "the typed spelling should be recorded, got $misspellings",
        )
    }

    @Test
    fun `a near miss is simply wrong`() {
        val trainer = productionSession()
        val question = currentTyping(trainer)

        trainer.answerTyping(question.expected.dropLast(1))

        val feedback = assertNotNull(trainer.feedback)
        assertEquals(false, feedback.correct, "the word could not be written, so it is not known")
    }

    @Test
    fun `a letter-perfect answer needs no acknowledgement`() {
        val trainer = productionSession()
        val question = currentTyping(trainer)

        trainer.answerTyping(question.expected)

        val feedback = assertNotNull(trainer.feedback)
        assertTrue(feedback.correct)
    }

    @Test
    fun `case and spacing alone do not make an answer wrong`() {
        val trainer = productionSession()
        val question = currentTyping(trainer)

        trainer.answerTyping("  ${question.expected.uppercase()}  ")

        assertTrue(assertNotNull(trainer.feedback).correct)
    }

    @Test
    fun `production cards really are the ones being drilled`() {
        val trainer = productionSession()

        assertEquals(Direction.PRODUCTION, assertNotNull(trainer.question).card.direction)
    }
}
