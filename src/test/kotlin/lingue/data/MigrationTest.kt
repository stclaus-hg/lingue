package lingue.data

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationTest {

    /** The schema exactly as version 1 shipped, so the upgrade path is exercised. */
    private fun createVersion1(file: Path) {
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE words (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        term        TEXT    NOT NULL,
                        translation TEXT    NOT NULL,
                        example     TEXT,
                        created_at  INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE cards (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT,
                        word_id   INTEGER NOT NULL REFERENCES words (id) ON DELETE CASCADE,
                        direction TEXT    NOT NULL,
                        fsrs_card TEXT    NOT NULL,
                        due       INTEGER NOT NULL,
                        reviews   INTEGER NOT NULL DEFAULT 0,
                        lapses    INTEGER NOT NULL DEFAULT 0,
                        UNIQUE (word_id, direction)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE reviews (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        card_id     INTEGER NOT NULL REFERENCES cards (id) ON DELETE CASCADE,
                        rating      INTEGER NOT NULL,
                        correct     INTEGER NOT NULL,
                        elapsed_ms  INTEGER NOT NULL,
                        reviewed_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    "INSERT INTO words (id, term, translation, created_at) VALUES (1, 'cozy', 'затишний', 1000)",
                )
                statement.executeUpdate(
                    """
                    INSERT INTO cards (id, word_id, direction, fsrs_card, due, reviews)
                    VALUES (1, 1, 'RECOGNITION', '{"cardId":1,"state":"LEARNING","step":0,"due":"2026-08-01T09:00:00Z"}', 1000, 3)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO reviews (card_id, rating, correct, elapsed_ms, reviewed_at)
                    VALUES (1, 3, 1, 1500, 2000)
                    """.trimIndent(),
                )
                statement.executeUpdate("PRAGMA user_version = 1")
            }
        }
    }

    @Test
    fun `a version 1 database is upgraded in place, keeping its history`() {
        val file = Files.createTempDirectory("lingue-migration").resolve("legacy.db")
        createVersion1(file)

        Database(file).use { database ->
            val version = database.connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
            }
            assertEquals(Database.SCHEMA_VERSION, version)

            database.connection.createStatement().use { statement ->
                val columns = statement.executeQuery("PRAGMA table_info(reviews)").use { rows ->
                    buildList { while (rows.next()) add(rows.getString("name")) }
                }
                assertTrue("confused_with_word_id" in columns, "the new column should exist")

                // The point of migrating rather than rebuilding: nothing is lost.
                statement.executeQuery("SELECT COUNT(*) FROM reviews").use {
                    it.next()
                    assertEquals(1, it.getInt(1))
                }
                statement.executeQuery("SELECT confused_with_word_id FROM reviews").use {
                    it.next()
                    it.getLong(1)
                    assertTrue(it.wasNull(), "existing rows have no recorded confusion")
                }
                statement.executeQuery("SELECT reviews FROM cards WHERE id = 1").use {
                    it.next()
                    assertEquals(3, it.getInt(1))
                }
            }
        }
    }

    @Test
    fun `a fresh database lands on the current version with every column`() {
        val file = Files.createTempDirectory("lingue-fresh").resolve("new.db")

        Database(file).use { database ->
            val version = database.connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
            }
            assertEquals(Database.SCHEMA_VERSION, version)

            val columns = database.connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(reviews)").use { rows ->
                    buildList { while (rows.next()) add(rows.getString("name")) }
                }
            }
            assertTrue("confused_with_word_id" in columns)
        }
    }

    @Test
    fun `opening twice is a no-op`() {
        val file = Files.createTempDirectory("lingue-reopen").resolve("twice.db")

        Database(file).use { it.connection }
        Database(file).use { database ->
            val words = WordRepository(database.connection)
            assertEquals(0, words.count())
        }
    }
}
