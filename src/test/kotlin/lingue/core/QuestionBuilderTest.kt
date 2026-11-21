package lingue.core

import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val EPOCH: Instant = Instant.parse("2026-08-02T09:00:00Z")

private fun word(id: Long, term: String, translation: String) =
    Word(id = id, term = term, translation = translation, createdAt = EPOCH)

private fun recognitionCard(word: Word) = StudyCard(
    id = word.id,
    word = word,
    direction = Direction.RECOGNITION,
    card = Cards.create(word.id, EPOCH),
)

/** Words with nothing in common, to pad a pool without influencing the layers. */
private fun filler(from: Int, to: Int) = (from..to).map {
    word(it.toLong(), "filler$it", "щосьнезрозуміле$it")
}

class DistractorLayerTest {

    private val builder = QuestionBuilder(Random(20260802))

    private fun optionIds(question: Question): List<Long> =
        assertIs<Question.Choice>(question).options.map { it.id }

    @Test
    fun `a word previously mistaken for this one is offered again`() {
        val target = word(1, "to pour", "наливати")
        val confused = word(2, "to spill", "розливати")
        val pool = listOf(target, confused) + filler(3, 20)

        val question = assertNotNullQuestion(builder.build(recognitionCard(target), pool, listOf(2L)))

        assertTrue(2L in optionIds(question), "the confused word is the strongest distractor available")
    }

    @Test
    fun `translations that read alike are preferred`() {
        val target = word(1, "predictable", "передбачуваний")
        val lookalike = word(2, "unpredictable", "непередбачуваний")
        val pool = listOf(target, lookalike) + filler(3, 20)

        val question = assertNotNullQuestion(builder.build(recognitionCard(target), pool))

        assertTrue(2L in optionIds(question), "a one-prefix difference cannot be answered by guessing")
    }

    @Test
    fun `same-root words are used when their translations look nothing alike`() {
        val target = word(1, "careful", "обережний")
        val relative = word(2, "careless", "недбалий")
        val pool = listOf(target, relative) + filler(3, 20)

        val question = assertNotNullQuestion(builder.build(recognitionCard(target), pool))

        assertTrue(2L in optionIds(question))
    }

    @Test
    fun `the correct answer is always among the options and correctly indexed`() {
        val target = word(1, "cozy", "затишний")
        val pool = listOf(target) + filler(2, 20)

        val question = assertIs<Question.Choice>(assertNotNullQuestion(builder.build(recognitionCard(target), pool)))

        assertEquals(4, question.options.size)
        assertEquals(target.id, question.options[question.correctIndex].id)
        assertEquals("затишний", question.answer)
    }

    @Test
    fun `options never repeat`() {
        val target = word(1, "cozy", "затишний")
        val pool = listOf(target) + filler(2, 20)

        val question = assertIs<Question.Choice>(assertNotNullQuestion(builder.build(recognitionCard(target), pool)))

        assertEquals(question.options.size, question.options.map { it.id }.toSet().size)
    }

    @Test
    fun `a word whose translation duplicates the answer is never a distractor`() {
        val target = word(1, "a jerk", "покидьок")
        val duplicate = word(2, "a scumbag", "покидьок")
        val pool = listOf(target, duplicate) + filler(3, 20)

        val question = assertNotNullQuestion(builder.build(recognitionCard(target), pool))

        assertFalse(2L in optionIds(question), "two identical options make the question unanswerable")
    }

    @Test
    fun `recognition cannot be asked without a distractor`() {
        val target = word(1, "cozy", "затишний")

        assertNull(builder.build(recognitionCard(target), listOf(target)))
        assertFalse(builder.canAsk(recognitionCard(target), listOf(target)))
    }

    @Test
    fun `production never needs a pool`() {
        val target = word(1, "cozy", "затишний")
        val card = StudyCard(1, target, Direction.PRODUCTION, Cards.create(1, EPOCH))

        assertIs<Question.Typing>(assertNotNullQuestion(builder.build(card, listOf(target))))
        assertTrue(builder.canAsk(card, emptyList()))
    }

    private fun assertNotNullQuestion(question: Question?): Question =
        question ?: error("expected a question")
}

class SharesRootTest {

    private val builder = QuestionBuilder(Random(1))

    @Test
    fun `derivational pairs are recognised`() {
        assertTrue(builder.sharesRoot("fair", "unfair"))
        assertTrue(builder.sharesRoot("predictable", "unpredictable"))
        assertTrue(builder.sharesRoot("careful", "careless"))
        assertTrue(builder.sharesRoot("to invent", "an invention"))
        assertTrue(builder.sharesRoot("necessary", "unnecessary"))
        assertTrue(builder.sharesRoot("instant", "instantly"))
    }

    @Test
    fun `a shared prefix is not a shared root`() {
        // The measured false positive: a prefix rule pairs these two.
        assertFalse(builder.sharesRoot("a consequence", "to consider"))
    }

    @Test
    fun `phrases are not paired by their first word`() {
        assertFalse(builder.sharesRoot("to make a mistake", "to make fun of sb"))
        assertFalse(builder.sharesRoot("to get on the train", "to get off the train"))
    }

    @Test
    fun `a word is not its own relative`() {
        assertFalse(builder.sharesRoot("fair", "fair"))
        assertFalse(builder.sharesRoot("to accept", "accept"))
    }

    @Test
    fun `unrelated words are not paired`() {
        assertFalse(builder.sharesRoot("salmon", "porridge"))
        assertFalse(builder.sharesRoot("a kettle", "a witness"))
    }
}
