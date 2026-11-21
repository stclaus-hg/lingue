package lingue.app

import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.State
import lingue.core.Direction
import lingue.core.Question
import lingue.data.Database
import lingue.data.WordRepository
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SynonymTest {

    private lateinit var database: Database
    private lateinit var words: WordRepository

    private val utc: ZoneId = ZoneId.of("UTC")
    private val now: Instant = Instant.parse("2026-08-18T09:00:00Z")

    @BeforeTest
    fun setUp() {
        val directory = Files.createTempDirectory("lingue-synonym-test")
        database = Database(directory.resolve("test.db"))
        words = WordRepository(database.connection)
    }

    @AfterTest
    fun tearDown() = database.close()

    private fun trainer() = Trainer(words, zone = utc, clock = { now })

    /** A session of production cards: recognition graduated yesterday, so typing is open. */
    private fun productionSession(): Trainer {
        words.all().forEach { words.graduateRecognition(it.id, at = now.minus(Duration.ofDays(1))) }
        val trainer = trainer()
        trainer.reload()
        return trainer
    }

    @Test
    fun `linking is symmetric`() {
        val first = words.add("besides", "крім того", now = now)
        val second = words.add("other than that", "окрім цього", now = now)

        words.linkSynonyms(first.id, second.id)

        val map = words.allSynonyms()
        assertEquals(setOf(second.id), map[first.id])
        assertEquals(setOf(first.id), map[second.id])
    }

    @Test
    fun `a word is never its own synonym`() {
        val word = words.add("besides", "крім того", now = now)

        words.linkSynonyms(word.id, word.id)

        assertTrue(words.allSynonyms().isEmpty())
    }

    @Test
    fun `a synonym is never offered as a wrong option`() {
        val asked = words.add("besides", "крім того, до того ж", now = now)
        val synonym = words.add("other than that", "крім того, окрім цього", now = now)
        (1..8).forEach { words.add("filler$it", "щосьінше$it", now = now) }
        words.linkSynonyms(asked.id, synonym.id)

        val trainer = trainer()
        trainer.reload()

        // Walk the whole session; the pair must never share a screen.
        var seen = 0
        while (trainer.question != null) {
            val question = trainer.question
            if (question is Question.Choice && question.card.word.id == asked.id) {
                seen++
                assertTrue(
                    question.options.none { it.id == synonym.id },
                    "a synonym as an option makes the question unanswerable",
                )
            }
            trainer.understood()
            (question as? Question.Choice)?.let { trainer.answerChoice(it.correctIndex) }
            trainer.advance()
        }
        // Once as a new word and once more in its learning circle.
        assertEquals(2, seen, "the asked word should have come up twice")
    }

    @Test
    fun `typing a synonym counts as knowing the word`() {
        val asked = words.add("besides", "крім того", now = now)
        val synonym = words.add("other than that", "окрім цього", now = now)
        (1..4).forEach { words.add("filler$it", "щосьінше$it", now = now) }
        words.linkSynonyms(asked.id, synonym.id)

        val trainer = productionSession()
        var checked = false
        while (trainer.question != null) {
            val question = trainer.question
            if (question is Question.Typing && question.card.word.id == asked.id) {
                trainer.answerTyping("other than that")
                assertTrue(
                    assertNotNull(trainer.feedback).correct,
                    "the card asks for a meaning, and a synonym is one",
                )
                checked = true
            }
            trainer.advance()
        }
        assertTrue(checked, "the production card for the asked word never came up")
    }

    @Test
    fun `an unrelated wrong answer is still wrong`() {
        words.add("besides", "крім того", now = now)
        words.add("other than that", "окрім цього", now = now)
        (1..4).forEach { words.add("filler$it", "щосьінше$it", now = now) }

        val trainer = productionSession()
        val question = assertNotNull(trainer.question as? Question.Typing)
        trainer.answerTyping("совершенно постороннее")

        assertEquals(false, assertNotNull(trainer.feedback).correct)
        assertNull(trainer.synonymCandidate, "nothing in the collection matches, so there is nothing to link")
    }

    @Test
    fun `answering with another word from the collection offers the link`() {
        words.add("besides", "крім того", now = now)
        val other = words.add("other than that", "окрім цього", now = now)
        (1..4).forEach { words.add("filler$it", "щосьінше$it", now = now) }

        val trainer = productionSession()
        val question = assertNotNull(trainer.question as? Question.Typing)
        val typed = if (question.card.word.id == other.id) "besides" else "other than that"

        trainer.answerTyping(typed)

        assertEquals(typed, assertNotNull(trainer.synonymCandidate).term)
    }

    @Test
    fun `accepting the link records it and calls off the spelling exercise`() {
        words.add("besides", "крім того", now = now)
        val other = words.add("other than that", "окрім цього", now = now)
        (1..4).forEach { words.add("filler$it", "щосьінше$it", now = now) }

        val trainer = productionSession()
        val question = assertNotNull(trainer.question as? Question.Typing)
        val typed = if (question.card.word.id == other.id) "besides" else "other than that"
        trainer.answerTyping(typed)
        assertNotNull(trainer.drill, "a wrong production answer starts the exercise")

        trainer.acceptSynonym()

        assertNull(trainer.synonymCandidate)
        assertNull(trainer.drill, "there is no misspelling to repair — it was a different valid word")
        assertTrue(words.allSynonyms().isNotEmpty())
    }

    /**
     * An old word whose typing card is due today and in review, so a miss sends
     * it into relearning — the case of a synonym typed for a word long known.
     */
    private fun dueTypingSession(asked: String, other: String): Pair<Trainer, Long> {
        val monthAgo = now.minus(Duration.ofDays(30))
        val askedWord = words.add(asked, "крім того", now = monthAgo)
        words.add(other, "окрім цього", now = monthAgo)
        (1..4).forEach { words.add("filler$it", "щосьінше$it", now = monthAgo) }
        words.all().forEach { words.graduateRecognition(it.id, at = now.minus(Duration.ofDays(1))) }
        val production = words.cardsOf(askedWord.id).first { it.direction == Direction.PRODUCTION }
        words.answer(production, Rating.EASY, at = monthAgo)
        val trainer = trainer().apply { reload() }
        while (trainer.question?.card?.id != production.id) {
            assertNotNull(trainer.question, "the asked word's typing card never came up")
            trainer.advance()
        }
        return trainer to production.id
    }

    private fun lastReview(cardId: Long): Pair<Int, Int> =
        database.connection.prepareStatement(
            "SELECT rating, correct FROM reviews WHERE card_id = ? ORDER BY id DESC LIMIT 1",
        ).use { statement ->
            statement.setLong(1, cardId)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) to rows.getInt(2) }
        }

    @Test
    fun `accepting the link turns the miss into a right answer`() {
        val (trainer, cardId) = dueTypingSession("besides", "other than that")
        val lapsesBefore = assertNotNull(words.findCard(cardId)).lapses
        trainer.answerTyping("other than that")
        assertEquals(false, assertNotNull(trainer.feedback).correct)

        trainer.acceptSynonym()

        assertTrue(assertNotNull(trainer.feedback).correct, "the panel now says it was right")
        val (rating, correct) = lastReview(cardId)
        assertTrue(rating != Rating.AGAIN.value, "the review is no longer a miss")
        assertEquals(1, correct)
        val card = assertNotNull(words.findCard(cardId))
        assertEquals(State.REVIEW, card.state, "a word known all along does not go into relearning")
        assertEquals(lapsesBefore, card.lapses)
        assertTrue(words.misspellings().none { it.typed == "other than that" }, "a synonym is not a misspelling")
    }

    @Test
    fun `a word whose miss was a synonym does not come round again`() {
        val (trainer, cardId) = dueTypingSession("besides", "other than that")
        trainer.answerTyping("other than that")

        trainer.acceptSynonym()

        assertTrue(trainer.queuedCards().drop(1).none { it.id == cardId })
    }

    @Test
    fun `picking a synonym's translation counts once the link is accepted`() {
        val monthAgo = now.minus(Duration.ofDays(30))
        val asked = words.add("besides", "крім того", now = monthAgo)
        (1..4).forEach { words.add("filler$it", "щосьінше$it", now = monthAgo) }
        // Both directions in review and due; burying keeps recognition, the older card.
        words.cardsOf(asked.id).forEach { words.answer(it, Rating.EASY, at = monthAgo) }
        val recognition = words.cardsOf(asked.id).first { it.direction == Direction.RECOGNITION }
        val trainer = trainer().apply { reload() }
        while (trainer.question?.card?.id != recognition.id) {
            assertNotNull(trainer.question, "the asked word never came up")
            trainer.advance()
        }
        val question = assertNotNull(trainer.question as? Question.Choice)
        trainer.understood()
        trainer.answerChoice((question.correctIndex + 1) % question.options.size)

        trainer.acceptSynonym()

        assertTrue(assertNotNull(trainer.feedback).correct)
        assertEquals(1, lastReview(recognition.id).second)
        assertEquals(State.REVIEW, assertNotNull(words.findCard(recognition.id)).state)
    }
}
