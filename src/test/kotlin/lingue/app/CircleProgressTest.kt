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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** What the answer panel says about a word's way through the learning circle. */
class CircleProgressTest {

    private lateinit var database: Database
    private lateinit var words: WordRepository

    private var now: Instant = Instant.parse("2026-08-01T09:00:00Z")

    @BeforeTest
    fun setUp() {
        val directory = Files.createTempDirectory("lingue-circle-progress-test")
        database = Database(directory.resolve("test.db"))
        words = WordRepository(database.connection)
        // Words in review and not due for weeks: wrong options only.
        (1..3).forEach { i ->
            val word = words.add("backdrop$i", "фон$i", now = now)
            words.cardsOf(word.id).forEach { words.answer(it, Rating.EASY, at = now) }
        }
    }

    @AfterTest
    fun tearDown() = database.close()

    private fun trainer() =
        Trainer(words, limits = Limits(newPerDay = 5, reviewsPerDay = 40), zone = ZoneId.of("UTC"), clock = { now })

    private fun newWordSession(): Trainer {
        words.add("vague", "расплывчатый", now = now)
        return trainer().apply { reload(); understood() }
    }

    private fun Trainer.pickRight() {
        val current = assertNotNull(question as? Question.Choice)
        answerChoice(current.correctIndex)
    }

    private fun Trainer.next() = advance()

    private val Trainer.circle: Circle? get() = assertNotNull(feedback).circle

    @Test
    fun `a missed new word needs two right answers in a row`() {
        val trainer = newWordSession()

        trainer.dontKnow()

        assertEquals(Circle.Going(done = 0, total = 2, slow = false), trainer.circle)
    }

    @Test
    fun `a right answer after the miss is one step of two`() {
        val trainer = newWordSession()
        trainer.dontKnow()
        trainer.next()

        trainer.pickRight()

        assertEquals(Circle.Going(done = 1, total = 2, slow = false), trainer.circle)
    }

    @Test
    fun `the second right answer in a row settles the word`() {
        val trainer = newWordSession()
        trainer.pickRight()
        trainer.next()

        trainer.pickRight()

        assertEquals(Circle.Graduated(total = 2), trainer.circle)
    }

    @Test
    fun `a forgotten old word needs one right answer`() {
        val monthAgo = now.minus(Duration.ofDays(30))
        val word = words.add("helpless", "беспомощный", now = monthAgo)
        words.cardsOf(word.id).forEach { words.answer(it, Rating.EASY, at = monthAgo) }
        val trainer = trainer().apply { reload() }

        trainer.dontKnow()

        assertEquals(Circle.Going(done = 0, total = 1, slow = false), trainer.circle)
    }

    @Test
    fun `a slow right answer does not move the word`() {
        val trainer = newWordSession()
        trainer.dontKnow()
        trainer.next()

        now = now.plus(Duration.ofSeconds(11))
        trainer.pickRight()

        assertEquals(Circle.Going(done = 0, total = 2, slow = true), trainer.circle)
    }

    @Test
    fun `a word that used up today's answers is left for tomorrow`() {
        val trainer = newWordSession()
        repeat(CIRCLE_ANSWER_LIMIT - 1) {
            trainer.dontKnow()
            trainer.next()
        }

        trainer.dontKnow()

        assertEquals(Circle.Paused, trainer.circle)
    }

    @Test
    fun `an ordinary repeat says nothing about a circle`() {
        val monthAgo = now.minus(Duration.ofDays(30))
        val word = words.add("helpless", "беспомощный", now = monthAgo)
        words.cardsOf(word.id).forEach { words.answer(it, Rating.EASY, at = monthAgo) }
        val trainer = trainer().apply { reload() }

        trainer.pickRight()

        assertNull(trainer.circle)
    }

    /** A word whose choice card settled yesterday, so its typing card is open and unseen. */
    private fun firstTypingSession(): Pair<Trainer, Question.Typing> {
        val word = words.add("vague", "расплывчатый", now = now.minus(Duration.ofDays(1)))
        words.graduateRecognition(word.id, at = now.minus(Duration.ofDays(1)))
        val trainer = trainer().apply { reload() }
        return trainer to assertNotNull(trainer.question as? Question.Typing, "expected the typing card")
    }

    @Test
    fun `the first right typing of a word settles it at once`() {
        val (trainer, question) = firstTypingSession()

        now = now.plus(Duration.ofSeconds(20))   // not fast: GOOD, not EASY
        trainer.answerTyping(question.expected)

        assertEquals(Circle.Graduated(total = 1), trainer.circle)
        assertEquals(State.REVIEW, assertNotNull(words.findCard(question.card.id)).state)
        trainer.advance()
        assertNull(trainer.question, "the word does not come round again")
    }

    @Test
    fun `a missed typing needs one right answer`() {
        val (trainer, question) = firstTypingSession()

        trainer.answerTyping("something else")

        assertEquals(Circle.Going(done = 0, total = 1, slow = false), trainer.circle)
    }
}
