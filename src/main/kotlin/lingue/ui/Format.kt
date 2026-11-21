package lingue.ui

import lingue.app.Circle
import java.time.Duration
import java.time.Instant

/**
 * Russian plural agreement: 1 минута, 2 минуты, 5 минут.
 *
 * The rule keys off the last digit, except in the teens, where everything takes
 * the third form.
 */
private fun plural(count: Long, one: String, few: String, many: String): String {
    val lastTwo = count % 100
    if (lastTwo in 11..14) return many
    return when (count % 10) {
        1L -> one
        2L, 3L, 4L -> few
        else -> many
    }
}

/**
 * "через 10 минут" — when the word comes back.
 *
 * Every unit is rounded rather than truncated. A card scheduled exactly ten
 * minutes ahead is read a fraction of a second later, and truncation would
 * report nine — a number the scheduler never chose.
 */
fun formatNextDue(from: Instant, to: Instant): String {
    val seconds = Duration.between(from, to).seconds.coerceAtLeast(0)
    if (seconds < 45) return "меньше чем через минуту"

    val minutes = Math.round(seconds / 60.0)
    if (minutes < 60) return "через $minutes ${plural(minutes, "минуту", "минуты", "минут")}"

    val hours = Math.round(seconds / 3600.0)
    if (hours < 24) return "через $hours ${plural(hours, "час", "часа", "часов")}"

    val days = Math.round(seconds / 86_400.0)
    if (days < 30) return "через $days ${plural(days, "день", "дня", "дней")}"

    if (days < 365) {
        val months = Math.round(days / 30.0)
        return "через $months ${plural(months, "месяц", "месяца", "месяцев")}"
    }

    val years = Math.round(days / 365.0)
    return "через $years ${plural(years, "год", "года", "лет")}"
}

fun formatCount(count: Int, one: String, few: String, many: String): String =
    "$count ${plural(count.toLong(), one, few, many)}"

/**
 * "Закрепление ●○ · ещё 1 верный ответ" — the answer panel's line for a word in
 * the learning circle, in place of the next-repeat line an ordinary answer gets.
 */
fun circleLine(circle: Circle, now: Instant, nextDue: Instant): String = when (circle) {
    is Circle.Going -> {
        val dots = "●".repeat(circle.done) + "○".repeat(circle.total - circle.done)
        val left = circle.total - circle.done
        val note = when {
            circle.slow -> "медленный ответ шаг не засчитывает"
            circle.done == 0 && left == 1 -> "нужен 1 верный ответ"
            circle.done == 0 -> "нужно ${formatCount(left, "верный ответ", "верных ответа", "верных ответов")} подряд"
            else -> "ещё ${formatCount(left, "верный ответ", "верных ответа", "верных ответов")}"
        }
        "Закрепление $dots · $note"
    }
    is Circle.Graduated -> "Закреплено ${"●".repeat(circle.total)} · следующий повтор ${formatNextDue(now, nextDue)}"
    Circle.Paused -> "На сегодня хватит · слово вернётся завтра"
}
