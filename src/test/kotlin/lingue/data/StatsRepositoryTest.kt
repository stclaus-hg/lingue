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

class StatsRepositoryTest {

    private lateinit var database: Database
    private lateinit var words: WordRepository
    private lateinit var stats: StatsRepository

    private val utc: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 8, 2)
    private val now: Instant = today.atStartOfDay(utc).toInstant().plus(Duration.ofHours(9))

    @BeforeTest
    fun setUp() {
        val directory = Files.createTempDirectory("lingue-stats-test")
        database = Database(directory.resolve("test.db"))
        words = WordRepository(database.connection)
        stats = StatsRepository(database.connection, words)
    }

    @AfterTest
    fun tearDown() = database.close()

    private fun compute() = stats.compute(today, utc, now)

    private fun cardOf(wordId: Long, direction: Direction): StudyCard =
        assertNotNull(words.cardsOf(wordId).firstOrNull { it.direction == direction })

    @Test
    fun `an untouched collection reports no retention rather than zero`() {
        words.add("apple", "яблуко", now = now)

        val result = compute()

        assertEquals(1, result.words)
        assertEquals(2, result.cards)
        assertEquals(0, result.totalAnswers)
        assertNull(result.retention, "no answers is not the same as all answers wrong")
    }

    @Test
    fun `retention is reported per direction`() {
        val word = words.add("apple", "яблуко", now = now)
        val recognition = cardOf(word.id, Direction.RECOGNITION)
        val production = cardOf(word.id, Direction.PRODUCTION)

        words.logReview(recognition, Rating.GOOD, correct = true, elapsedMillis = 900, at = now)
        words.logReview(production, Rating.AGAIN, correct = false, elapsedMillis = 900, at = now)

        val result = compute()

        assertEquals(2, result.totalAnswers)
        assertEquals(0.5, result.retention)
        assertEquals(1.0, result.retentionByDirection[Direction.RECOGNITION])
        assertEquals(0.0, result.retentionByDirection[Direction.PRODUCTION])
    }

    @Test
    fun `new cards start due and counted as learning`() {
        words.add("apple", "яблуко", now = now)
        words.add("bread", "хліб", now = now)

        val result = compute()

        assertEquals(4, result.dueNow)
        assertEquals(4, result.byState[State.LEARNING])
    }

    @Test
    fun `an answered card leaves the due pile`() {
        val word = words.add("apple", "яблуко", now = now)
        words.update(ReviewEngine().apply(cardOf(word.id, Direction.RECOGNITION), Rating.GOOD, now))

        assertEquals(1, compute().dueNow)
    }

    @Test
    fun `activity covers a fortnight and includes empty days`() {
        val word = words.add("apple", "яблуко", now = now)
        val card = cardOf(word.id, Direction.RECOGNITION)

        words.logReview(card, Rating.GOOD, true, 900, now)
        words.logReview(card, Rating.GOOD, true, 900, now)
        words.logReview(card, Rating.GOOD, true, 900, now.minus(Duration.ofDays(3)))

        val activity = compute().activity

        assertEquals(14, activity.size)
        assertEquals(today, activity.last().date)
        assertEquals(2, activity.last().count)
        assertEquals(1, activity.first { it.date == today.minusDays(3) }.count)
        assertEquals(0, activity.first { it.date == today.minusDays(1) }.count)
    }

    @Test
    fun `cards never answered are not part of the forecast`() {
        // A new card is "due" from the moment it is added, but it is released by
        // the new-card limit, not owed as a repeat. Counting it put a whole import
        // under today and flattened every other day of the chart.
        words.add("apple", "яблуко", now = now.minus(Duration.ofDays(7)))
        words.add("pear", "груша", now = now)

        val forecast = compute().forecast

        assertEquals(7, forecast.size)
        assertEquals(0, forecast.sumOf { it.count })
    }

    @Test
    fun `overdue repeats are counted under today rather than disappearing`() {
        val weekAgo = now.minus(Duration.ofDays(7))
        val word = words.add("apple", "яблуко", now = weekAgo)
        // Answered a week ago and due again within minutes — long overdue now.
        words.update(ReviewEngine().apply(cardOf(word.id, Direction.RECOGNITION), Rating.AGAIN, weekAgo))

        val forecast = compute().forecast

        assertEquals(today, forecast.first().date)
        assertEquals(1, forecast.first().count, "only the answered card is owed; its new sibling is not")
    }

    @Test
    fun `a repeat scheduled for a later day is counted on that day`() {
        val word = words.add("apple", "яблуко", now = now)
        val card = cardOf(word.id, Direction.RECOGNITION)
        val reviewed = ReviewEngine().apply(card, Rating.GOOD, now.minus(Duration.ofDays(1)))
        words.update(reviewed)
        val dueDay = reviewed.due.atZone(utc).toLocalDate()

        val forecast = compute().forecast

        val expected = if (dueDay.isBefore(today)) today else dueDay
        if (expected.isBefore(today.plusDays(7))) {
            assertEquals(1, forecast.first { it.date == expected }.count)
        }
        assertEquals(0, forecast.filter { it.date != expected }.sumOf { it.count }, "the untouched sibling is not a repeat")
    }
}
