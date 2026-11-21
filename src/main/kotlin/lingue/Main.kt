package lingue

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import lingue.app.Trainer
import lingue.audio.Pronouncer
import lingue.data.Database
import lingue.data.SettingsRepository
import lingue.data.StatsRepository
import lingue.data.WordRepository
import lingue.ui.App

fun main() {
    val database = Database()
    val words = WordRepository(database.connection)
    val statistics = StatsRepository(database.connection, words)
    val settings = SettingsRepository(database.connection)
    val trainer = Trainer(words, limits = settings.load())
    val pronouncer = Pronouncer()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            pronouncer.close()
            database.close()
        },
    )

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Lingue — английские слова",
            // Tall enough that a question, its feedback and the spelling exercise
            // all fit without scrolling on a 1080p screen.
            state = rememberWindowState(size = DpSize(1020.dp, 900.dp)),
        ) {
            App(words, statistics, settings, trainer, pronouncer)
        }
    }
}
