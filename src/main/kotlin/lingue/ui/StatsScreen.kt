package lingue.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.openspacedrepetition.State
import lingue.core.Direction
import lingue.data.DayCount
import lingue.data.Stats
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val DAY_LABEL = DateTimeFormatter.ofPattern("d.MM")

@Composable
fun StatsScreen(stats: Stats, today: LocalDate) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("Слов", stats.words.toString(), Modifier.weight(1f))
            Tile("Карточек", stats.cards.toString(), Modifier.weight(1f))
            Tile("Готовы сейчас", stats.dueNow.toString(), Modifier.weight(1f))
            Tile("Сегодня отвечено", stats.answeredToday.toString(), Modifier.weight(1f))
            Tile("Из них новых", stats.introducedToday.toString(), Modifier.weight(1f))
        }

        Spacer(Modifier.height(24.dp))
        Section("Удержание")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Tile(
                label = "Всего верных",
                value = stats.retention.asPercent(),
                modifier = Modifier.weight(1f),
                hint = "из ${formatCount(stats.totalAnswers, "ответа", "ответов", "ответов")}",
            )
            Tile(
                label = "Узнавание",
                value = stats.retentionByDirection[Direction.RECOGNITION].asPercent(),
                modifier = Modifier.weight(1f),
                hint = "выбор из вариантов",
            )
            Tile(
                label = "Производство",
                value = stats.retentionByDirection[Direction.PRODUCTION].asPercent(),
                modifier = Modifier.weight(1f),
                hint = "ввод руками",
            )
        }

        Spacer(Modifier.height(24.dp))
        Section("Состояние карточек")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("Изучаются", (stats.byState[State.LEARNING] ?: 0).toString(), Modifier.weight(1f))
            Tile("На повторении", (stats.byState[State.REVIEW] ?: 0).toString(), Modifier.weight(1f))
            Tile("Заново", (stats.byState[State.RELEARNING] ?: 0).toString(), Modifier.weight(1f))
        }

        Spacer(Modifier.height(28.dp))
        Section("Ответы за две недели")
        Spacer(Modifier.height(12.dp))
        BarChart(stats.activity, highlight = today, unit = "ответов")

        Spacer(Modifier.height(28.dp))
        Section("Сколько придёт на повторение")
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Только повторения: новые карточки не учитываются, просроченные отнесены к сегодня",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        BarChart(stats.forecast, highlight = today, unit = "карточек")
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier, hint: String? = null) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (hint != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A single series of counts over days.
 *
 * One measure, one hue: a second colour here would imply a second category that
 * does not exist. The value is labelled only on the tallest bar and revealed on
 * hover elsewhere, rather than printed above every column.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarChart(data: List<DayCount>, highlight: LocalDate?, unit: String) {
    val peak = data.maxOfOrNull { it.count } ?: 0
    if (peak == 0) {
        Text(
            text = "Пока нет данных",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val barColor = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(140.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            data.forEach { day ->
                val share = day.count.toFloat() / peak.toFloat()
                val isPeak = day.count == peak
                TooltipArea(
                    tooltip = {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 4.dp,
                        ) {
                            Text(
                                text = "${DAY_LABEL.format(day.date)} — ${day.count} $unit",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (isPeak) {
                            Text(
                                text = day.count.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                // Zero still gets a sliver, so an empty day reads as
                                // "nothing happened" rather than "no bar drawn here".
                                .height((if (day.count == 0) 2f else 4f + share * 96f).dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (day.count == 0) axis else barColor),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(axis))
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            data.forEachIndexed { index, day ->
                // Every other label on the long series, all of them on the short one.
                val show = data.size <= 8 || index % 2 == 0 || day.date == highlight
                Text(
                    text = if (show) DAY_LABEL.format(day.date) else "",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (day.date == highlight) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun Double?.asPercent(): String =
    if (this == null) "—" else "${(this * 100).roundToInt()}%"
