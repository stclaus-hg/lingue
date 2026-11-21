package lingue.data

import lingue.core.Limits
import java.sql.Connection

/**
 * Persistence for the daily limits.
 *
 * Stored in the database next to the cards rather than in a config file: they are
 * part of the collection's state, and a backup of the one file should restore the
 * whole setup.
 */
class SettingsRepository(private val connection: Connection) {

    fun load(): Limits {
        val stored = readAll()
        return Limits(
            newPerDay = stored[NEW_PER_DAY]?.toIntOrNull()?.coerceIn(Limits.ALLOWED_NEW)
                ?: Limits().newPerDay,
            reviewsPerDay = stored[REVIEWS_PER_DAY]?.toIntOrNull()?.coerceIn(Limits.ALLOWED_REVIEWS)
                ?: Limits().reviewsPerDay,
        )
    }

    fun save(limits: Limits) {
        // Clamped on the way in as well as on the way out: a value edited into the
        // file by hand should not be able to put the app in a stuck state.
        put(NEW_PER_DAY, limits.newPerDay.coerceIn(Limits.ALLOWED_NEW).toString())
        put(REVIEWS_PER_DAY, limits.reviewsPerDay.coerceIn(Limits.ALLOWED_REVIEWS).toString())
    }

    private fun readAll(): Map<String, String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT key, value FROM settings").use { rows ->
                buildMap {
                    while (rows.next()) put(rows.getString("key"), rows.getString("value"))
                }
            }
        }

    private fun put(key: String, value: String) {
        connection.prepareStatement(
            "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = ?",
        ).use { statement ->
            statement.setString(1, key)
            statement.setString(2, value)
            statement.setString(3, value)
            statement.executeUpdate()
        }
    }

    private companion object {
        const val NEW_PER_DAY = "new_per_day"
        const val REVIEWS_PER_DAY = "reviews_per_day"
    }
}
