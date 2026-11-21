package lingue.core

import io.github.openspacedrepetition.Card
import java.time.Instant

/** Creation of FSRS cards, kept in one place so identity is decided once. */
object Cards {

    /**
     * [cardRowId] is the `cards` table row id, not the word id — a word owns one
     * card per direction and their FSRS states must not collide.
     *
     * Left to itself the library derives an id from the wall clock (casting epoch
     * millis to `int`, which overflows, then sleeping a millisecond so successive
     * calls differ). Identity belongs to the database.
     */
    fun create(cardRowId: Long, now: Instant): Card =
        Card.builder()
            .cardId(cardRowId.toInt())
            .due(now)
            .build()
}
