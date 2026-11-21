package lingue.core

import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.Scheduler
import io.github.openspacedrepetition.State
import java.time.Duration
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
    // The library's own defaults, stated here because the trainer needs to know
    // how many there are and the scheduler does not say.
    private val learningSteps: List<Duration> = listOf(Duration.ofMinutes(1), Duration.ofMinutes(10)),
    private val relearningSteps: List<Duration> = listOf(Duration.ofMinutes(10)),
) {
    private val scheduler: Scheduler = Scheduler.builder()
        .learningSteps(learningSteps.toTypedArray())
        .relearningSteps(relearningSteps.toTypedArray())
        .build()

    /**
     * Right answers in a row a card in [state] needs to leave its steps: two for
     * a word being learned, one for a forgotten word being relearned, none in review.
     */
    fun stepsIn(state: State): Int = when (state) {
        State.LEARNING -> learningSteps.size
        State.RELEARNING -> relearningSteps.size
        else -> 0
    }

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
