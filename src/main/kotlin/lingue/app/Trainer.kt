package lingue.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.State
import lingue.core.Direction
import lingue.core.Grading
import lingue.core.Limits
import lingue.core.Question
import lingue.core.QuestionBuilder
import lingue.core.ReviewEngine
import lingue.core.StudyCard
import lingue.core.Word
import lingue.data.AnswerSpan
import lingue.data.WordRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Repetitions needed to fix a spelling once it has gone wrong. */
const val DRILL_REPETITIONS = 3

/** Other cards asked between two asks of a card that is still being learned. */
const val REQUEUE_GAP = 4

/** Answers a card may take in one session before it is left to its schedule. */
const val CIRCLE_ANSWER_LIMIT = 4

/** What the user is told after answering. */
data class Feedback(
    /**
     * Also means letter-perfect on a typing card: spelling is graded strictly, so
     * there is no "known but misspelled" state to distinguish.
     */
    val correct: Boolean,
    val expected: String,
    val given: String,
    val rating: Rating,
    val nextDue: Instant,
    /** Where the word stands in the learning circle; `null` for an ordinary repeat. */
    val circle: Circle? = null,
)

/**
 * A word's way through the learning circle, as the answer panel shows it.
 *
 * Without it a right answer that brings the word back again looks like the
 * session going in circles — the rule (two right answers in a row, a slow one
 * not counting) is FSRS's and invisible otherwise.
 */
sealed interface Circle {
    /** Comes back a few cards later; [done] of [total] right answers in a row given. */
    data class Going(val done: Int, val total: Int, val slow: Boolean) : Circle

    /** Left the circle with this answer, after [total] right answers in a row. */
    data class Graduated(val total: Int) : Circle

    /** Still unlearned, but out of answers for today. */
    data object Paused : Circle
}

/**
 * A spelling exercise, run after the review has already been graded.
 *
 * It never feeds back into the scheduler. The rating was decided by the first
 * answer; letting repetitions improve it would mean a card could be talked up to
 * a good score, and the schedule would stop describing what is actually known.
 */
data class Drill(
    val word: Word,
    val expected: String,
    val required: Int,
    val done: Int = 0,
    val lastAttemptWrong: Boolean = false,
    /** Distinguishes one attempt from the next, so the input field can reset. */
    val attempts: Int = 0,
) {
    /** The first repetition is copied from the model; the rest are from memory. */
    val showsModel: Boolean get() = done == 0
}

data class Progress(
    val remaining: Int = 0,
    /**
     * Cards in the queue that have never been answered.
     *
     * Reported apart from repeats because "new card" and "new word" are not the
     * same thing: the production sibling of a word whose recognition was learned
     * last week is an unseen card, and it arrives as a typing question. Lumping
     * the two together made the session look like it was ignoring the setting.
     */
    val newRemaining: Int = 0,
    val repeatRemaining: Int = 0,
    val answeredThisSession: Int = 0,
    /** Words introduced today — what the new-word cap spends. */
    val newToday: Int = 0,
    /** Started cards answered today, each once — what the review cap spends. */
    val reviewsToday: Int = 0,
    val words: Int = 0,
    /**
     * Cards due but held back by a cap — the next batch, split by kind.
     *
     * The two are not the same problem and must not share a number. An overdue
     * repeat is work already owed and it compounds; an unseen card owes nothing,
     * it is simply queued behind the daily allowance. Reported together, a
     * healthy collection looks like a crisis.
     */
    val waitingNew: Int = 0,
    val waitingRepeats: Int = 0,
    val limits: Limits = Limits(),
    /** The day's caps as currently extended; see Trainer.takeAnotherDay. */
    val allowedNew: Int = limits.newPerDay,
    val allowedReviews: Int = limits.reviewsPerDay,
) {
    /** True when cards are due but a daily cap is holding them back. */
    val waiting: Int get() = waitingNew + waitingRepeats

    val budgetSpent: Boolean
        get() = waiting > 0
}

/**
 * Drives a training session: what to ask, how to grade it, what to persist.
 *
 * This is the seam between the pure domain in `core` and the Compose UI. The UI
 * reads state and calls the verbs below; it never touches the scheduler or the
 * database directly.
 */
