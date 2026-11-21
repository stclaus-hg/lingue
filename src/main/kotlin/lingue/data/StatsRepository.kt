package lingue.data

import io.github.openspacedrepetition.State
import lingue.core.Direction
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DayCount(val date: LocalDate, val count: Int)

data class Stats(
    val words: Int,
    val cards: Int,
    val byState: Map<State, Int>,
    val dueNow: Int,
    /**
     * Due cards that have been answered before — the only ones that mean "behind".
     * An unseen card is due from the moment it is created and owes nothing.
     */
    val dueRepeats: Int,
    /** Cards that have never been answered at all. */
    val notStarted: Int,
    val answeredToday: Int,
    val introducedToday: Int,
    val totalAnswers: Int,
    /** Share of correct answers, `null` until something has been answered. */
    val retention: Double?,
    val retentionByDirection: Map<Direction, Double>,
    val activity: List<DayCount>,
    val forecast: List<DayCount>,
)

/**
 * Read-only queries for the statistics screen.
 *
 * Card-derived figures are computed in memory rather than in SQL: FSRS state
 * lives inside the serialised card, and denormalising it into a column only to
 * count three buckets would trade a schema change for nothing — a personal
 * collection is a few hundred rows.
 */
class StatsRepository(
    private val connection: Connection,
    private val words: WordRepository,
) {

    fun compute(
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): Stats {
        val cards = words.allCards()

        val (total, retention) = overallRetention()

        return Stats(
            words = words.count(),
            cards = cards.size,
            byState = cards.groupingBy { it.state }.eachCount(),
            dueNow = cards.count { it.isDue(now) },
            dueRepeats = cards.count { it.reviews > 0 && it.isDue(now) },
            notStarted = cards.count { it.reviews == 0 },
            answeredToday = words.reviewedOn(today, zone),
            introducedToday = words.introducedOn(today, zone),
            totalAnswers = total,
            retention = retention,
            retentionByDirection = retentionByDirection(),
            activity = activity(today, zone),
            forecast = forecast(cards.filter { it.reviews > 0 }.map { it.due }, today, zone),
        )
    }

    private fun overallRetention(): Pair<Int, Double?> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) AS total, AVG(correct) AS share FROM reviews").use { rows ->
                rows.next()
                val total = rows.getInt("total")
                val share = rows.getDouble("share")
                total to if (total == 0) null else share
            }
        }

    /**
     * Recognition and production are expected to diverge — that divergence is the
     * payoff of scheduling them separately, so it is worth showing.
     */
    private fun retentionByDirection(): Map<Direction, Double> =
        connection.prepareStatement(
            """
            SELECT c.direction AS direction, AVG(r.correct) AS share
              FROM reviews r
              JOIN cards c ON c.id = r.card_id
             GROUP BY c.direction
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        put(Direction.valueOf(rows.getString("direction")), rows.getDouble("share"))
                    }
                }
            }
        }

    /** Answers per day over the trailing fortnight, including days with none. */
    private fun activity(today: LocalDate, zone: ZoneId): List<DayCount> {
        val from = today.minusDays(ACTIVITY_DAYS - 1L).atStartOfDay(zone).toInstant().toEpochMilli()
        val counts = mutableMapOf<LocalDate, Int>()

        connection.prepareStatement("SELECT reviewed_at FROM reviews WHERE reviewed_at >= ?").use { statement ->
            statement.setLong(1, from)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val day = Instant.ofEpochMilli(rows.getLong("reviewed_at")).atZone(zone).toLocalDate()
                    counts[day] = (counts[day] ?: 0) + 1
                }
            }
        }

        return (0 until ACTIVITY_DAYS).map { offset ->
            val day = today.minusDays(ACTIVITY_DAYS - 1L - offset)
            DayCount(day, counts[day] ?: 0)
        }
    }

    /**
     * How many repeats come due each of the next seven days.
     *
     * Only cards answered before are passed in, by the same rule as [Stats.dueRepeats]:
     * an unseen card is released by the new-card limit rather than owed, and
     * counting it once put a whole import under today and flattened the rest.
     *
     * Anything overdue is counted under today — it is work waiting now, not work
     * that quietly disappeared into the past.
     */
    private fun forecast(dueDates: List<Instant>, today: LocalDate, zone: ZoneId): List<DayCount> {
        val counts = mutableMapOf<LocalDate, Int>()
        dueDates.forEach { due ->
            val day = due.atZone(zone).toLocalDate()
            val bucket = if (day.isBefore(today)) today else day
            counts[bucket] = (counts[bucket] ?: 0) + 1
        }

        return (0 until FORECAST_DAYS).map { offset ->
            val day = today.plusDays(offset.toLong())
            DayCount(day, counts[day] ?: 0)
        }
    }

    private companion object {
        const val ACTIVITY_DAYS = 14
        const val FORECAST_DAYS = 7
    }
}
