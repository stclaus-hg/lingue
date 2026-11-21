package lingue.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lingue.app.Trainer
import lingue.audio.Pronouncer
import lingue.core.Limits
import lingue.data.SettingsRepository
import lingue.data.Stats
import lingue.data.StatsRepository
import lingue.data.WordRepository
import java.time.LocalDate

private const val TAB_TRAIN = 0
private const val TAB_WORDS = 1
private const val TAB_STATS = 2
private const val TAB_SETTINGS = 3

@Composable
fun App(
    words: WordRepository,
    statistics: StatsRepository,
    settings: SettingsRepository,
    trainer: Trainer,
    pronouncer: Pronouncer,
) {
    var dark by remember { mutableStateOf(true) }
    var soundOn by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(TAB_TRAIN) }
    // Rebuilding the queue mid-session would throw away progress, so an edit on
    // the words tab only takes effect when the user comes back to training.
    var wordsChanged by remember { mutableStateOf(false) }
    // Recomputed when the tab is opened rather than continuously: the numbers only
    // change in response to answers, which happen on another tab.
    var stats by remember { mutableStateOf<Stats?>(null) }
    var limits by remember { mutableStateOf(trainer.limits) }

    LaunchedEffect(Unit) { trainer.reload() }

    LingueTheme(dark) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Lingue",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { soundOn = !soundOn }) {
                            Text(if (soundOn) "Звук включён" else "Звук выключен")
                        }
                        TextButton(onClick = { dark = !dark }) {
                            Text(if (dark) "Светлая тема" else "Тёмная тема")
                        }
                    }
                }

                TabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == TAB_TRAIN,
                        onClick = {
                            tab = TAB_TRAIN
                            if (wordsChanged) {
                                trainer.reload()
                                wordsChanged = false
                            }
                        },
                        text = { Text("Тренировка") },
                    )
                    Tab(
                        selected = tab == TAB_WORDS,
                        onClick = { tab = TAB_WORDS },
                        text = { Text("Слова") },
                    )
                    Tab(
                        selected = tab == TAB_STATS,
                        onClick = {
                            tab = TAB_STATS
                            stats = statistics.compute()
                        },
                        text = { Text("Статистика") },
                    )
                    Tab(
                        selected = tab == TAB_SETTINGS,
                        onClick = {
                            tab = TAB_SETTINGS
                            stats = statistics.compute()
                        },
                        text = { Text("Настройки") },
                    )
                }

                when (tab) {
                    TAB_TRAIN -> TrainScreen(trainer, pronouncer, soundOn)
                    TAB_WORDS -> WordsScreen(words) { wordsChanged = true }
                    TAB_SETTINGS -> SettingsScreen(
                        limits = limits,
                        dueRepeats = stats?.dueRepeats ?: 0,
                        notStarted = stats?.notStarted ?: 0,
                        onSave = { updated ->
                            settings.save(updated)
                            // Reloaded from storage rather than trusting the input:
                            // the repository clamps out-of-range values.
                            limits = settings.load()
                            trainer.limits = limits
                            trainer.reload()
                            stats = statistics.compute()
                        },
                    )
                    else -> stats?.let { StatsScreen(it, LocalDate.now()) }
                }
            }
        }
    }
}
