package lingue.data

import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.State
import lingue.core.Direction
import lingue.core.ReviewEngine
import lingue.core.StudyCard
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WordRepositoryTest {

    private lateinit var database: Database
    private lateinit var words: WordRepository
    private val engine = ReviewEngine()

    private val now: Instant = Instant.parse("2026-08-01T09:00:00Z")
    private val utc: ZoneId = ZoneId.of("UTC")

    @BeforeTest
    fun setUp() {
        val directory = Files.createTempDirectory("lingue-test")
        database = Database(directory.resolve("test.db"))
        words = WordRepository(database.connection)
    }

    @AfterTest
    fun tearDown() = database.close()

    private fun cardFor(wordId: Long, direction: Direction): StudyCard =
        assertNotNull(words.cardsOf(wordId).firstOrNull { it.direction == direction })

    @Test
    fun `a word gets one card per direction`() {
        val word = words.add("apple", "яблуко", now = now)

        val cards = words.cardsOf(word.id)
        assertEquals(setOf(Direction.RECOGNITION, Direction.PRODUCTION), cards.map { it.direction }.toSet())
        assertEquals(2, cards.size)
    }

    @Test
    fun `both cards start due immediately and in learning`() {
        val word = words.add("apple", "яблуко", now = now)

        words.cardsOf(word.id).forEach { card ->
            assertEquals(now, card.due, "${card.direction} should be due at once")
            assertEquals(State.LEARNING, card.state)
        }
    }

    @Test
    fun `each card carries its own row id, so their FSRS states cannot collide`() {
        val word = words.add("apple", "яблуко", now = now)
        val cards = words.cardsOf(word.id)

        assertEquals(cards.map { it.id.toInt() }, cards.map { it.card.cardId })
        assertNotEquals(cards[0].card.cardId, cards[1].card.cardId)
    }

    @Test
    fun `answering one direction leaves the other untouched`() {
        val word = words.add("apple", "яблуко", now = now)
        val recognition = cardFor(word.id, Direction.RECOGNITION)

        words.update(engine.apply(recognition, Rating.GOOD, now))

        val production = cardFor(word.id, Direction.PRODUCTION)
        assertEquals(now, production.due, "production must not inherit the recognition schedule")
        assertEquals(0, production.reviews)
        assertTrue(cardFor(word.id, Direction.RECOGNITION).due.isAfter(now))
    }

    @Test
    fun `the two directions can diverge in difficulty`() {
        val word = words.add("apple", "яблуко", now = now)

        words.update(engine.apply(cardFor(word.id, Direction.RECOGNITION), Rating.EASY, now))
        words.update(engine.apply(cardFor(word.id, Direction.PRODUCTION), Rating.AGAIN, now))

        val recognition = cardFor(word.id, Direction.RECOGNITION)
        val production = cardFor(word.id, Direction.PRODUCTION)

        assertTrue(
            recognition.due.isAfter(production.due),
            "a recognised word should be scheduled later than one that could not be produced",
        )
        assertEquals(0, recognition.lapses)
        assertEquals(1, production.lapses)
    }

    @Test
    fun `scheduling state survives a round trip through the database`() {
        val word = words.add("apple", "яблуко", now = now)
        val reviewed = engine.apply(cardFor(word.id, Direction.RECOGNITION), Rating.GOOD, now)

        words.update(reviewed)

        val loaded = assertNotNull(words.findCard(reviewed.id))
        assertEquals(reviewed.card.due, loaded.card.due)
        assertEquals(reviewed.card.state, loaded.card.state)
        assertEquals(reviewed.card.stability, loaded.card.stability)
        assertEquals(reviewed.card.difficulty, loaded.card.difficulty)
        assertEquals(Direction.RECOGNITION, loaded.direction)
        assertEquals("apple", loaded.word.term)
    }

    @Test
    fun `due returns only cards whose time has come, soonest first`() {
        val early = words.add("apple", "яблуко", now = now.minus(Duration.ofHours(1)))
        val late = words.add("bread", "хліб", now = now)
        words.update(engine.apply(cardFor(late.id, Direction.RECOGNITION), Rating.EASY, now))

        val due = words.dueCards(now)

        assertEquals(3, due.size, "one card of 'bread' was pushed into the future")
        assertEquals(early.id, due.first().word.id)
    }

    @Test
    fun `deleting a word removes its cards and history`() {
        val word = words.add("apple", "яблуко", now = now)
        words.logReview(cardFor(word.id, Direction.RECOGNITION), Rating.GOOD, true, 1_200, now)

        words.delete(word.id)

        assertEquals(0, words.count())
        assertEquals(0, words.cardCount())
        assertEquals(0, words.reviewedOn(LocalDate.of(2026, 8, 1), utc))
    }

    @Test
    fun `introductions are counted per word, by its recognition card`() {
        val word = words.add("apple", "яблоко", now = now)
        words.logReview(cardFor(word.id, Direction.RECOGNITION), Rating.GOOD, true, 900, now)
        words.logReview(cardFor(word.id, Direction.PRODUCTION), Rating.GOOD, true, 900, now)

        assertEquals(1, words.introducedOn(LocalDate.of(2026, 8, 1), utc))
    }

    @Test
    fun `repeats count each started card once a day and leave new words out`() {
        val old = words.add("pear", "груша", now = now.minus(Duration.ofDays(3)))
        val fresh = words.add("apple", "яблоко", now = now)
        val oldCard = cardFor(old.id, Direction.RECOGNITION)
        words.logReview(oldCard, Rating.GOOD, true, 900, now.minus(Duration.ofDays(2)))
        words.logReview(oldCard, Rating.AGAIN, false, 900, now)
        words.logReview(oldCard, Rating.GOOD, true, 900, now.plus(Duration.ofMinutes(2)))
        words.logReview(cardFor(old.id, Direction.PRODUCTION), Rating.GOOD, true, 900, now)
        words.logReview(cardFor(fresh.id, Direction.RECOGNITION), Rating.GOOD, true, 900, now)

        assertEquals(2, words.repeatsOn(LocalDate.of(2026, 8, 1), utc))
        // Today's answers: AGAIN and GOOD on the old card, its production, the new word.
        assertEquals(4, words.reviewedOn(LocalDate.of(2026, 8, 1), utc), "every answer still counts there")
    }

    @Test
    fun `answer spans give each card its first and last answer`() {
        val word = words.add("apple", "яблоко", now = now)
        val card = cardFor(word.id, Direction.RECOGNITION)
        words.logReview(card, Rating.GOOD, true, 900, now)
        words.logReview(card, Rating.GOOD, true, 900, now.plus(Duration.ofMinutes(5)))

        assertEquals(AnswerSpan(now, now.plus(Duration.ofMinutes(5))), words.answerSpans()[card.id])
        assertNull(words.answerSpans()[cardFor(word.id, Direction.PRODUCTION).id])
    }

    @Test
    fun `a second answer on the same card is not a new introduction`() {
        val word = words.add("apple", "яблуко", now = now)
        val card = cardFor(word.id, Direction.RECOGNITION)

        words.logReview(card, Rating.GOOD, true, 900, now)
        words.logReview(card, Rating.GOOD, true, 900, now.plus(Duration.ofMinutes(10)))

        assertEquals(1, words.introducedOn(LocalDate.of(2026, 8, 1), utc))
        assertEquals(2, words.reviewedOn(LocalDate.of(2026, 8, 1), utc))
    }

    @Test
    fun `word count and card count differ by the number of directions`() {
        words.add("apple", "яблуко", now = now)
        words.add("bread", "хліб", now = now)

        assertEquals(2, words.count())
        assertEquals(4, words.cardCount())
    }
}
