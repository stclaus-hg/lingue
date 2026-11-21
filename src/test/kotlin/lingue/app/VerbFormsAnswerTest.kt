package lingue.app

import lingue.core.Question
import lingue.data.Database
import lingue.data.WordRepository
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VerbFormsAnswerTest {

    private lateinit var database: Database
    private lateinit var words: WordRepository

    private val utc: ZoneId = ZoneId.of("UTC")
    private val now: Instant = Instant.parse("2026-11-08T09:00:00Z")

    @BeforeTest
    fun setUp() {
        val directory = Files.createTempDirectory("lingue-verb-forms-test")
        database = Database(directory.resolve("test.db"))
        words = WordRepository(database.connection)
        listOf(
            "go, went, gone" to "идти, ходить",
            "cozy" to "уютный",
            "reliable" to "надёжный",
            "curious" to "любопытный",
        ).forEach { (term, translation) -> words.add(term, translation, now = now) }
    }

    @AfterTest
    fun tearDown() = database.close()

    /** Walks the session until the verb's production card is on screen. */
    private fun verbProductionCard(): Trainer {
        // Typing opens the day after recognition graduates.
        words.all().forEach { words.graduateRecognition(it.id, at = now.minus(Duration.ofDays(1))) }
        val trainer = Trainer(words, zone = utc, clock = { now })
        repeat(2) {
            trainer.reload()
            while (true) {
                val current = trainer.question ?: break
                if (current is Question.Typing && current.expected == "go, went, gone") return trainer
                when (current) {
                    is Question.Choice -> trainer.answerChoice(current.correctIndex)
                    is Question.Typing -> trainer.answerTyping(current.expected)
                }
                trainer.advance()
            }
        }
        error("the verb's production card never came up")
    }

    @Test
    fun `forms typed with spaces count as the right answer`() {
        val trainer = verbProductionCard()

        trainer.answerTyping("go went gone")

        assertEquals(true, assertNotNull(trainer.feedback).correct)
    }

    @Test
    fun `a misspelt space-separated answer is shown with commas`() {
        val trainer = verbProductionCard()

        trainer.answerTyping("go went gonne")

        val feedback = assertNotNull(trainer.feedback)
        assertEquals(false, feedback.correct)
        assertEquals("go, went, gonne", feedback.given, "separators must not show up in the diff")
    }

    @Test
    fun `the drill accepts space-separated forms too`() {
        val trainer = verbProductionCard()
        trainer.answerTyping("go goed gone")

        trainer.submitDrill("go went gone")

        assertEquals(1, assertNotNull(trainer.drill).done)
    }
}
