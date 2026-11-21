package lingue.core

import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.Scheduler
import java.time.Instant

/**
 * Thin wrapper over the FSRS scheduler.
 *
 * Everything the algorithm knows lives inside the card's FSRS state; this class
 * only decides when to call it and how the surrounding counters change. Keeping
 * the wrapper this thin is deliberate — the scheduling maths stays in the
 * upstream library, which ships its own test suite, instead of being
 * reimplemented here.
 */
class ReviewEngine(
    private val scheduler: Scheduler = Scheduler.builder().build(),
) {

    fun apply(card: StudyCard, rating: Rating, now: Instant = Instant.now()): StudyCard {
        val scheduled = scheduler.reviewCard(card.card, rating, now, null).card()
        return card.copy(
            card = scheduled,
            reviews = card.reviews + 1,
            lapses = card.lapses + if (rating == Rating.AGAIN) 1 else 0,
        )
    }

    /** Probability of recalling this direction of the word right now. */
    fun retrievability(card: StudyCard, now: Instant = Instant.now()): Double =
        scheduler.getCardRetrievability(card.card, now)
}
