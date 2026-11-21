package lingue.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import lingue.core.Direction
import lingue.core.StudyCard
import lingue.core.Word
import lingue.data.WordRepository
import java.time.Instant

@Composable
fun WordsScreen(words: WordRepository, onChanged: () -> Unit) {
    var items by remember { mutableStateOf(words.all()) }
    var cards by remember { mutableStateOf(words.allCards().groupBy { it.word.id }) }
    var term by remember { mutableStateOf("") }
    var translation by remember { mutableStateOf("") }
    var example by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        items = words.all()
        cards = words.allCards().groupBy { it.word.id }
        onChanged()
    }

    fun submit() {
        if (term.isBlank() || translation.isBlank()) return
        try {
            words.add(term, translation, example)
            term = ""
            translation = ""
            example = ""
            error = null
            refresh()
        } catch (failure: Exception) {
            // The unique index on `term` is the expected failure here.
            error = "Слово «${term.trim()}» уже есть в списке"
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(28.dp)) {
        Text(
            text = "Новое слово",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                val submits = event.type == KeyEventType.KeyDown && event.key == Key.Enter
                if (submits) submit()
                submits
            },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = term,
                onValueChange = { term = it },
                label = { Text("English") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = translation,
                onValueChange = { translation = it },
                label = { Text("Перевод") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = example,
                onValueChange = { example = it },
                label = { Text("Пример (необязательно)") },
                singleLine = true,
                modifier = Modifier.weight(1.2f),
            )
            Button(onClick = ::submit, enabled = term.isNotBlank() && translation.isNotBlank()) {
                Text("Добавить")
            }
        }

        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Каждое слово даёт две карточки — узнавание и производство. " +
                "Равноправные варианты разделяйте косой чертой: lift / elevator",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(22.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            text = "${formatCount(items.size, "слово", "слова", "слов")} · " +
                formatCount(items.size * 2, "карточка", "карточки", "карточек"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { word ->
                WordRow(word, cards[word.id].orEmpty()) {
                    words.delete(word.id)
                    refresh()
                }
            }
        }
    }
}

@Composable
private fun WordRow(word: Word, cards: List<StudyCard>, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${word.term} — ${word.translation}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = status(cards),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDelete) { Text("Удалить") }
        }
    }
}

/**
 * One line summarising both directions.
 *
 * A word nobody has answered yet gets a single label instead of two identical
 * "due in under a minute" readings: its cards are due because they were created
 * in the past, which says nothing about the word.
 */
internal fun status(cards: List<StudyCard>): String {
    if (cards.isEmpty()) return ""
    if (cards.all { it.reviews == 0 }) return "новое, ещё не изучалось"

    val now = Instant.now()
    return cards.sortedBy { it.direction.ordinal }.joinToString("   ·   ") { describe(it, now) }
}

/** "узнавание: через 3 дня, ошибок 1" — the two directions progress separately. */
private fun describe(card: StudyCard, now: Instant): String = buildString {
    append(directionLabel(card.direction))
    append(": ")
    when {
        card.reviews == 0 -> append("не начато")
        // Already waiting in the queue — a countdown to a moment in the past is
        // not a status.
        card.isDue(now) -> append("готово к показу")
        else -> append(formatNextDue(now, card.due))
    }
    if (card.reviews > 0) append(", повторений ${card.reviews}")
    if (card.lapses > 0) append(", ошибок ${card.lapses}")
}

private fun directionLabel(direction: Direction): String = when (direction) {
    Direction.RECOGNITION -> "узнавание"
    Direction.PRODUCTION -> "производство"
}
