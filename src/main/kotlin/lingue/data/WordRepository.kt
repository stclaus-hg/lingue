package lingue.data

import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Rating
import lingue.core.Cards
import lingue.core.Direction
import lingue.core.StudyCard
import lingue.core.Word
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A spelling that was produced instead of the expected one, and how often. */
data class Misspelling(val term: String, val typed: String, val times: Int)

/** When a card was answered for the first and for the most recent time. */
data class AnswerSpan(val first: Instant, val last: Instant)

/** Storage for words, their per-direction cards, and the answer history. */
class WordRepository(private val connection: Connection) {

    /**
     * Adds a word together with one card per direction.
     *
     * A card's FSRS state carries its own row id, which only exists after the
     * insert, so each card is written twice inside one transaction. A word left
     * with a card whose state was never filled in would be unschedulable.
     */
    fun add(
        term: String,
        translation: String,
        example: String? = null,
        now: Instant = Instant.now(),
    ): Word {
        val cleanTerm = term.trim()
        val cleanTranslation = translation.trim()
        val cleanExample = example?.trim()?.takeIf(String::isNotBlank)

        return inTransaction {
            val wordId = connection.prepareStatement(
                "INSERT INTO words (term, translation, example, created_at) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, cleanTerm)
                statement.setString(2, cleanTranslation)
                statement.setString(3, cleanExample)
                statement.setLong(4, now.toEpochMilli())
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    check(keys.next()) { "INSERT returned no generated key" }
                    keys.getLong(1)
                }
            }

            Direction.entries.forEach { direction -> insertCard(wordId, direction, now) }

            Word(
                id = wordId,
                term = cleanTerm,
                translation = cleanTranslation,
                example = cleanExample,
                createdAt = now,
            )
        }
    }

    private fun insertCard(wordId: Long, direction: Direction, now: Instant) {
        val cardId = connection.prepareStatement(
            "INSERT INTO cards (word_id, direction, fsrs_card, due) VALUES (?, ?, '', ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, wordId)
            statement.setString(2, direction.name)
            statement.setLong(3, now.toEpochMilli())
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                check(keys.next()) { "INSERT returned no generated key" }
                keys.getLong(1)
            }
        }

        connection.prepareStatement("UPDATE cards SET fsrs_card = ? WHERE id = ?").use { statement ->
            statement.setString(1, Cards.create(cardId, now).toJson())
            statement.setLong(2, cardId)
            statement.executeUpdate()
        }
    }

    fun all(): List<Word> =
        connection.prepareStatement("$WORD_QUERY ORDER BY created_at DESC, word_id DESC")
            .use { it.executeQuery().mapToWords() }

    fun find(id: Long): Word? =
        connection.prepareStatement("$WORD_QUERY WHERE id = ?").use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().mapToWords().firstOrNull()
        }

    fun delete(id: Long) {
        // Cards and reviews follow via ON DELETE CASCADE.
        connection.prepareStatement("DELETE FROM words WHERE id = ?").use { statement ->
            statement.setLong(1, id)
            statement.executeUpdate()
        }
    }

    fun count(): Int = countOf("SELECT COUNT(*) FROM words")

    fun cardCount(): Int = countOf("SELECT COUNT(*) FROM cards")

    /** Cards whose scheduled time has arrived, soonest first. */
    fun dueCards(now: Instant = Instant.now(), limit: Int = 500): List<StudyCard> =
        connection.prepareStatement("$CARD_QUERY WHERE c.due <= ? ORDER BY c.due ASC, c.id ASC LIMIT ?")
            .use { statement ->
                statement.setLong(1, now.toEpochMilli())
                statement.setInt(2, limit)
                statement.executeQuery().mapToCards()
            }

    /** How many cards are waiting, without loading them. */
    fun dueCount(now: Instant = Instant.now()): Int =
        connection.prepareStatement("SELECT COUNT(*) FROM cards WHERE due <= ?").use { statement ->
            statement.setLong(1, now.toEpochMilli())
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    /** Every card, for the word list. Grouped by word id at the call site. */
    fun allCards(): List<StudyCard> =
        connection.prepareStatement("$CARD_QUERY ORDER BY c.word_id, c.id")
            .use { it.executeQuery().mapToCards() }

    fun cardsOf(wordId: Long): List<StudyCard> =
        connection.prepareStatement("$CARD_QUERY WHERE c.word_id = ? ORDER BY c.id").use { statement ->
            statement.setLong(1, wordId)
            statement.executeQuery().mapToCards()
        }

    fun findCard(id: Long): StudyCard? =
        connection.prepareStatement("$CARD_QUERY WHERE c.id = ?").use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().mapToCards().firstOrNull()
        }

    fun update(card: StudyCard) {
        connection.prepareStatement(
            "UPDATE cards SET fsrs_card = ?, due = ?, reviews = ?, lapses = ? WHERE id = ?",
        ).use { statement ->
            statement.setString(1, card.card.toJson())
            statement.setLong(2, card.due.toEpochMilli())
            statement.setInt(3, card.reviews)
            statement.setInt(4, card.lapses)
            statement.setLong(5, card.id)
            statement.executeUpdate()
        }
    }

    fun updateWord(word: Word) {
        connection.prepareStatement(
            "UPDATE words SET term = ?, translation = ?, example = ? WHERE id = ?",
        ).use { statement ->
            statement.setString(1, word.term.trim())
            statement.setString(2, word.translation.trim())
            statement.setString(3, word.example?.trim()?.takeIf(String::isNotBlank))
            statement.setLong(4, word.id)
            statement.executeUpdate()
        }
    }

    /**
     * [confusedWithWordId] is the word whose translation was picked by mistake.
     * It is the raw material for the strongest distractor layer and cannot be
     * reconstructed later, so it is recorded even though nothing reads it yet at
     * the moment of writing.
     */
    fun logReview(
        card: StudyCard,
        rating: Rating,
        correct: Boolean,
        elapsedMillis: Long,
        at: Instant = Instant.now(),
        confusedWithWordId: Long? = null,
        answerText: String? = null,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO reviews
                (card_id, rating, correct, elapsed_ms, reviewed_at, confused_with_word_id, answer_text)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, card.id)
            statement.setInt(2, rating.value)
            statement.setInt(3, if (correct) 1 else 0)
            statement.setLong(4, elapsedMillis)
            statement.setLong(5, at.toEpochMilli())
            if (confusedWithWordId == null) {
                statement.setNull(6, java.sql.Types.INTEGER)
            } else {
                statement.setLong(6, confusedWithWordId)
            }
            statement.setString(7, answerText)
            statement.executeUpdate()
        }
    }

    /**
     * Spellings that were typed but are not the expected one, newest first.
     *
     * Feeds the "words I keep misspelling" view; the drill itself does not need
     * it, but the history is only useful if it is kept from the start.
     */
    fun misspellings(limit: Int = 50): List<Misspelling> =
        connection.prepareStatement(
            """
            SELECT w.term AS term, r.answer_text AS typed, COUNT(*) AS times, MAX(r.reviewed_at) AS last_seen
              FROM reviews r
              JOIN cards c ON c.id = r.card_id
              JOIN words w ON w.id = c.word_id
             WHERE r.answer_text IS NOT NULL
               AND LOWER(TRIM(r.answer_text)) <> LOWER(w.term)
             GROUP BY w.term, LOWER(TRIM(r.answer_text))
             ORDER BY times DESC, last_seen DESC
             LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            Misspelling(
                                term = rows.getString("term"),
                                typed = rows.getString("typed"),
                                times = rows.getInt("times"),
                            ),
                        )
                    }
                }
            }
        }

    /**
     * Records that two words mean close enough to the same thing.
     *
     * Symmetric by construction: a synonym relation has no direction, and storing
     * one row would leave half the lookups wrong.
     */
    fun linkSynonyms(first: Long, second: Long) {
        if (first == second) return
        connection.prepareStatement(
            "INSERT OR IGNORE INTO synonyms (word_id, synonym_id) VALUES (?, ?)",
        ).use { statement ->
            listOf(first to second, second to first).forEach { (a, b) ->
                statement.setLong(1, a)
                statement.setLong(2, b)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    fun allSynonyms(): Map<Long, Set<Long>> {
        val result = mutableMapOf<Long, MutableSet<Long>>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT word_id, synonym_id FROM synonyms").use { rows ->
                while (rows.next()) {
                    result.getOrPut(rows.getLong("word_id")) { mutableSetOf() }
                        .add(rows.getLong("synonym_id"))
                }
            }
        }
        return result
    }

    /**
     * For each word, the words it has been mistaken for, most frequent first.
     *
     * Loaded once per session rather than per question: the whole table is a few
     * hundred rows for a personal collection.
     */
    fun allConfusions(): Map<Long, List<Long>> {
        val result = mutableMapOf<Long, MutableList<Long>>()
        connection.prepareStatement(
            """
            SELECT c.word_id AS asked, r.confused_with_word_id AS picked, COUNT(*) AS times
              FROM reviews r
              JOIN cards c ON c.id = r.card_id
             WHERE r.confused_with_word_id IS NOT NULL
             GROUP BY asked, picked
             ORDER BY asked, times DESC
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    result.getOrPut(rows.getLong("asked")) { mutableListOf() }
                        .add(rows.getLong("picked"))
                }
            }
        }
        return result
    }

    /**
     * Words whose recognition card was answered for the very first time on [date].
     *
     * The daily new-word limit counts introductions rather than rows created: a
     * word can sit in the list unstudied for weeks, and counting it on the day it
     * was typed in would spend the budget on words never actually seen.
     *
     * Only recognition counts. A word's production card is held back until
     * recognition has survived a night, so its first answer is work on a word
     * already started — counting it here would let yesterday's words use up
     * today's new ones.
     */
    fun introducedOn(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Int {
        val (from, until) = dayBounds(date, zone)
        return connection.prepareStatement(
            """
            SELECT COUNT(DISTINCT r.card_id)
              FROM reviews r
              JOIN cards c ON c.id = r.card_id
             WHERE r.reviewed_at >= ? AND r.reviewed_at < ?
               AND c.direction = 'RECOGNITION'
               AND NOT EXISTS (
                   SELECT 1 FROM reviews earlier
                    WHERE earlier.card_id = r.card_id
                      AND earlier.reviewed_at < ?
               )
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, from)
            statement.setLong(2, until)
            statement.setLong(3, from)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    fun reviewedOn(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Int {
        val (from, until) = dayBounds(date, zone)
        return connection.prepareStatement(
            "SELECT COUNT(*) FROM reviews WHERE reviewed_at >= ? AND reviewed_at < ?",
        ).use { statement ->
            statement.setLong(1, from)
            statement.setLong(2, until)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    /**
     * Cards already started that were answered on [date], each counted once.
     *
     * This is what the daily review cap spends. Every answer would be the wrong
     * measure: a word being learned is asked several times within minutes, and
     * a forgotten card circles back before it sticks, and charging each of those
     * answers would leave ten new words eating most of the day's repeats.
     * [reviewedOn] keeps counting every answer for the statistics.
     */
    fun repeatsOn(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Int {
        val (from, until) = dayBounds(date, zone)
        val answered = connection.prepareStatement(
            "SELECT COUNT(DISTINCT card_id) FROM reviews WHERE reviewed_at >= ? AND reviewed_at < ?",
        ).use { statement ->
            statement.setLong(1, from)
            statement.setLong(2, until)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
        return answered - introducedOn(date, zone)
    }

    /** First and last answer of every card that has one, keyed by card id. */
    fun answerSpans(): Map<Long, AnswerSpan> =
        connection.prepareStatement(
            "SELECT card_id, MIN(reviewed_at) AS first, MAX(reviewed_at) AS last FROM reviews GROUP BY card_id",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        put(
                            rows.getLong("card_id"),
                            AnswerSpan(
                                first = Instant.ofEpochMilli(rows.getLong("first")),
                                last = Instant.ofEpochMilli(rows.getLong("last")),
                            ),
                        )
                    }
                }
            }
        }

    private fun dayBounds(date: LocalDate, zone: ZoneId): Pair<Long, Long> =
        date.atStartOfDay(zone).toInstant().toEpochMilli() to
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun countOf(sql: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun <T> inTransaction(block: () -> T): T {
        val previous = connection.autoCommit
        connection.autoCommit = false
        return try {
            block().also { connection.commit() }
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previous
        }
    }

    private fun ResultSet.mapToWords(): List<Word> = use { rows ->
        buildList {
            while (rows.next()) add(rows.readWord())
        }
    }

    private fun ResultSet.mapToCards(): List<StudyCard> = use { rows ->
        buildList {
            while (rows.next()) {
                add(
                    StudyCard(
                        id = rows.getLong("card_id"),
                        word = rows.readWord(),
                        direction = Direction.valueOf(rows.getString("direction")),
                        card = Card.fromJson(rows.getString("fsrs_card")),
                        reviews = rows.getInt("reviews"),
                        lapses = rows.getInt("lapses"),
                    ),
                )
            }
        }
    }

    private fun ResultSet.readWord(): Word = Word(
        id = getLong("word_id"),
        term = getString("term"),
        translation = getString("translation"),
        example = getString("example"),
        createdAt = Instant.ofEpochMilli(getLong("created_at")),
    )

    private companion object {
        /** `id` is aliased to `word_id` so one row reader serves both queries. */
        const val WORD_QUERY = """
            SELECT id AS word_id, term, translation, example, created_at FROM words
        """

        const val CARD_QUERY = """
            SELECT c.id AS card_id, c.direction, c.fsrs_card, c.reviews, c.lapses,
                   w.id AS word_id, w.term, w.translation, w.example, w.created_at
              FROM cards c
              JOIN words w ON w.id = c.word_id
        """
    }
}
