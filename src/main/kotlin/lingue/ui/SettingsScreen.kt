package lingue.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import lingue.core.Limits

@Composable
fun SettingsScreen(
    limits: Limits,
    dueRepeats: Int,
    notStarted: Int,
    onSave: (Limits) -> Unit,
) {
    var newPerDay by remember(limits) { mutableStateOf(limits.newPerDay.toString()) }
    var reviewsPerDay by remember(limits) { mutableStateOf(limits.reviewsPerDay.toString()) }
    var saved by remember(limits) { mutableStateOf(false) }

    val parsedNew = newPerDay.toIntOrNull()
    val parsedReviews = reviewsPerDay.toIntOrNull()
    val valid = parsedNew != null && parsedNew in Limits.ALLOWED_NEW &&
        parsedReviews != null && parsedReviews in Limits.ALLOWED_REVIEWS

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
    ) {
        Column(modifier = Modifier.widthIn(max = 660.dp)) {
            Text(
                text = "Дневная нагрузка",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = newPerDay,
                    onValueChange = { newPerDay = it.filter(Char::isDigit).take(3); saved = false },
                    label = { Text("Новых слов") },
                    singleLine = true,
                    isError = parsedNew == null || parsedNew !in Limits.ALLOWED_NEW,
                    supportingText = { Text("0–${Limits.ALLOWED_NEW.last}") },
                    modifier = Modifier.widthIn(max = 220.dp),
                )
                OutlinedTextField(
                    value = reviewsPerDay,
                    onValueChange = { reviewsPerDay = it.filter(Char::isDigit).take(3); saved = false },
                    label = { Text("Повторений") },
                    singleLine = true,
                    isError = parsedReviews == null || parsedReviews !in Limits.ALLOWED_REVIEWS,
                    supportingText = { Text("${Limits.ALLOWED_REVIEWS.first}–${Limits.ALLOWED_REVIEWS.last}") },
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            if (parsedNew != null) {
                Text(
                    text = "При ${formatCount(parsedNew, "новом слове", "новых словах", "новых словах")} " +
                        "в день нагрузка со временем установится около " +
                        "${formatCount(Limits.expectedDailyReviews(parsedNew), "повторения", "повторений", "повторений")} в день.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = valid,
                    onClick = {
                        onSave(Limits(newPerDay = parsedNew!!, reviewsPerDay = parsedReviews!!))
                        saved = true
                    },
                ) {
                    Text("Сохранить")
                }
                if (saved) {
                    Text(
                        text = "Сохранено, применится к текущей очереди",
                        style = MaterialTheme.typography.bodySmall,
                        color = Verdict.Correct,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            Backlog(dueRepeats = dueRepeats, notStarted = notStarted, limits = limits, onSave = onSave)

            Spacer(Modifier.height(28.dp))
            Explanation()
        }
    }
}

/**
 * The state of the queue, with the one action that actually clears it.
 *
 * Shown here rather than on the statistics tab because a backlog is not a fact to
 * admire — it is the thing these two numbers exist to control.
 */
@Composable
private fun Backlog(dueRepeats: Int, notStarted: Int, limits: Limits, onSave: (Limits) -> Unit) {
    // Only overdue repeats mean "behind". Unseen cards are due from creation and
    // owe nothing — counting them here made a healthy collection look like a crisis.
    val heavy = dueRepeats > Limits.expectedDailyReviews(limits.newPerDay)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (heavy) Verdict.WrongContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "Сейчас в очереди",
                style = MaterialTheme.typography.titleMedium,
                color = if (heavy) Verdict.Wrong else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${formatCount(dueRepeats, "карточка ждёт", "карточки ждут", "карточек ждут")} повторения, " +
                    "${formatCount(notStarted, "карточка ещё не начата", "карточки ещё не начаты", "карточек ещё не начаты")}. " +
                    "Непоказанные карточки не просрочены — они ждут своей очереди по лимиту новых.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (heavy) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Повторений накопилось больше, чем даёт ваш темп. Это настоящая задолженность: " +
                        "пока она не разойдётся, новые слова только добавляют хвост.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { onSave(limits.copy(newPerDay = 0)) }) {
                    Text("Приостановить новые слова")
                }
            }
        }
    }
}

@Composable
private fun Explanation() {
    Column {
        Text(
            text = "Как это работает",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        listOf(
            "Каждое новое слово тянет за собой примерно двадцать будущих повторений — " +
                "по десять на каждое направление, прежде чем интервалы станут длинными.",
            "Новое слово сначала показывается, потом закрепляется выбором перевода в той же тренировке; " +
                "писать его по-английски предложат не раньше следующего дня.",
            "Поэтому уменьшить общую нагрузку может только лимит новых слов. " +
                "Лимит повторений ограничивает один день, но отложенное всё равно вернётся.",
            "Ноль новых слов — нормальный режим на неделю-другую, пока разбирается задолженность.",
        ).forEach { line ->
            Text(
                text = "•  $line",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}
