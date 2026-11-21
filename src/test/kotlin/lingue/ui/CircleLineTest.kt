package lingue.ui

import lingue.app.Circle
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class CircleLineTest {

    private val now: Instant = Instant.parse("2026-08-01T09:00:00Z")
    private val inSixDays: Instant = now.plus(Duration.ofDays(6))

    private fun line(circle: Circle) = circleLine(circle, now, inSixDays)

    @Test
    fun `a fresh miss asks for two right answers in a row`() {
        assertEquals("Закрепление ○○ · нужно 2 верных ответа подряд", line(Circle.Going(0, 2, slow = false)))
    }

    @Test
    fun `a forgotten old word asks for one`() {
        assertEquals("Закрепление ○ · нужен 1 верный ответ", line(Circle.Going(0, 1, slow = false)))
    }

    @Test
    fun `halfway shows what is left`() {
        assertEquals("Закрепление ●○ · ещё 1 верный ответ", line(Circle.Going(1, 2, slow = false)))
    }

    @Test
    fun `a slow answer says why the step did not count`() {
        assertEquals("Закрепление ○○ · медленный ответ шаг не засчитывает", line(Circle.Going(0, 2, slow = true)))
    }

    @Test
    fun `a settled word shows its next repeat`() {
        assertEquals("Закреплено ●● · следующий повтор через 6 дней", line(Circle.Graduated(2)))
    }

    @Test
    fun `a word out of answers for today waits for tomorrow`() {
        assertEquals("На сегодня хватит · слово вернётся завтра", line(Circle.Paused))
    }
}
