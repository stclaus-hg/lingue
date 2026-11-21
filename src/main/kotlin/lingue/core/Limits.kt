package lingue.core

/**
 * Daily caps on how much work enters a session.
 *
 * [newPerDay] counts **words**: a word is new on the day its recognition card is
 * first answered. Its production card opens once recognition has survived a
 * night, and is counted with the repeats from then on — a word that has already
 * started is not new work.
 *
 * The two numbers are not independent. Each card drags a tail of roughly ten
 * reviews behind it before its interval grows long, and a word owns two cards,
 * so a steady diet of N new words a day settles at about 20N reviews a day.
 * Raising [newPerDay] without room in [reviewsPerDay] does not add work later —
 * it adds a backlog.
 *
 * Only [newPerDay] changes the total amount of work. [reviewsPerDay] bounds a
 * single day, which protects the habit, but anything it holds back comes due all
 * the same.
 */
data class Limits(
    val newPerDay: Int = 20,
    val reviewsPerDay: Int = 200,
) {
    companion object {
        val ALLOWED_NEW = 0..50
        val ALLOWED_REVIEWS = 10..500

        /** One card per direction. */
        const val CARDS_PER_WORD = 2

        /** Reviews a single card needs before its interval grows long. */
        private const val REVIEWS_PER_CARD = 10

        /** The review load a steady diet of [newPerDay] new words settles at. */
        fun expectedDailyReviews(newPerDay: Int): Int = newPerDay * CARDS_PER_WORD * REVIEWS_PER_CARD
    }
}
