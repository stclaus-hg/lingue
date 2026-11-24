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
    /**
     * A typing card is first asked once its choice card has settled — the word
     * is already known by then, and only its spelling is new. One step, so one
     * right answer settles it, and one right answer after a miss.
     */
    private val typingLearningSteps: List<Duration> = listOf(Duration.ofMinutes(10)),
) {
    private val choiceScheduler: Scheduler = scheduler(learningSteps)
    private val typingScheduler: Scheduler = scheduler(typingLearningSteps)

    private fun scheduler(learning: List<Duration>): Scheduler = Scheduler.builder()
        .learningSteps(learning.toTypedArray())
        .relearningSteps(relearningSteps.toTypedArray())
        .build()

    private fun schedulerFor(card: StudyCard): Scheduler =
        if (card.direction == Direction.PRODUCTION) typingScheduler else choiceScheduler

    /**
     * Right answers in a row [card] needs to leave the steps of [state]: two for
     * a word being learned (one for its typing card), one for a forgotten word
     * being relearned, none in review.
     */
    fun stepsIn(card: StudyCard, state: State = card.state): Int = when (state) {
        State.LEARNING -> if (card.direction == Direction.PRODUCTION) typingLearningSteps.size else learningSteps.size
        State.RELEARNING -> relearningSteps.size
        else -> 0
    }

    fun apply(card: StudyCard, rating: Rating, now: Instant = Instant.now()): StudyCard {
        val scheduled = schedulerFor(card).reviewCard(card.card, rating, now, null).card()
        return card.copy(
            card = scheduled,
            reviews = card.reviews + 1,
            lapses = card.lapses + if (rating == Rating.AGAIN) 1 else 0,
        )
    }

    /** Probability of recalling this direction of the word right now. */
    fun retrievability(card: StudyCard, now: Instant = Instant.now()): Double =
        schedulerFor(card).getCardRetrievability(card.card, now)
}
