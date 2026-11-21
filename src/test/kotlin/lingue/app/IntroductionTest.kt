package lingue.app

import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.State
import lingue.core.Limits
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A brand-new word is shown before it is asked. */
class IntroductionTest {

    private lateinit var database: Database
    private lateinit var words: WordRepository

    private var now: Instant = Instant.parse("2026-08-01T09:00:00Z")

    @BeforeTest
    fun setUp() {
        val directory = Files.createTempDirectory("lingue-introduction-test")
        database = Database(directory.resolve("test.db"))
        words = WordRepository(database.connection)
        // Words already in review and not due for weeks: wrong options only.
        (1..3).forEach { i ->
            val word = words.add("backdrop$i", "фон$i", now = now)
            words.cardsOf(word.id).forEach { words.answer(it, Rating.EASY, at = now) }
        }
    }

    @AfterTest
    fun tearDown() = database.close()

    private fun trainer() =
        Trainer(words, limits = Limits(newPerDay = 5, reviewsPerDay = 40), zone = ZoneId.of("UTC"), clock = { now })

    private fun choice(trainer: Trainer): Question.Choice =
        assertNotNull(trainer.question as? Question.Choice, "expected a choice question")

    private fun ratings(cardId: Long): List<Int> =
        database.connection.prepareStatement("SELECT rating FROM reviews WHERE card_id = ? ORDER BY id").use { statement ->
            statement.setLong(1, cardId)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getInt(1)) } }
        }

    @Test
    fun `a brand-new word is introduced before it is asked`() {
        words.add("cozy", "уютный", now = now)
        val trainer = trainer().apply { reload() }

        assertTrue(trainer.introducing)
        val question = choice(trainer)
        trainer.answerChoice(question.correctIndex)

        assertNull(trainer.feedback, "an answer before the word was shown is ignored")
        assertTrue(ratings(question.card.id).isEmpty(), "the introduction is not a review")
    }

    @Test
    fun `after the introduction the same word is asked`() {
        words.add("cozy", "уютный", now = now)
        val trainer = trainer().apply { reload() }
        val introduced = choice(trainer).card

        trainer.understood()

        assertFalse(trainer.introducing)
        val question = choice(trainer)
        assertEquals(introduced.id, question.card.id)
        trainer.answerChoice(question.correctIndex)
        assertEquals(listOf(Rating.GOOD.value), ratings(question.card.id))
    }

    @Test
    fun `a started card is never introduced`() {
        val word = words.add("cozy", "уютный", now = now.minus(Duration.ofDays(1)))
        val recognition = words.cardsOf(word.id).first { it.direction == lingue.core.Direction.RECOGNITION }
        words.answer(recognition, Rating.AGAIN, at = now.minus(Duration.ofDays(1)))

        val trainer = trainer().apply { reload() }

        assertEquals(recognition.id, choice(trainer).card.id)
        assertFalse(trainer.introducing)
    }

    @Test
    fun `a word claimed as known and then picked right graduates at once`() {
        words.add("cozy", "уютный", now = now)
        val trainer = trainer().apply { reload() }

        trainer.alreadyKnown()
        val question = choice(trainer)
        trainer.answerChoice(question.correctIndex)
        trainer.advance()

        assertEquals(listOf(Rating.EASY.value), ratings(question.card.id))
        assertEquals(State.REVIEW, assertNotNull(words.findCard(question.card.id)).state)
        assertNull(trainer.question, "a known word skips the circle")
    }

    @Test
    fun `a word claimed as known but picked wrong enters the circle`() {
        words.add("cozy", "уютный", now = now)
        val trainer = trainer().apply { reload() }

        trainer.alreadyKnown()
        val question = choice(trainer)
        trainer.answerChoice((question.correctIndex + 1) % question.options.size)
        trainer.advance()

        assertEquals(listOf(Rating.AGAIN.value), ratings(question.card.id))
        assertEquals(question.card.id, assertNotNull(trainer.question).card.id, "it comes back to be learned")
        assertFalse(trainer.introducing, "the word has been seen; it is not introduced twice")
    }

    @Test
    fun `time spent reading the introduction is not answer time`() {
        words.add("cozy", "уютный", now = now)
        val trainer = trainer().apply { reload() }

        now = now.plus(Duration.ofSeconds(30))
        trainer.understood()
        now = now.plus(Duration.ofSeconds(1))
        val question = choice(trainer)
        trainer.answerChoice(question.correctIndex)

        assertEquals(listOf(Rating.GOOD.value), ratings(question.card.id), "a quick pick, not a slow one")
    }
}
