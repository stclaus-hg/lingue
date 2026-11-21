package lingue.app

import io.github.openspacedrepetition.Rating
import lingue.core.Direction
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

class TrainerTest {

    private lateinit var database: Database
    private lateinit var words: WordRepository

    private val now: Instant = Instant.parse("2026-08-01T09:00:00Z")

    @BeforeTest
    fun setUp() {
        val directory = Files.createTempDirectory("lingue-trainer-test")
        database = Database(directory.resolve("test.db"))
        words = WordRepository(database.connection)
    }

    @AfterTest
    fun tearDown() = database.close()

    private fun trainer(limits: Limits = Limits()) = Trainer(
        words = words,
        limits = limits,
        zone = ZoneId.of("UTC"),
        clock = { now },
    )

    private fun seed(count: Int) = (1..count).forEach {
        words.add("word$it", "переклад$it", now = now)
    }

    /** Answers whatever is on screen correctly, whichever format it uses. */
    private fun Trainer.answerCorrectly() {
        if (introducing) understood()
        when (val current = question) {
            is Question.Choice -> answerChoice(current.correctIndex)
            is Question.Typing -> answerTyping(current.expected)
            null -> error("nothing to answer")
        }
    }

    @Test
    fun `only one direction of a word appears in a session`() {
        seed(3)

        val trainer = trainer()
        trainer.reload()

        // Six cards are due, but a sibling would give away its counterpart.
        assertEquals(3, trainer.progress.remaining)
    }

    @Test
    fun `the buried sibling comes back in the next session`() {
        seed(4)
        // Both directions of every word in review and due today, so a word's
        // two cards compete for the same session.
        words.all().forEach { word ->
            words.cardsOf(word.id).forEach { words.answer(it, Rating.EASY, at = now.minus(Duration.ofDays(30))) }
        }
        val trainer = trainer()
        trainer.reload()

        val first = assertNotNull(trainer.question).card
        trainer.answerCorrectly()
        trainer.advance()

        trainer.reload()
        val sameWord = generateSequence({ trainer.question }) { trainer.advance(); trainer.question }
            .take(4)
            .filterNotNull()
            .first { it.card.word.id == first.word.id }

        assertNotEquals(first.direction, sameWord.card.direction)
    }

    @Test
    fun `recognition is asked by choice and production by typing`() {
        seed(6)
        val trainer = trainer()
        trainer.reload()

        repeat(6) {
            val question = assertNotNull(trainer.question)
            assertEquals(question.card.direction.mode, question.mode)
            when (question) {
                is Question.Choice -> {
                    assertEquals(Direction.RECOGNITION, question.card.direction)
                    assertEquals(question.card.word.term, question.prompt)
                    assertTrue(question.card.word.translation in question.labels)
                }
                is Question.Typing -> {
                    assertEquals(Direction.PRODUCTION, question.card.direction)
                    assertEquals(question.card.word.translation, question.prompt)
                }
            }
            trainer.answerCorrectly()
            trainer.advance()
        }
    }

    @Test
    fun `the daily limit counts words`() {
        seed(10)

        val trainer = trainer(Limits(newPerDay = 4))
        trainer.reload()

        assertEquals(4, trainer.progress.remaining)
    }

    @Test
    fun `a wrong answer brings the card back within the session`() {
        seed(4)
        val trainer = trainer(Limits(newPerDay = 1))
        trainer.reload()

        val question = assertNotNull(trainer.question) as Question.Choice
        trainer.understood()
        trainer.answerChoice((question.correctIndex + 1) % question.options.size)

        assertEquals(false, assertNotNull(trainer.feedback).correct)
        trainer.advance()
        assertNotNull(trainer.question, "a forgotten card is re-queued rather than dropped")
    }

    @Test
    fun `a new word answered right twice ends a one-card session`() {
        seed(4)
        val trainer = trainer(Limits(newPerDay = 1))
        trainer.reload()
        val card = assertNotNull(trainer.question).card

        trainer.answerCorrectly()
        trainer.advance()
        assertEquals(card.id, assertNotNull(trainer.question, "the first answer leaves it in learning").card.id)

        trainer.answerCorrectly()
        trainer.advance()

        assertNull(trainer.question)
    }

    @Test
    fun `an empty collection produces no questions`() {
        val trainer = trainer()
        trainer.reload()

        assertNull(trainer.question)
        assertEquals(0, trainer.progress.remaining)
        assertEquals(0, trainer.progress.words)
    }

    @Test
    fun `a single word is still studiable through its production card`() {
        // Recognition needs a distractor and cannot be asked here; the trainer
        // must fall through to the production card rather than offering nothing.
        words.add("apple", "яблуко", now = now)

        val trainer = trainer()
        trainer.reload()

        val question = assertNotNull(trainer.question)
        assertEquals(Direction.PRODUCTION, question.card.direction)
    }

    @Test
    fun `answers are written to the history for the optimiser`() {
        seed(4)
        val trainer = trainer(Limits(newPerDay = 2))
        trainer.reload()

        trainer.answerCorrectly()
        trainer.advance()
        trainer.answerCorrectly()

        assertEquals(2, words.reviewedOn(java.time.LocalDate.of(2026, 8, 1), ZoneId.of("UTC")))
    }
}
