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

/** "Не знаю" owns up to a guess, so the guess never passes for knowledge. */
class DontKnowTest {

    private lateinit var database: Database
    private lateinit var words: WordRepository

    private var now: Instant = Instant.parse("2026-08-01T09:00:00Z")

    @BeforeTest
    fun setUp() {
        val directory = Files.createTempDirectory("lingue-dont-know-test")
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

    private fun confusions(cardId: Long): List<Long?> =
        database.connection.prepareStatement("SELECT confused_with_word_id FROM reviews WHERE card_id = ? ORDER BY id").use { statement ->
            statement.setLong(1, cardId)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getLong(1).takeUnless { rows.wasNull() }) }
            }
        }

    /** A word in review whose recognition card is due today. */
    private fun oldWord(term: String) {
        val word = words.add(term, "перевод $term", now = now.minus(Duration.ofDays(30)))
        words.cardsOf(word.id).forEach { words.answer(it, Rating.EASY, at = now.minus(Duration.ofDays(30))) }
    }

    @Test
    fun `not knowing is recorded as a miss without a confusion`() {
        oldWord("cozy")
        val trainer = trainer().apply { reload() }
        val question = choice(trainer)

        trainer.dontKnow()

        val feedback = assertNotNull(trainer.feedback)
        assertFalse(feedback.correct)
        assertEquals(question.answer, feedback.expected)
        assertEquals(Rating.AGAIN.value, ratings(question.card.id).last())
        assertEquals(listOf<Long?>(null), confusions(question.card.id).takeLast(1))
    }

    @Test
    fun `not knowing an old word sends it into relearning and back into the circle`() {
        (1..6).forEach { oldWord("old$it") }
        val trainer = trainer().apply { reload() }
        val forgotten = choice(trainer).card

        trainer.dontKnow()
        trainer.advance()
        repeat(REQUEUE_GAP) {
            val other = assertNotNull(trainer.question)
            assertTrue(other.card.id != forgotten.id)
            trainer.answerChoice(choice(trainer).correctIndex)
            trainer.advance()
        }

        assertEquals(State.RELEARNING, assertNotNull(words.findCard(forgotten.id)).state)
        assertEquals(forgotten.id, assertNotNull(trainer.question).card.id)
    }

    @Test
    fun `not knowing starts no spelling exercise`() {
        oldWord("cozy")
        val trainer = trainer().apply { reload() }

        trainer.dontKnow()

        assertNull(trainer.drill)
    }

    @Test
    fun `not knowing is ignored during the introduction`() {
        words.add("cozy", "уютный", now = now)
        val trainer = trainer().apply { reload() }

        trainer.dontKnow()

        assertTrue(trainer.introducing)
        assertNull(trainer.feedback)
    }
}
