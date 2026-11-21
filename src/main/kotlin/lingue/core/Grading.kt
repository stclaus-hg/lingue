package lingue.core

import io.github.openspacedrepetition.Rating
import java.time.Duration

/**
 * Turns an answer into an FSRS rating.
 *
 * Anki makes the user self-assess (Again / Hard / Good / Easy) after seeing the
 * answer. This app grades automatically instead — that is the main reason it
 * exists — so this object is where "how well did I actually know it" is decided.
 * Everything downstream (intervals, difficulty, stability) follows from here.
 */
object Grading {

    /** A correct pick slower than this reads as effortful rather than fluent. */
    private val SLOW_CHOICE: Duration = Duration.ofSeconds(10)

    /** Typing the whole word correctly this fast means it came without searching. */
    private val FAST_TYPING: Duration = Duration.ofSeconds(6)

    private val WHITESPACE = Regex("\\s+")
    private val COMMA_SPACING = Regex("\\s*,\\s*")
    private val FORM_SEPARATORS = Regex("[\\s,]+")
    private const val VERB_FORM_COUNT = 3

    /**
     * Recognition is easier than recall, so a correct pick never earns EASY.
     * Awarding it here would stretch the interval on evidence the user may not
     * be able to reproduce once the options are gone.
     */
    fun forChoice(correct: Boolean, elapsed: Duration): Rating = when {
        !correct -> Rating.AGAIN
        elapsed > SLOW_CHOICE -> Rating.HARD
        else -> Rating.GOOD
    }

    /**
     * Spelling is graded strictly: anything but the exact letters is AGAIN.
     *
     * An earlier version forgave a one-character slip as motor noise. That was
     * the right rule for a card measuring *recall of meaning* — and the wrong one
     * here, because a production card asks whether the word can be written. Under
     * that goal a misspelling is not interference with the measurement, it is the
     * thing being measured.
     *
     * Formatting stays forgiving: case, stray spaces and a leading "to" are not
     * letters of the word (see [normalize]).
     */
    fun forTyping(expected: String, typed: String, elapsed: Duration): Rating {
        if (!isCorrect(expected, typed)) return Rating.AGAIN
        return if (elapsed <= FAST_TYPING) Rating.EASY else Rating.GOOD
    }

    /** Whether the typed answer counts as knowing the word at all. */
    fun isCorrect(expected: String, typed: String): Boolean = matchesExactly(expected, typed)

    /**
     * Case, padding and repeated spaces are never what is being tested. A leading
     * "to " is dropped so that "to run" and "run" are the same answer.
     *
     * Spacing around commas is levelled too, so a list of verb forms can be typed
     * as "go, went, gone" or "go,went,gone" — the separator is punctuation the
     * reader supplies, not a letter of the answer.
     */
    fun normalize(raw: String): String =
        raw.trim()
            .lowercase()
            .replace(WHITESPACE, " ")
            .replace(COMMA_SPACING, ", ")
            .removePrefix("to ")

    /**
     * Whether a spelling is exactly right.
     *
     * The single answer to "is this the same answer", used by grading, by the
     * spelling drill and by the decision to show a diff. They must not each carry
     * their own rule: an earlier version compared the whole stored string here
     * while grading split it into alternatives, so a correct "run" for "to run"
     * was graded right and simultaneously reported as inexact — the card then
     * waited for a keypress and showed a diff of an answer that was fine.
     */
    fun matchesExactly(expected: String, typed: String): Boolean {
        val answer = normalize(asAnswered(expected, typed))
        val variants = accepted(expected)
        return answer.isNotBlank() && answer in variants
    }

    /** "/" and ";" in a term separate equally valid answers, e.g. "lift / elevator". */
    fun accepted(expected: String): List<String> =
        expected.split('/', ';')
            .map(::normalize)
            .filter { it.isNotBlank() }

    /**
     * Whether a term is a list of irregular verb forms, "go, went, gone".
     *
     * Recognised by shape rather than stored as a flag: three comma-separated
     * single words. No other kind of entry is written that way — phrases contain
     * spaces inside a part, alternatives use "/" — so the shape is unambiguous.
     */
    fun isVerbForms(term: String): Boolean {
        val parts = term.split(',').map(String::trim)
        return parts.size == VERB_FORM_COUNT && parts.all { part ->
            part.isNotEmpty() && part.none { it.isWhitespace() || it == '/' || it == ';' }
        }
    }

    /**
     * The answer as it should be compared, shown in the diff and kept in history.
     *
     * For verb forms, spaces work as separators as well as commas: "go went gone"
     * becomes "go, went, gone". The number of forms is not touched, so a missing
     * or extra form still fails. Anywhere else spaces belong to the phrase, and
     * the answer is only trimmed.
     */
    fun asAnswered(expected: String, typed: String): String {
        val trimmed = typed.trim()
        if (!isVerbForms(expected)) return trimmed
        return trimmed.split(FORM_SEPARATORS).filter(String::isNotEmpty).joinToString(", ")
    }

    /** Standard edit distance, two rows instead of a full matrix. */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
