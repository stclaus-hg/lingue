package lingue.ui

import lingue.core.Cards
import lingue.core.Direction
import lingue.core.StudyCard
import lingue.core.Word
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordStatusTest {

    private val word = Word(id = 1, term = "cozy", translation = "затишний", createdAt = Instant.now())

    private fun card(direction: Direction, due: Instant, reviews: Int) = StudyCard(
        id = direction.ordinal + 1L,
        word = word,
        direction = direction,
        card = Cards.create(direction.ordinal + 1L, due),
        reviews = reviews,
    )

    private fun bothNew() = listOf(
        card(Direction.RECOGNITION, Instant.now(), reviews = 0),
        card(Direction.PRODUCTION, Instant.now(), reviews = 0),
    )

    @Test
    fun `an unstudied word says so once instead of twice`() {
        // Its cards are due only because they were created in the past, which
        // says nothing about the word — printing two countdowns is noise.
        assertEquals("новое, ещё не изучалось", status(bothNew()))
    }

    @Test
    fun `a card waiting in the queue reports readiness, not a countdown`() {
        val cards = listOf(
            card(Direction.RECOGNITION, Instant.now().minus(Duration.ofHours(2)), reviews = 3),
            card(Direction.PRODUCTION, Instant.now().plus(Duration.ofDays(2)), reviews = 1),
        )

        val line = status(cards)

        assertTrue("узнавание: готово к показу" in line, line)
        assertTrue("производство: через 2 дня" in line, line)
    }

    @Test
    fun `a half-studied word still reports the untouched direction`() {
        val cards = listOf(
            card(Direction.RECOGNITION, Instant.now().plus(Duration.ofDays(1)), reviews = 2),
            card(Direction.PRODUCTION, Instant.now(), reviews = 0),
        )

        val line = status(cards)

        assertTrue("производство: не начато" in line, line)
        assertTrue("узнавание: через 1 день" in line, line)
    }

    @Test
    fun `lapses are surfaced, silence means none`() {
        val clean = status(
            listOf(card(Direction.RECOGNITION, Instant.now().plus(Duration.ofDays(3)), reviews = 4)),
        )
        assertTrue("ошибок" !in clean, clean)

        val lapsed = status(
            listOf(
                card(Direction.RECOGNITION, Instant.now().plus(Duration.ofDays(3)), reviews = 4)
                    .copy(lapses = 2),
            ),
        )
        assertTrue("ошибок 2" in lapsed, lapsed)
    }
}
