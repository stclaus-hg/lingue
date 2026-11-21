package lingue.ui

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatNextDueTest {

    private val now: Instant = Instant.parse("2026-08-01T09:00:00Z")

    private fun after(duration: Duration) = formatNextDue(now, now.plus(duration))

    @Test
    fun `an interval read a moment late still reports the scheduled value`() {
        // The regression: FSRS schedules exactly ten minutes ahead, the panel
        // renders a fraction of a second later, and truncation reported nine.
        val due = now.plus(Duration.ofMinutes(10))
        val rendered = now.plusMillis(300)

        assertEquals("через 10 минут", formatNextDue(rendered, due))
    }

    @Test
    fun `minutes agree in number`() {
        assertEquals("через 1 минуту", after(Duration.ofMinutes(1)))
        assertEquals("через 2 минуты", after(Duration.ofMinutes(2)))
        assertEquals("через 5 минут", after(Duration.ofMinutes(5)))
        assertEquals("через 21 минуту", after(Duration.ofMinutes(21)))
    }

    @Test
    fun `teens take the third form`() {
        assertEquals("через 11 минут", after(Duration.ofMinutes(11)))
        assertEquals("через 12 минут", after(Duration.ofMinutes(12)))
        assertEquals("через 14 минут", after(Duration.ofMinutes(14)))
    }

    @Test
    fun `units roll over instead of overflowing`() {
        assertEquals("через 1 час", after(Duration.ofMinutes(60)))
        assertEquals("через 2 часа", after(Duration.ofMinutes(90)))
        assertEquals("через 1 день", after(Duration.ofHours(25)))
        assertEquals("через 1 месяц", after(Duration.ofDays(31)))
        assertEquals("через 1 год", after(Duration.ofDays(365)))
    }

    @Test
    fun `a due card does not report a negative interval`() {
        assertEquals("меньше чем через минуту", formatNextDue(now, now))
        assertEquals("меньше чем через минуту", formatNextDue(now, now.minus(Duration.ofHours(3))))
    }
}
