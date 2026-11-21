package lingue.core

import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.State
import java.time.Instant

/** A vocabulary entry. Content only — scheduling lives on its [StudyCard]s. */
data class Word(
    val id: Long,
    val term: String,
    val translation: String,
    val example: String? = null,
    val createdAt: Instant,
)

/**
 * Which way round a word is tested.
 *
 * Recognition and production are separate abilities: being able to pick the
 * meaning of a word out of four options says little about being able to produce
 * it unprompted. Each direction therefore gets its own card with its own FSRS
 * state, so the scheduler models one skill per card instead of averaging two.
 *
 * The answer format is fixed per direction on purpose. If the task changed as a
 * card matured, the resulting failure would look to FSRS like forgetting rather
 * than a harder question, and it would lower stability for the wrong reason.
 */
enum class Direction {
    /** The English term is shown; the translation is picked from options. */
    RECOGNITION,

    /** The translation is shown; the English term is typed out. */
    PRODUCTION;

    val mode: TrainingMode
        get() = when (this) {
            RECOGNITION -> TrainingMode.CHOICE
            PRODUCTION -> TrainingMode.TYPING
        }
}

/**
 * One direction of one word, together with its scheduling state.
 *
 * [card] belongs to the FSRS library and is persisted verbatim as JSON, so
 * upgrading the algorithm never becomes a database migration.
 */
data class StudyCard(
    val id: Long,
    val word: Word,
    val direction: Direction,
    val card: Card,
    val reviews: Int = 0,
    val lapses: Int = 0,
) {
    val due: Instant get() = card.due
    val state: State get() = card.state
    val mode: TrainingMode get() = direction.mode

    fun isDue(now: Instant): Boolean = !due.isAfter(now)
}

enum class TrainingMode { CHOICE, TYPING }

/** A single prompt shown to the user. */
sealed interface Question {
    val card: StudyCard
    val mode: TrainingMode
    val prompt: String

    val word: Word get() = card.word

    /**
     * Recognition — the English term is shown, the translation is picked.
     *
     * Options are whole words rather than translation strings so that a wrong
     * pick can be recorded by id: the word a user actually confuses with this one
     * is the best distractor to offer next time.
     */
    data class Choice(
        override val card: StudyCard,
        val options: List<Word>,
        val correctIndex: Int,
    ) : Question {
        override val mode = TrainingMode.CHOICE
        override val prompt: String get() = card.word.term
        val labels: List<String> get() = options.map { it.translation }
        val answer: String get() = options[correctIndex].translation
    }

    /** Production — the translation is shown, the English term is typed. */
    data class Typing(
        override val card: StudyCard,
    ) : Question {
        override val mode = TrainingMode.TYPING
        override val prompt: String get() = card.word.translation
        val expected: String get() = card.word.term
    }
}
