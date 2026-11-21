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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A card still in its learning steps comes back within the session, a few cards later. */
class LearningCircleTest {

    private lateinit var database: Database
    private lateinit var words: WordRepository

    private var now: Instant = Instant.parse("2026-08-01T09:00:00Z")

    @BeforeTest
    fun setUp() {
        val directory = Files.createTempDirectory("lingue-learning-circle-test")
        database = Database(directory.resolve("test.db"))
        words = WordRepository(database.connection)
    }

    @AfterTest
    fun tearDown() = database.close()

    private fun trainer(limits: Limits = Limits(newPerDay = 8, reviewsPerDay = 40)) =
        Trainer(words, limits = limits, zone = ZoneId.of("UTC"), clock = { now })

    private fun seed(count: Int, at: Instant = now) =
        (1..count).map { words.add("word$it", "перевод$it", now = at) }

    /**
     * Words in review and not due for weeks — present only so a single word
     * under test has wrong options to be asked against.
     */
    private fun backdrop(count: Int = 3) = (1..count).forEach { i ->
        val word = words.add("backdrop$i", "фон$i", now = now)
        words.cardsOf(word.id).forEach { words.answer(it, Rating.EASY, at = now) }
    }

    private fun Trainer.answerChoiceCorrectly() {
        val current = assertNotNull(question as? Question.Choice, "expected a choice question")
        understood()
        answerChoice(current.correctIndex)
    }

    private fun Trainer.dontKnowAfterIntroduction() {
        understood()
        dontKnow()
    }

    private fun answersTo(cardId: Long): Int =
        database.connection.prepareStatement("SELECT COUNT(*) FROM reviews WHERE card_id = ?").use { statement ->
            statement.setLong(1, cardId)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }

    @Test
    fun `a new word comes back after four other cards`() {
        seed(8)
        val trainer = trainer().apply { reload() }

        val first = assertNotNull(trainer.question).card
        trainer.answerChoiceCorrectly()          // GOOD -> LEARNING
        trainer.advance()
        repeat(4) {
            assertNotEquals(first.id, assertNotNull(trainer.question).card.id)
            trainer.answerChoiceCorrectly()
            trainer.advance()
        }

        assertEquals(first.id, assertNotNull(trainer.question).card.id)
    }

    @Test
    fun `the circle ends when the card graduates`() {
        backdrop()
        val word = seed(1).single()
        val trainer = trainer().apply { reload() }

        trainer.answerChoiceCorrectly()          // GOOD -> LEARNING, 10 min step
        trainer.advance()
        trainer.answerChoiceCorrectly()          // GOOD -> REVIEW
        trainer.advance()

        assertNull(trainer.question)
        assertEquals(State.REVIEW, words.cardsOf(word.id).first { it.reviews > 0 }.state)
    }

    @Test
    fun `a slow reader still leaves the circle after four answers`() {
        backdrop()
        seed(1)
        val trainer = trainer().apply { reload() }
        val card = assertNotNull(trainer.question).card

        while (trainer.question != null) {
            now = now.plus(Duration.ofSeconds(11))   // every pick is slow, so HARD
            trainer.answerChoiceCorrectly()
            trainer.advance()
        }

        assertEquals(CIRCLE_ANSWER_LIMIT, answersTo(card.id))
        assertEquals(State.LEARNING, assertNotNull(words.findCard(card.id)).state)
    }

    @Test
    fun `a forgotten old card comes back a few cards later, not at the end`() {
        val old = seed(8, at = now.minus(Duration.ofDays(30)))
        // Both directions in review and due today; burying keeps recognition,
        // the older card, so the session is all choice questions.
        old.forEach { word ->
            words.cardsOf(word.id).forEach { words.answer(it, Rating.EASY, at = now.minus(Duration.ofDays(30))) }
        }
        val trainer = trainer(Limits(newPerDay = 0, reviewsPerDay = 40)).apply { reload() }

        val forgotten = assertNotNull(trainer.question as? Question.Choice)
        trainer.answerChoice((forgotten.correctIndex + 1) % forgotten.options.size)   // AGAIN -> RELEARNING
        trainer.advance()
        repeat(4) {
            assertNotEquals(forgotten.card.id, assertNotNull(trainer.question).card.id)
            trainer.answerChoiceCorrectly()
            trainer.advance()
        }

        assertEquals(forgotten.card.id, assertNotNull(trainer.question).card.id)
    }

    @Test
    fun `adding a word mid-circle does not lose the circling word`() {
        backdrop()
        seed(1)
        val trainer = trainer().apply { reload() }
        val circling = assertNotNull(trainer.question).card
        trainer.answerChoiceCorrectly()          // LEARNING, due in ten minutes
        trainer.advance()

        words.add("added", "добавлено", now = now)
        trainer.reload()

        assertTrue(trainer.queuedCards().any { it.id == circling.id })
    }

    @Test
    fun `a card that used up its answers is not brought back by a rebuild`() {
        backdrop()
        seed(1)
        val trainer = trainer().apply { reload() }
        val card = assertNotNull(trainer.question).card
        while (trainer.question != null) {
            now = now.plus(Duration.ofSeconds(11))
            trainer.answerChoiceCorrectly()
            trainer.advance()
        }

        now = now.plus(Duration.ofMinutes(15))   // its learning step is due again
        trainer.reload()

        assertTrue(trainer.queuedCards().none { it.id == card.id })
    }

    @Test
    fun `the answer cap starts afresh the next day even if the app stayed open`() {
        backdrop()
        seed(1)
        val trainer = trainer().apply { reload() }
        val card = assertNotNull(trainer.question).card
        while (trainer.question != null) {
            now = now.plus(Duration.ofSeconds(11))   // four slow answers use up today's cap
            trainer.answerChoiceCorrectly()
            trainer.advance()
        }

        now = now.plus(Duration.ofDays(1))
        trainer.reload()
        assertEquals(card.id, assertNotNull(trainer.question, "yesterday's unfinished word is due").card.id)
        trainer.dontKnow()
        trainer.advance()

        assertEquals(card.id, assertNotNull(trainer.question, "a miss today starts a new circle").card.id)
    }

    @Test
    fun `a word missed just before a rebuild still waits four cards`() {
        seed(8)
        val trainer = trainer().apply { reload() }
        val missed = assertNotNull(trainer.question).card
        trainer.dontKnowAfterIntroduction()
        trainer.advance()

        words.add("added", "добавлено", now = now)
        trainer.reload()

        assertEquals(REQUEUE_GAP, trainer.queuedCards().indexOfFirst { it.id == missed.id })
    }
}
