package lingue.data

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseLocationTest {

    /**
     * Regression guard. An earlier version honoured XDG_DATA_HOME, which snap
     * launchers rewrite to a path inside their sandbox — starting the app from a
     * snap-packaged IDE terminal and from a plain shell then opened two different
     * databases, each missing the other's words.
     */
    @Test
    fun `the database always lives under the real home directory`() {
        val expected = Path.of(System.getProperty("user.home"), ".local", "share", "lingue", "lingue.db")

        assertEquals(expected, Database.defaultLocation())
    }
}
