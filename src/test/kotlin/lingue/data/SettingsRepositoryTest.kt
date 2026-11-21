package lingue.data

import lingue.core.Limits
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsRepositoryTest {

    private lateinit var file: Path
    private lateinit var database: Database
    private lateinit var settings: SettingsRepository

    @BeforeTest
    fun setUp() {
        file = Files.createTempDirectory("lingue-settings-test").resolve("test.db")
        database = Database(file)
        settings = SettingsRepository(database.connection)
    }

    @AfterTest
    fun tearDown() = database.close()

    @Test
    fun `an untouched collection uses the defaults`() {
        assertEquals(Limits(), settings.load())
    }

    @Test
    fun `limits survive a restart`() {
        settings.save(Limits(newPerDay = 5, reviewsPerDay = 60))
        database.close()

        database = Database(file)
        settings = SettingsRepository(database.connection)

        assertEquals(Limits(newPerDay = 5, reviewsPerDay = 60), settings.load())
    }

    @Test
    fun `saving twice overwrites rather than failing on the key`() {
        settings.save(Limits(newPerDay = 5, reviewsPerDay = 60))
        settings.save(Limits(newPerDay = 8, reviewsPerDay = 80))

        assertEquals(Limits(newPerDay = 8, reviewsPerDay = 80), settings.load())
    }

    @Test
    fun `pausing new cards is a legal setting, not an error`() {
        settings.save(Limits(newPerDay = 0, reviewsPerDay = 40))

        assertEquals(0, settings.load().newPerDay)
    }

    @Test
    fun `out-of-range values are clamped instead of jamming the queue`() {
        settings.save(Limits(newPerDay = 9_000, reviewsPerDay = 1))

        val loaded = settings.load()
        assertEquals(Limits.ALLOWED_NEW.last, loaded.newPerDay)
        assertEquals(Limits.ALLOWED_REVIEWS.first, loaded.reviewsPerDay)
    }

    @Test
    fun `the expected review load follows the number of new words, two cards each`() {
        assertEquals(200, Limits.expectedDailyReviews(10))
        assertEquals(0, Limits.expectedDailyReviews(0))
    }
}
