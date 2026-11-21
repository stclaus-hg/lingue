package lingue.app

import io.github.openspacedrepetition.Rating
import lingue.core.Direction
import lingue.core.ReviewEngine
import lingue.core.StudyCard
import lingue.data.WordRepository
import java.time.Instant

/**
 * Writes an answer to [card] as if it had been given at [at], through the real
 * scheduler — the way to put a card into a known state on a chosen day.
 */
fun WordRepository.answer(card: StudyCard, rating: Rating, at: Instant): StudyCard {
    val updated = ReviewEngine().apply(card, rating, at)
    update(updated)
    logReview(updated, rating, correct = rating != Rating.AGAIN, elapsedMillis = 1_000, at = at)
    return updated
}

/**
 * Puts the word's recognition card straight into review, answered at [at].
 *
 * A production card opens only once recognition has graduated on an earlier
 * day, so this is how a test reaches typing without living through a night.
 */
fun WordRepository.graduateRecognition(wordId: Long, at: Instant): StudyCard =
    answer(cardsOf(wordId).first { it.direction == Direction.RECOGNITION }, Rating.EASY, at)

/** The cards a trainer would ask, in order, walked without answering any. */
fun Trainer.queuedCards(): List<StudyCard> = buildList {
    while (true) {
        add(question?.card ?: break)
        advance()
    }
}
