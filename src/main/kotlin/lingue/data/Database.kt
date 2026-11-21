package lingue.data

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * The SQLite file backing the app.
 *
 * One user, one process, one connection — nothing here needs a pool. The whole
 * collection is a single file, which makes a backup `cp` and a restore a copy
 * back.
 */
class Database(private val location: Path = defaultLocation()) : AutoCloseable {

    val connection: Connection

    init {
        Files.createDirectories(location.parent)
        connection = DriverManager.getConnection("jdbc:sqlite:$location").apply {
            createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            // Durable enough for a desktop app and much faster than the default
            // rollback journal when reviews are written one at a time.
            createStatement().use { it.execute("PRAGMA journal_mode = WAL") }
        }
        migrate()
    }

    /**
     * Linear migrations keyed off `PRAGMA user_version`.
     *
     * Each step moves the schema forward by exactly one version and is written
     * once, never edited afterwards — a fresh database runs every step in order,
     * which is what keeps the two paths (fresh install and upgrade) identical.
     */
    private fun migrate() {
        val version = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

        if (version < 1) createInitialSchema()
        if (version < 2) addConfusionTracking()
        if (version < 3) recordTypedAnswers()
        if (version < 4) addSettings()
        if (version < 5) addSynonyms()

        connection.createStatement().use { it.executeUpdate("PRAGMA user_version = $SCHEMA_VERSION") }
    }

    /**
     * Records which option was picked when a choice answer was wrong.
     *
     * An option the user actually confused with the right answer is the strongest
     * possible distractor for that word, and it cannot be reconstructed after the
     * fact — hence a real migration rather than a rebuild.
     */
    private fun addConfusionTracking() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                ALTER TABLE reviews
                  ADD COLUMN confused_with_word_id INTEGER
                      REFERENCES words (id) ON DELETE SET NULL
                """.trimIndent(),
            )
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_reviews_confusion ON reviews (confused_with_word_id)",
            )
        }
    }

    /**
     * Keeps what was actually typed on a production card.
     *
     * The rating alone cannot say *which* words are misspelled or *how* — and a
     * misremembered spelling is a different problem from a slow recall, needing a
     * different exercise. Recorded now so the history exists when that exercise
     * starts drawing on it.
     */
    private fun recordTypedAnswers() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("ALTER TABLE reviews ADD COLUMN answer_text TEXT")
        }
    }

    /**
     * Somewhere to keep the daily limits.
     *
     * A key-value table rather than columns: these are a handful of user
     * preferences, and giving each one a schema migration of its own would cost
     * more than it is worth.
     */
    private fun addSettings() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS settings (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * Pairs of words that mean close enough to the same thing.
     *
     * Needed because no string metric can tell a good distractor from an unfair
     * one: "передбачуваний" and "непередбачуваний" look alike and mean opposite
     * things, while "кроме того" and "помимо этого" look alike and mean the same.
     * Only a reader knows which is which, so the pairs are recorded as they are
     * met rather than guessed.
     *
     * Both directions are stored, which keeps every lookup a plain index hit.
     */
    private fun addSynonyms() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS synonyms (
                    word_id    INTEGER NOT NULL REFERENCES words (id) ON DELETE CASCADE,
                    synonym_id INTEGER NOT NULL REFERENCES words (id) ON DELETE CASCADE,
                    PRIMARY KEY (word_id, synonym_id)
                )
                """.trimIndent(),
            )
        }
    }

    private fun createInitialSchema() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS words (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    term        TEXT    NOT NULL,
                    translation TEXT    NOT NULL,
                    example     TEXT,
                    created_at  INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_words_term ON words (term COLLATE NOCASE)",
            )

            // One row per direction of a word. Recognition and production are
            // scheduled independently because they are different abilities; see
            // lingue.core.Direction.
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS cards (
                    id        INTEGER PRIMARY KEY AUTOINCREMENT,
                    word_id   INTEGER NOT NULL REFERENCES words (id) ON DELETE CASCADE,
                    direction TEXT    NOT NULL,
                    -- FSRS state, serialised by the library. Treating it as opaque
                    -- keeps an algorithm upgrade from becoming a schema migration.
                    fsrs_card TEXT    NOT NULL,
                    -- Denormalised out of fsrs_card so "what is due?" can use an index.
                    -- Epoch millis, not ISO text: Instant.toString() omits the fractional
                    -- part when it is zero, which makes string ordering wrong.
                    due       INTEGER NOT NULL,
                    reviews   INTEGER NOT NULL DEFAULT 0,
                    lapses    INTEGER NOT NULL DEFAULT 0,
                    UNIQUE (word_id, direction)
                )
                """.trimIndent(),
            )
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cards_due ON cards (due)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cards_word ON cards (word_id)")

            // Kept for the FSRS optimiser: fitting the algorithm's parameters to
            // personal memory needs a full answer history, and it cannot be
            // reconstructed after the fact. The answer format is not stored — it
            // follows from the card's direction.
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS reviews (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    card_id     INTEGER NOT NULL REFERENCES cards (id) ON DELETE CASCADE,
                    rating      INTEGER NOT NULL,
                    correct     INTEGER NOT NULL,
                    elapsed_ms  INTEGER NOT NULL,
                    reviewed_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_reviews_card ON reviews (card_id)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_reviews_at ON reviews (reviewed_at)")
        }
    }

    override fun close() = connection.close()

    companion object {
        const val SCHEMA_VERSION = 5

        /**
         * Always `~/.local/share/lingue/lingue.db`.
         *
         * XDG_DATA_HOME is deliberately ignored. Snap-packaged launchers (VS Code
         * among them) rewrite it to point inside their own sandbox, so honouring
         * it would give one database when the app is started from an IDE terminal
         * and a different one from a plain shell — with the words silently missing
         * from whichever was opened second.
         */
        fun defaultLocation(): Path =
            Path.of(System.getProperty("user.home"), ".local", "share", "lingue", "lingue.db")
    }
}
