package lingue.app

import io.github.openspacedrepetition.Rating
import lingue.core.Direction
import lingue.core.Limits
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
import kotlin.test.assertTrue

/** Which cards a session is built from, now that new words are learned first. */
class LearningQueueTest {

    private lateinit var database: Database
    private lateinit var words: WordRepository

    private var now: Instant = Instant.parse("2026-08-01T09:00:00Z")
    private val yesterday: Instant get() = now.minus(Duration.ofDays(1))

    @BeforeTest
    fun setUp() {
        val directory = Files.createTempDirectory("lingue-learning-queue-test")
        database = Database(directory.resolve("test.db"))
        words = WordRepository(database.connection)
    }

    @AfterTest
    fun tearDown() = database.close()

    private fun trainer(limits: Limits = Limits()) =
        Trainer(words, limits = limits, zone = ZoneId.of("UTC"), clock = { now })

    private fun session(limits: Limits = Limits()) = trainer(limits).apply { reload() }.queuedCards()

    /**
     * A word that is never asked in these tests, there only so recognition
     * questions have a wrong option to offer. Its own cards are put out of reach.
     */
    private fun backdrop() {
        val word = words.add("backdrop", "фон", now = now)
        words.cardsOf(word.id).forEach { words.answer(it, Rating.EASY, at = now) }
    }

    @Test
    fun `a new word's production card waits while its recognition is unlearned`() {
        backdrop()
        words.add("cozy", "уютный", now = now)

        assertEquals(listOf(Direction.RECOGNITION), session().map { it.direction })
    }

    @Test
    fun `production waits for the night even after recognition graduated today`() {
        backdrop()
        val word = words.add("cozy", "уютный", now = now)
        words.graduateRecognition(word.id, at = now)

        assertTrue(session().isEmpty())
    }

    @Test
    fun `production opens the day after recognition graduated`() {
        backdrop()
        val word = words.add("cozy", "уютный", now = yesterday)
        words.graduateRecognition(word.id, at = yesterday)

        assertEquals(listOf(Direction.PRODUCTION), session().map { it.direction })
    }

    @Test
    fun `production stays closed while recognition is relearning`() {
        backdrop()
        val word = words.add("cozy", "уютный", now = now.minus(Duration.ofDays(20)))
        val graduated = words.graduateRecognition(word.id, at = now.minus(Duration.ofDays(20)))
        words.answer(graduated, Rating.AGAIN, at = yesterday)

        assertTrue(session().none { it.direction == Direction.PRODUCTION })
    }

    @Test
    fun `the new-word cap counts words, so unlocked production does not use it up`() {
        (1..3).forEach { i ->
            val word = words.add("old$i", "старое$i", now = yesterday)
            words.graduateRecognition(word.id, at = yesterday)
        }
        (1..3).forEach { i -> words.add("new$i", "новое$i", now = now) }

        val cards = session(Limits(newPerDay = 2, reviewsPerDay = 40))

        assertEquals(3, cards.count { it.direction == Direction.PRODUCTION })
        assertEquals(2, cards.count { it.direction == Direction.RECOGNITION })
    }

    @Test
    fun `unlocked production cards are bounded by the review cap`() {
        // The first day after this change: dozens of old words have an unseen
        // production card. They must arrive as repeats, not all at once.
        (1..5).forEach { i ->
            val word = words.add("old$i", "старое$i", now = yesterday)
            words.graduateRecognition(word.id, at = yesterday)
        }

        val trainer = trainer(Limits(newPerDay = 0, reviewsPerDay = 2)).apply { reload() }

        assertEquals(2, trainer.progress.remaining)
        assertEquals(3, trainer.progress.waitingRepeats)
        assertEquals(0, trainer.progress.waitingNew)
    }

    @Test
    fun `a card left in learning on an earlier day waits behind the review cap`() {
        // Cards abandoned mid-learning before this change sit in LEARNING for
        // days. Letting all of them past the cap would flood one session.
        backdrop()
        val word = words.add("cozy", "уютный", now = yesterday)
        val recognition = words.cardsOf(word.id).first { it.direction == Direction.RECOGNITION }
        words.answer(recognition, Rating.GOOD, at = yesterday)

        val trainer = trainer(Limits(newPerDay = 0, reviewsPerDay = 0)).apply { reload() }

        assertTrue(trainer.queuedCards().none { it.id == recognition.id })
        assertEquals(1, trainer.progress.waitingRepeats)
    }

    @Test
    fun `a card being learned today is not held back by the review cap`() {
        backdrop()
        val word = words.add("cozy", "уютный", now = now)
        val recognition = words.cardsOf(word.id).first { it.direction == Direction.RECOGNITION }
        words.answer(recognition, Rating.GOOD, at = now)

        val trainer = trainer(Limits(newPerDay = 0, reviewsPerDay = 0)).apply { reload() }

        assertEquals(listOf(recognition.id), trainer.queuedCards().map { it.id })
    }
}