class Trainer(
    private val words: WordRepository,
    private val engine: ReviewEngine = ReviewEngine(),
    private val builder: QuestionBuilder = QuestionBuilder(),
    limits: Limits = Limits(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Instant = Instant::now,
) {

    /** Applied when the queue is next rebuilt — see [reload]. */
    var limits: Limits = limits

    var question by mutableStateOf<Question?>(null)
        private set

    var feedback by mutableStateOf<Feedback?>(null)
        private set

    var progress by mutableStateOf(Progress())
        private set

    var drill by mutableStateOf<Drill?>(null)
        private set

    /**
     * True while a brand-new word is being shown rather than asked.
     *
     * The question is already chosen — [question] holds it — but a word seen for
     * the first time cannot be recognised, only guessed, and a lucky guess would
     * be recorded as knowledge. So the word is introduced first and answers are
     * ignored until [understood] or [alreadyKnown]. Nothing reaches the scheduler
     * or the history: an introduction is not a review.
     */
    var introducing by mutableStateOf(false)
        private set

    /** The card the user said they already know, until its answer is in. */
    private var claimedKnown: Long? = null

    /**
     * The word the user just answered with, when it was not the expected one but
     * is a real word from the collection.
     *
     * Offering to mark it a synonym only makes sense for a concrete pair, and this
     * is the moment the pair is visible — nobody can list their synonyms upfront.
     */
    var synonymCandidate by mutableStateOf<Word?>(null)
        private set

    private val queue = ArrayDeque<StudyCard>()
    private var pool: List<Word> = emptyList()
    private var confusions: Map<Long, List<Long>> = emptyMap()
    private var synonyms: Map<Long, Set<Long>> = emptyMap()
    private var askedAt: Instant = Instant.EPOCH
    private var answered = 0
    private var waitingNew = 0
    private var waitingRepeats = 0
    private var extraDays = 0

    /**
     * Answers given to each card today, while this trainer has been running.
     *
     * Lives here rather than in the queue because the queue is rebuilt whenever
     * the user comes back from adding a word, and a cap that reset on every
     * rebuild would not cap anything. It does reset with the day: an app left
     * open overnight must not carry yesterday's exhausted cards into today.
     */
    private val sessionAnswers = mutableMapOf<Long, Int>()
    private var sessionDay: LocalDate? = null

    private fun answersOn(day: LocalDate): MutableMap<Long, Int> {
        if (day != sessionDay) {
            sessionAnswers.clear()
            sessionDay = day
        }
        return sessionAnswers
    }

    /**
     * The day's allowance, extended by [takeAnotherDay].
     *
     * The daily cap exists to stop the app handing out more work than can be
     * sustained — not to stop someone who wants to keep going. Clearing a backlog
     * takes more than one day's worth by definition, so the cap is a default, and
     * the user can draw the next day forward as often as they like.
     */
    private fun allowance(perDay: Int): Int = perDay * (1 + extraDays)

    /**
     * Rebuilds the queue from scratch. Safe to call whenever the word list changes.
     *
     * The session is assembled from three parts:
     *
     * 1. Cards in today's circle — answered today and still in FSRS's learning
     *    or relearning steps. They are not subject to either cap, since a word
     *    abandoned halfway through being learned wastes the answers already
     *    spent on it, and they are taken even before they are due, so adding a
     *    word mid-session does not throw them out of the queue.
     * 2. Repeats — everything else already started, plus production cards that
     *    have just opened, bounded by the review cap.
     * 3. New words, bounded by the new-word cap.
     *
     * Repeats and new words come in that order; today's circle is slotted in
     * after the first [REQUEUE_GAP] of them, keeping the gap it had before the
     * rebuild.
     */
    fun reload() {
        val now = clock()
        // Derived from the same clock the cards are scheduled against. Reading the
        // wall clock here would let the day's counters drift from the schedule.
        val today = LocalDate.ofInstant(now, zone)
        val startOfToday = today.atStartOfDay(zone).toInstant()

        pool = words.all()
        confusions = words.allConfusions()
        synonyms = words.allSynonyms()
        val newToday = words.introducedOn(today, zone)
        val repeatsToday = words.repeatsOn(today, zone)

        val newBudget = (allowance(limits.newPerDay) - newToday).coerceAtLeast(0)
        val reviewBudget = (allowance(limits.reviewsPerDay) - repeatsToday).coerceAtLeast(0)

        val cards = words.allCards()
        val spans = words.answerSpans()
        val recognitionOf = cards.filter { it.direction == Direction.RECOGNITION }.associateBy { it.word.id }

        fun askable(card: StudyCard) = builder.canAsk(card, pool, synonymsOf(card.word.id))

        fun inTodaysCircle(card: StudyCard) =
            card.isLearning() && spans[card.id]?.last?.isBefore(startOfToday) == false

        val learning = cards
            .filter(::inTodaysCircle)
            .filter { (answersOn(today)[it.id] ?: 0) < CIRCLE_ANSWER_LIMIT }
            // Longest since answered first, so the word just missed comes last.
            .sortedBy { spans[it.id]?.last }
            .filter(::askable)
        val learningIds = learning.mapTo(mutableSetOf()) { it.id }

        // A card that has used up its answers today waits for another session.
        // One left mid-learning on an earlier day is an ordinary repeat: letting
        // every abandoned card past the cap would flood a single session.
        val due = words.dueCards(now)
            .filter { !inTodaysCircle(it) }
            .filter { isOpen(it, recognitionOf, spans, startOfToday, ::askable) }
            .filter(::askable)

        val buried = burySiblings(learning + due)
        val (fresh, started) = buried.partition { it.reviews == 0 && it.direction == Direction.RECOGNITION }
        val (inCircle, repeats) = started.partition { it.id in learningIds }

        val takeNew = newBudget.coerceAtMost(fresh.size)
        val takeRepeats = reviewBudget.coerceAtMost(repeats.size)
        // Exactly what the caps held back — the amount another allowance releases.
        // Counting everything due instead would promise buried siblings too.
        waitingNew = fresh.size - takeNew
        waitingRepeats = repeats.size - takeRepeats

        // The circle keeps its gap across a rebuild: a word missed seconds ago is
        // not asked first, while its answer is still on screen in the user's head.
        val rest = repeats.take(takeRepeats) + fresh.take(takeNew)
        queue.clear()
        queue.addAll(rest.take(REQUEUE_GAP))
        queue.addAll(inCircle)
        queue.addAll(rest.drop(REQUEUE_GAP))

        answered = 0
        feedback = null
        question = null
        publishProgress(newToday, repeatsToday)
        advance()
    }

    /**
     * Whether a card may be asked at all today.
     *
     * Only an unseen production card can be closed. Typing a word is pointless
     * until its meaning is held, so production opens once recognition is in
     * review and was first answered on an earlier day — the word has survived a
     * night. A recognition card that has lapsed into relearning closes it again
     * until the meaning is back.
     *
     * A recognition card that cannot be asked — no other word to offer as a
     * wrong option — cannot open anything, so production stays open; otherwise
     * a one-word collection would have nothing to study.
     */
    private fun isOpen(
        card: StudyCard,
        recognitionOf: Map<Long, StudyCard>,
        spans: Map<Long, AnswerSpan>,
        startOfToday: Instant,
        askable: (StudyCard) -> Boolean,
    ): Boolean {
        if (card.direction != Direction.PRODUCTION || card.reviews > 0) return true
        val recognition = recognitionOf[card.word.id] ?: return true
        if (!askable(recognition)) return true
        if (recognition.state != State.REVIEW) return false
        val first = spans[recognition.id]?.first ?: return false
        return first.isBefore(startOfToday)
    }

    private fun StudyCard.isLearning(): Boolean =
        reviews > 0 && (state == State.LEARNING || state == State.RELEARNING)

    /**
     * Keeps at most one direction of a word per session.
     *
     * Recognising `cozy → уютный` moments before being asked to produce
     * `уютный → cozy` tests short-term memory of the previous card rather than
     * the word itself, and hands FSRS an answer that looks better than the truth.
     * Anki calls this burying siblings. The first card of a word in [cards] wins,
     * which is why cards still being learned are passed in first.
     */
    private fun burySiblings(cards: List<StudyCard>): List<StudyCard> {
        val seen = mutableSetOf<Long>()
        return cards.filter { seen.add(it.word.id) }
    }

    /** One repetition of the spelling drill. Wrong attempts do not count down. */
    fun submitDrill(typed: String) {
        val current = drill ?: return
        val attempts = current.attempts + 1

        if (!Grading.matchesExactly(current.expected, typed)) {
            drill = current.copy(lastAttemptWrong = true, attempts = attempts)
            return
        }

        val done = current.done + 1
        if (done >= current.required) {
            // Finishing the exercise ends the card. Returning to the verdict panel
            // would ask for one more keypress to acknowledge an answer that has
            // just been written out correctly three times.
            advance()
            return
        }

        drill = current.copy(done = done, lastAttemptWrong = false, attempts = attempts)
    }

    /**
     * Abandons the exercise, returning to the verdict.
     *
     * An exercise with no way out stops being practice and becomes a reason to
     * close the app, which costs far more than one unlearned spelling. Unlike
     * completing it, skipping does not move on: whoever skips has not yet read
     * the correct spelling, and that is still on screen.
     */
    fun skipDrill() {
        drill = null
    }

    /**
     * Draws the next day's allowance forward and rebuilds the queue.
     *
     * Session-scoped: restarting the app puts the ordinary cap back, so an evening
     * of catching up cannot quietly become the new normal.
     */
    fun takeAnotherDay() {
        extraDays++
        reload()
    }

    /** Moves to the next question, or to `null` when the session is done. */
    fun advance() {
        feedback = null
        drill = null
        synonymCandidate = null
        missed = null

        // A card the builder cannot ask in its own format is skipped rather than
        // asked in the other one; see QuestionBuilder.build.
        var next: Question? = null
        while (next == null) {
            val card = queue.removeFirstOrNull() ?: break
            next = builder.build(
                card = card,
                pool = pool,
                confusedWith = confusions[card.word.id].orEmpty(),
                synonyms = synonymsOf(card.word.id),
            )
        }

        question = next
        introducing = next is Question.Choice && next.card.reviews == 0
        claimedKnown = null
        askedAt = clock()
        publishProgress()
    }

    /** The introduction has been read; ask the word now. */
    fun understood() {
        if (!introducing) return
        introducing = false
        // Reading the word is not answering it: timing starts from here, or a
        // careful read would turn the first answer into a slow one.
        askedAt = clock()
    }

    /**
     * The word was learned elsewhere — skip straight to the question.
     *
     * A correct answer then counts as easy and the word leaves the session at
     * once; a wrong one is an ordinary miss and the word is learned like any
     * other, so a mistaken claim costs only a little time.
     */
    fun alreadyKnown() {
        if (!introducing) return
        understood()
        claimedKnown = question?.card?.id
    }

    fun answerChoice(index: Int) {
        val current = question as? Question.Choice ?: return
        if (feedback != null || introducing) return

        val correct = index == current.correctIndex
        val picked = current.options.getOrNull(index)
        val elapsed = elapsed()
        // Prior knowledge claimed and then shown without a hint is what EASY is for.
        val rating = if (correct && claimedKnown == current.card.id) Rating.EASY else Grading.forChoice(correct, elapsed)
        commit(
            card = current.card,
            rating = rating,
            correct = correct,
            given = picked?.translation.orEmpty(),
            expected = current.answer,
            elapsed = elapsed,
            // Only a wrong pick says anything about what gets confused.
            confusedWith = picked?.id?.takeIf { !correct },
            candidate = picked?.takeIf { !correct },
            ratingIfSynonym = { Grading.forChoice(true, elapsed) },
        )
    }

    /**
     * The user does not know the word and says so instead of guessing.
     *
     * A miss like any other — the correct answer is shown and the card enters
     * its relearning circle — except that nothing was picked, so there is no
     * confusion to record and no word to offer as a synonym.
     */
    fun dontKnow() {
        val current = question as? Question.Choice ?: return
        if (feedback != null || introducing) return

        commit(
            card = current.card,
            rating = Rating.AGAIN,
            correct = false,
            given = "",
            expected = current.answer,
            elapsed = elapsed(),
        )
    }

    fun answerTyping(typed: String) {
        val current = question as? Question.Typing ?: return
        if (feedback != null) return

        val elapsed = elapsed()
        // A word marked as a synonym is a correct answer to its partner's card:
        // the card asks for a meaning, and the user supplied one.
        val accepted = (listOf(current.expected) + synonymTerms(current.card.word.id))
            .firstOrNull { Grading.isCorrect(it, typed) }

        commit(
            card = current.card,
            rating = if (accepted == null) Rating.AGAIN else Grading.forTyping(accepted, typed, elapsed),
            correct = accepted != null,
            given = Grading.asAnswered(current.expected, typed),
            expected = current.expected,
            elapsed = elapsed,
            answerText = Grading.asAnswered(current.expected, typed),
            candidate = if (accepted == null) wordWithTerm(typed) else null,
            ratingIfSynonym = { synonym -> Grading.forTyping(synonym.term, typed, elapsed) },
        )
    }

    private fun synonymsOf(wordId: Long): Set<Long> = synonyms[wordId].orEmpty()

    private fun synonymTerms(wordId: Long): List<String> =
        synonymsOf(wordId).mapNotNull { id -> pool.firstOrNull { it.id == id }?.term }

    /** The collection's own word matching what was typed, if there is one. */
    private fun wordWithTerm(typed: String): Word? =
        pool.firstOrNull { Grading.matchesExactly(it.term, typed) }

    /**
     * Records that the answer given means the same as the one expected.
     *
     * The answer just given is graded again as right: it was a valid meaning,
     * not a lapse, and leaving it a miss sent a long-known word round the
     * learning circle. This is not a kinder guess put into the memory model —
     * the user did recall a correct answer, the pair simply was not known yet.
     */
    fun acceptSynonym() {
        val candidate = synonymCandidate ?: return
        val asked = question?.card?.word ?: return

        words.linkSynonyms(asked.id, candidate.id)
        synonyms = words.allSynonyms()
        synonymCandidate = null
        regradeAsRight(candidate)

        // The spelling exercise was started by what looked like a misspelling and
        // turned out to be a different valid word. There is nothing to repair.
        drill = null
    }

    /** The answer just given, while it was a miss that a synonym could still turn right. */
    private class Missed(val before: StudyCard, val at: Instant, val ratingIfSynonym: (Word) -> Rating)

    private var missed: Missed? = null

    /**
     * Grades the miss just made again, as the right answer it turned out to be.
     *
     * The answer was [synonym] — a valid meaning, not a lapse — so the card is
     * scheduled from its state before the answer, as if the synonym had been
     * known all along, and leaves the circle unless that grade keeps it there.
     */
    private fun regradeAsRight(synonym: Word) {
        val miss = missed ?: return
        val shown = feedback ?: return
        missed = null

        val rating = miss.ratingIfSynonym(synonym)
        val updated = engine.apply(miss.before, rating, miss.at)
        words.update(updated)
        words.regradeLastReview(updated.id, rating, correct = true)

        val day = LocalDate.ofInstant(miss.at, zone)
        val answersToday = answersOn(day)
        queue.removeAll { it.id == updated.id }
        if (updated.isLearning() && (answersToday[updated.id] ?: 0) < CIRCLE_ANSWER_LIMIT) {
            queue.add(requeuePosition(answersToday), updated)
        }

        feedback = shown.copy(
            correct = true,
            rating = rating,
            nextDue = updated.due,
            circle = circleOf(miss.before, updated, rating, correct = true, answers = answersToday[updated.id] ?: 1),
        )
        publishProgress(words.introducedOn(day, zone), words.repeatsOn(day, zone))
    }

    private fun circleOf(before: StudyCard, after: StudyCard, rating: Rating, correct: Boolean, answers: Int): Circle? {
        if (after.state == State.REVIEW) {
            val total = engine.stepsIn(before.state)
            return if (total > 0) Circle.Graduated(total) else null
        }
        if (answers >= CIRCLE_ANSWER_LIMIT) return Circle.Paused
        return Circle.Going(
            done = after.card.step ?: 0,
            total = engine.stepsIn(after.state),
            // HARD keeps a card on its step: right, but not a step forward.
            slow = correct && rating == Rating.HARD,
        )
    }

    /**
     * Where a card going round the circle is put back: after the next
     * [REQUEUE_GAP] cards that are not themselves going round it.
     *
     * Counting every card instead let several missed words take over the head
     * of the queue — each went back behind the others, so with five of them
     * circling nothing else was asked until all five left. Cards already
     * answered today and still queued are the ones circling.
     */
    private fun requeuePosition(answersToday: Map<Long, Int>): Int {
        var others = 0
        queue.forEachIndexed { index, queued ->
            if (queued.id !in answersToday) others++
            if (others == REQUEUE_GAP) return index + 1
        }
        return queue.size
    }

    private fun commit(
        card: StudyCard,
        rating: Rating,
        correct: Boolean,
        given: String,
        expected: String,
        elapsed: Duration,
        confusedWith: Long? = null,
        answerText: String? = null,
        candidate: Word? = null,
        ratingIfSynonym: ((Word) -> Rating)? = null,
    ) {
        val now = clock()
        val updated = engine.apply(card, rating, now)
        missed = if (correct || ratingIfSynonym == null) null else Missed(card, now, ratingIfSynonym)

        words.update(updated)
        words.logReview(updated, rating, correct, elapsed.toMillis(), now, confusedWith, answerText)

        drill = spellingDrill(card, rating)
        synonymCandidate = candidate?.takeIf { it.id != card.word.id }

        // A card still in its learning steps comes back a few cards later rather
        // than at its due time: the steps are FSRS's own, compressed into the
        // session, because "some other material in between" is the gap that
        // matters while the user is here. The circle stops at graduation — a
        // third quick correct answer would shorten the interval, not lengthen it
        // — and after CIRCLE_ANSWER_LIMIT answers, since HARD keeps a card on its
        // step and a slow reader would otherwise never leave.
        val answersToday = answersOn(LocalDate.ofInstant(now, zone))
        val answers = (answersToday[card.id] ?: 0) + 1
        answersToday[card.id] = answers
        if (updated.isLearning() && answers < CIRCLE_ANSWER_LIMIT) {
            queue.add(requeuePosition(answersToday), updated)
        }

        answered++
        feedback = Feedback(
            correct = correct,
            expected = expected,
            given = given,
            rating = rating,
            nextDue = updated.due,
            circle = circleOf(card, updated, rating, correct, answers),
        )

        // Recounted from the history rather than carried over: the header would
        // otherwise keep showing the totals from whenever the queue was last
        // built and drift further from the truth with every answer.
        val today = LocalDate.ofInstant(now, zone)
        publishProgress(words.introducedOn(today, zone), words.repeatsOn(today, zone))
    }

    /**
     * Whether a failed production answer earns a spelling exercise.
     *
     * Since spelling is graded strictly, every misspelling now lands here — which
     * is the point: the exercise is the part that actually repairs a spelling,
     * and a card that was written wrong has one to repair. The escape hatch is
     * [skipDrill], not a narrower trigger.
     */
    private fun spellingDrill(card: StudyCard, rating: Rating): Drill? {
        if (card.direction != Direction.PRODUCTION) return null
        if (rating != Rating.AGAIN) return null

        return Drill(
            word = card.word,
            expected = card.word.term,
            required = DRILL_REPETITIONS,
        )
    }

    private fun elapsed(): Duration = Duration.between(askedAt, clock())

    private fun publishProgress(
        newToday: Int = progress.newToday,
        reviewsToday: Int = progress.reviewsToday,
    ) {
        val pending = queue.toList() + listOfNotNull(question?.card)
        val fresh = pending.count { it.reviews == 0 }

        progress = Progress(
            remaining = pending.size,
            newRemaining = fresh,
            repeatRemaining = pending.size - fresh,
            answeredThisSession = answered,
            newToday = newToday,
            reviewsToday = reviewsToday,
            words = pool.size,
            waitingNew = waitingNew,
            waitingRepeats = waitingRepeats,
            limits = limits,
            allowedNew = allowance(limits.newPerDay),
            allowedReviews = allowance(limits.reviewsPerDay),
        )
    }
}
