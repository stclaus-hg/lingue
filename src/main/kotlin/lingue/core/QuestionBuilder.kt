package lingue.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * Assembles the prompt for a card.
 *
 * The answer format follows from the card's [Direction] and never changes as the
 * card matures — see [Direction] for why that matters to the scheduler.
 */
class QuestionBuilder(
    private val random: Random = Random.Default,
) {

    /**
     * Returns `null` when the card cannot be asked in its own format — a
     * recognition card in a collection with no other word to offer as a
     * distractor. The caller skips it.
     *
     * Substituting the other format instead would silently change what the card
     * measures, which is exactly what splitting the directions was meant to stop.
     *
     * [confusedWith] lists word ids this user has previously mistaken for this
     * word, strongest first. [synonyms] lists word ids that mean the same thing
     * and must never appear as options — see [candidates].
     */
    fun build(
        card: StudyCard,
        pool: List<Word>,
        confusedWith: List<Long> = emptyList(),
        synonyms: Set<Long> = emptySet(),
    ): Question? = when (card.direction) {
        Direction.PRODUCTION -> Question.Typing(card)
        Direction.RECOGNITION -> buildChoice(card, pool, confusedWith, synonyms)
    }

    /**
     * Whether [build] would produce a question, without building one.
     *
     * Used to drop unaskable cards before siblings are buried — otherwise a
     * single-word collection would bury the production card in favour of a
     * recognition card that then gets skipped, leaving nothing to study.
     */
    fun canAsk(card: StudyCard, pool: List<Word>, synonyms: Set<Long> = emptySet()): Boolean =
        card.direction == Direction.PRODUCTION || candidates(card.word, pool, synonyms).isNotEmpty()

    private fun buildChoice(
        card: StudyCard,
        pool: List<Word>,
        confusedWith: List<Long>,
        synonyms: Set<Long>,
    ): Question? {
        // Fewer options than usual is a weaker question but still the right kind;
        // no options at all is not a question.
        val distractors = pickDistractors(card.word, pool, confusedWith, synonyms)
        if (distractors.isEmpty()) return null

        val options = (distractors + card.word).shuffled(random)
        return Question.Choice(
            card = card,
            options = options,
            correctIndex = options.indexOfFirst { it.id == card.word.id },
        )
    }

    /**
     * Words that may stand as a wrong answer.
     *
     * Synonyms are excluded outright. An option that is also a correct meaning
     * makes the question unanswerable, and marking it wrong teaches the opposite
     * of what the card is for.
     */
    private fun candidates(word: Word, pool: List<Word>, synonyms: Set<Long>): List<Word> =
        pool.filter { it.id != word.id && it.id !in synonyms && it.translation != word.translation }

    /**
     * Distractors are chosen in layers, strongest signal first, each contributing
     * whatever it can until four options are filled.
     *
     * Full coverage by the strong layers is neither achievable nor desirable: a
     * word with no close neighbour is a word that cannot be confused, so an easy
     * question for it measures the truth rather than being lenient.
     */
    private fun pickDistractors(
        word: Word,
        pool: List<Word>,
        confusedWith: List<Long>,
        synonyms: Set<Long>,
    ): List<Word> {
        val candidates = candidates(word, pool, synonyms)
        if (candidates.isEmpty()) return emptyList()

        val byId = candidates.associateBy(Word::id)
        val picked = LinkedHashSet<Word>()

        // 1. Words this user has actually mistaken for it. Proven confusable for
        //    this person, needs no linguistics, and improves with use.
        confusedWith.mapNotNull(byId::get).forEach { picked.addUpTo(it) }

        // 2. Translations that read alike. The options on screen are translations,
        //    so this is where confusion actually happens — "передбачуваний" against
        //    "непередбачуваний" cannot be answered by guessing.
        candidates.asSequence()
            .filterNot { it in picked }
            .map { it to relativeDistance(word.translation, it.translation) }
            .filter { (_, distance) -> distance <= SIMILAR_TRANSLATION }
            .sortedBy { (_, distance) -> distance }
            .forEach { (candidate, _) -> picked.addUpTo(candidate) }

        // 3. English words sharing a root, for pairs whose translations happen to
        //    look nothing alike.
        candidates.asSequence()
            .filterNot { it in picked }
            .filter { sharesRoot(word.term, it.term) }
            .forEach { picked.addUpTo(it) }

        // 4. Similar length. Weak, but always available, and still better than a
        //    random word of obviously different shape.
        candidates.asSequence()
            .filterNot { it in picked }
            .sortedBy { abs(it.translation.length - word.translation.length) }
            .take(NEIGHBOURHOOD)
            .toList()
            .shuffled(random)
            .forEach { picked.addUpTo(it) }

        return picked.toList()
    }

    private fun LinkedHashSet<Word>.addUpTo(word: Word) {
        if (size < OPTIONS - 1) add(word)
    }

    /** Edit distance as a share of the longer string, so length does not dominate. */
    private fun relativeDistance(a: String, b: String): Double {
        val left = mainVariant(a)
        val right = mainVariant(b)
        val longest = max(left.length, right.length)
        if (longest == 0) return 1.0
        return Grading.levenshtein(left, right).toDouble() / longest
    }

    /** Translations may carry a second reading after a comma or in brackets. */
    private fun mainVariant(translation: String): String =
        translation.substringBefore(',').substringBefore('(').trim().lowercase()

    /**
     * True for pairs like fair/unfair or invent/invention.
     *
     * Stems must match exactly rather than merely share a prefix: a prefix rule
     * pairs "consequence" with "consider". Phrases are excluded because their
     * first word collides constantly — "to make a mistake" against "to make fun
     * of sb" is not a derivational pair.
     */
    internal fun sharesRoot(a: String, b: String): Boolean {
        val left = headOf(a) ?: return false
        val right = headOf(b) ?: return false
        if (left == right) return false
        return stems(left).any { it in stems(right) }
    }

    /** The single word carried by a term, or `null` when the term is a phrase. */
    private fun headOf(term: String): String? {
        val stripped = term.trim().lowercase()
            .removePrefix("to ")
            .removePrefix("an ")
            .removePrefix("a ")
            .removePrefix("the ")
            .trim()
        return stripped.takeIf { it.isNotEmpty() && !it.contains(' ') }
    }

    /**
     * Every plausible root of a word, rather than one "correct" stem.
     *
     * Committing to a single stem means committing to a wrong guess: stripping
     * the longest matching suffix turns "invention" into "inven", which no longer
     * matches "invent", while stripping a leading "in-" from "invent" removes a
     * prefix that was never there. Collecting the candidates and intersecting two
     * sets sidesteps both, because a real pair agrees on at least one form.
     */
    private fun stems(word: String): Set<String> {
        val roots = mutableSetOf(word)

        SUFFIXES.filter { word.endsWith(it) && word.length - it.length >= MIN_STEM }
            .forEach { roots += word.removeSuffix(it) }

        roots.toList().forEach { root ->
            PREFIXES.filter { root.startsWith(it) && root.length - it.length >= MIN_STEM }
                .forEach { roots += root.removePrefix(it) }
        }

        return roots
    }

    private companion object {
        const val OPTIONS = 4

        /** Closest candidates by length to sample the fallback layer from. */
        const val NEIGHBOURHOOD = 12

        /** Edit distance share below which two translations read alike. */
        const val SIMILAR_TRANSLATION = 0.35

        const val MIN_STEM = 4

        val PREFIXES = listOf("un", "in", "im", "dis", "re")

        /** Order is irrelevant — every applicable one is tried. */
        val SUFFIXES = listOf(
            "ability", "ation", "ence", "ance", "tion", "sion", "ion", "ment",
            "ness", "less", "ful", "able", "ible", "ing", "ed", "ly", "es", "s",
            "e", "y",
        )
    }
}
