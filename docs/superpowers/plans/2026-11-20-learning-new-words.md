# Learning New Words Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A brand-new word is introduced, then recalled in a short in-session circle, and only typed after recognition has survived a night. A guess can be declared as "Не знаю".

**Architecture:** All behaviour lives in `Trainer` (queue building in `reload`, grading in `commit`) on top of two new read queries in `WordRepository`. The circle is FSRS's own learning steps: a card still in `LEARNING`/`RELEARNING` after an answer is re-inserted a few cards later. The UI only reads new `Trainer` state (`introducing`) and calls new verbs (`understood`, `alreadyKnown`, `dontKnow`).

**Tech Stack:** Kotlin, Compose Multiplatform Desktop, java-fsrs 1.0.0, SQLite (xerial), kotlin.test. Run tests with `./gradlew test`.

**Spec:** `docs/superpowers/specs/2026-11-20-learning-new-words-design.md`

## Global Constraints

- Code, comments, test names, docs: English. UI strings: Russian.
- No schema change. No new FSRS parameters. Branch on `card.state` (`io.github.openspacedrepetition.State`), never on FSRS internals.
- TDD: each new test is run and seen failing before the code that passes it.
- `Trainer` stays the only place that calls `ReviewEngine` and writes to `WordRepository`.
- `reviewedOn` keeps its meaning (every answer). It is used by the stats tile "answered today".
- Constants: `REQUEUE_GAP = 4` (other cards between two asks of a circling card); `CIRCLE_ANSWER_LIMIT = 4` (answers per card per `Trainer` lifetime, introduction not counted).
- The project is not a git repository: skip commit steps.

## Review Focus

1. **The user adds a word mid-session.** `App.kt` calls `reload()` on returning to the training tab. Words circling must still be in the queue afterwards. Pinned in Task 3.
2. **The first day after release.** About 70 existing words have an unseen production card whose recognition is already in `REVIEW`. They must arrive as repeats, under `reviewsPerDay`, not all at once. Pinned in Task 2.
3. **A slow reader.** Every choice slower than 10 s is rated `HARD`, which keeps a learning card on its step. The circle must still end after 4 answers. Pinned in Task 3.
4. **"Уже знаю" followed by a wrong pick.** The card must enter the circle like any new word, with no `EASY`. Pinned in Task 4.
5. **"Не знаю" on an old card.** It must lapse into `RELEARNING` and circle back, and it must not start a spelling drill or record a confusion. Pinned in Task 5.

---

### Task 1: Repository counts — words introduced, repeats answered, answer spans

**Files:**
- Modify: `src/main/kotlin/lingue/data/WordRepository.kt:309-349`
- Test: `src/test/kotlin/lingue/data/WordRepositoryTest.kt` (replace `introductions are counted per card, not per word` at line 143)

**Interfaces:**
- Produces:
  - `fun introducedOn(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Int`. **Changed meaning:** it counts words, as recognition cards whose first-ever answer falls on `date`.
  - `fun repeatsOn(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Int`. The number of distinct cards answered on `date`, minus `introducedOn(date, zone)`.
  - `data class AnswerSpan(val first: Instant, val last: Instant)` in `WordRepository.kt`.
  - `fun answerSpans(): Map<Long, AnswerSpan>`, keyed by card id. One `GROUP BY card_id` query over `reviews`.

- [ ] **Step 1: Write the failing tests.** Replace the per-card test, and add the new ones next to it:

```kotlin
@Test
fun `introductions are counted per word, by its recognition card`() {
    val word = words.add("apple", "яблоко", now = now)
    words.logReview(cardFor(word.id, Direction.RECOGNITION), Rating.GOOD, true, 900, now)
    words.logReview(cardFor(word.id, Direction.PRODUCTION), Rating.GOOD, true, 900, now)

    assertEquals(1, words.introducedOn(LocalDate.of(2026, 8, 1), utc))
}

@Test
fun `repeats count each started card once a day and leave new words out`() {
    val old = words.add("pear", "груша", now = now.minus(Duration.ofDays(3)))
    val fresh = words.add("apple", "яблоко", now = now)
    val oldCard = cardFor(old.id, Direction.RECOGNITION)
    words.logReview(oldCard, Rating.GOOD, true, 900, now.minus(Duration.ofDays(2)))
    words.logReview(oldCard, Rating.AGAIN, false, 900, now)
    words.logReview(oldCard, Rating.GOOD, true, 900, now.plus(Duration.ofMinutes(2)))
    words.logReview(cardFor(old.id, Direction.PRODUCTION), Rating.GOOD, true, 900, now)
    words.logReview(cardFor(fresh.id, Direction.RECOGNITION), Rating.GOOD, true, 900, now)

    assertEquals(2, words.repeatsOn(LocalDate.of(2026, 8, 1), utc))
    // Today's answers: AGAIN and GOOD on the old card, its production, the new word.
    assertEquals(4, words.reviewedOn(LocalDate.of(2026, 8, 1), utc), "every answer still counts there")
}

@Test
fun `answer spans give each card its first and last answer`() {
    val word = words.add("apple", "яблоко", now = now)
    val card = cardFor(word.id, Direction.RECOGNITION)
    words.logReview(card, Rating.GOOD, true, 900, now)
    words.logReview(card, Rating.GOOD, true, 900, now.plus(Duration.ofMinutes(5)))

    assertEquals(AnswerSpan(now, now.plus(Duration.ofMinutes(5))), words.answerSpans()[card.id])
    assertNull(words.answerSpans()[cardFor(word.id, Direction.PRODUCTION).id])
}
```

- [ ] **Step 2: Run them to verify they fail.** Run `./gradlew test --tests 'lingue.data.WordRepositoryTest'`. Expected: the introduction test fails (2 ≠ 1). The other two fail to compile, because `repeatsOn` and `answerSpans` are undefined.

- [ ] **Step 3: Implement.**
  - `introducedOn`: join `cards` and add `AND c.direction = 'RECOGNITION'`.
  - `repeatsOn`: `SELECT COUNT(DISTINCT card_id)` within `dayBounds`, minus `introducedOn`.
  - `answerSpans`: `SELECT card_id, MIN(reviewed_at), MAX(reviewed_at) FROM reviews GROUP BY card_id`.
  - Update the KDoc of `introducedOn` to say "words".

- [ ] **Step 4: Run the full suite.** Run `./gradlew test`. Expected: the three new tests pass. Fix any other test that asserted the per-card introduction count by updating its expected value only where the spec's new meaning makes the old value wrong. Note each one in the task report.

---

### Task 2: Queue building — production gate, words-not-cards cap, repeats budget

**Files:**
- Modify: `src/main/kotlin/lingue/app/Trainer.kt` (`reload`, `burySiblings`, `publishProgress`, the `Progress` KDoc)
- Create: `src/test/kotlin/lingue/app/LearningFixtures.kt`
- Create: `src/test/kotlin/lingue/app/LearningQueueTest.kt`
- Modify, to repair setups: `src/test/kotlin/lingue/app/TrainerTest.kt`, `SpellingDrillTest.kt`, `SynonymTest.kt`, `VerbFormsAnswerTest.kt`, `DailyAllowanceTest.kt`

**Interfaces:**
- Consumes: `introducedOn`, `repeatsOn`, `answerSpans`, `AnswerSpan` (Task 1).
- Produces, as a test fixture used by Tasks 3–5:
  - `fun WordRepository.answer(card: StudyCard, rating: Rating, at: Instant): StudyCard`. Applies `ReviewEngine()` at `at`, then `update` and `logReview(correct = rating != Rating.AGAIN, elapsedMs = 1_000)`. Returns the updated card.
  - `fun WordRepository.graduateRecognition(wordId: Long, at: Instant)`. Answers the word's recognition card `EASY` at `at`, which puts it in `REVIEW` for 14 days.
- Produces: `Progress.reviewsToday` now carries `repeatsOn(today)`.

**Queue rule** (`reload`), in this order:

1. **learning** — every card with `reviews > 0` and state `LEARNING` or `RELEARNING`, that either is due or has `answerSpans()[id].last` today, and has `sessionAnswers[id] < CIRCLE_ANSWER_LIMIT`. The `sessionAnswers` map arrives in Task 3. In this task, treat every count as 0.
2. **due** — `dueCards(now)` minus learning ids, minus gated cards.

   A production card with `reviews == 0` is **gated** unless its recognition sibling is in `REVIEW` and its `answerSpans().first` is before the start of today in `zone`.
3. Bury siblings over `learning + due`, in that order, so a learning card wins its word.
4. Partition what is left:
   - `fresh`: recognition cards with `reviews == 0`;
   - learning;
   - `repeats`: everything else, including unlocked production cards.
5. Build the queue as `learning` (uncapped), then `repeats.take(reviewBudget)`, then `fresh.take(newBudget)`.
   - `newBudget = allowance(newPerDay) − introducedOn(today)`;
   - `reviewBudget = allowance(reviewsPerDay) − repeatsOn(today)`;
   - `waitingNew = fresh.size − taken`; `waitingRepeats` likewise.

- [ ] **Step 1: Write the fixture, then the failing tests in `LearningQueueTest.kt`.** Use a mutable clock (`private var now = Instant.parse("2026-08-01T09:00:00Z")`, `clock = { now }`, zone UTC), and the queue helper `fun Trainer.queuedCards(): List<StudyCard>`. The helper walks `question` with `advance()` without answering.

```kotlin
@Test
fun `a new word's production card waits while its recognition is unlearned`() {
    words.add("cozy", "уютный", now = now)
    trainer().apply { reload() }.queuedCards().let { cards ->
        assertEquals(listOf(Direction.RECOGNITION), cards.map { it.direction })
    }
}

@Test
fun `production waits for the night even after recognition graduated today`() {
    val word = words.add("cozy", "уютный", now = now)
    words.graduateRecognition(word.id, at = now)
    assertTrue(trainer().apply { reload() }.queuedCards().isEmpty())
}

@Test
fun `production opens the day after recognition graduated`() {
    val word = words.add("cozy", "уютный", now = now.minus(Duration.ofDays(1)))
    words.graduateRecognition(word.id, at = now.minus(Duration.ofDays(1)))
    assertEquals(listOf(Direction.PRODUCTION), trainer().apply { reload() }.queuedCards().map { it.direction })
}

@Test
fun `production stays closed while recognition is relearning`() {
    val word = words.add("cozy", "уютный", now = now.minus(Duration.ofDays(20)))
    words.graduateRecognition(word.id, at = now.minus(Duration.ofDays(20)))
    val recognition = words.cardsOf(word.id).first { it.direction == Direction.RECOGNITION }
    words.answer(recognition, Rating.AGAIN, at = now.minus(Duration.ofDays(1)))
    assertTrue(trainer().apply { reload() }.queuedCards().none { it.direction == Direction.PRODUCTION })
}

@Test
fun `the new-word cap counts words, so unlocked production does not use it up`() {
    (1..3).forEach { i ->
        val w = words.add("old$i", "старое$i", now = now.minus(Duration.ofDays(1)))
        words.graduateRecognition(w.id, at = now.minus(Duration.ofDays(1)))
    }
    (1..3).forEach { i -> words.add("new$i", "новое$i", now = now) }
    val cards = trainer(Limits(newPerDay = 2, reviewsPerDay = 40)).apply { reload() }.queuedCards()
    assertEquals(3, cards.count { it.direction == Direction.PRODUCTION })
    assertEquals(2, cards.count { it.direction == Direction.RECOGNITION })
}

@Test
fun `unlocked production cards are bounded by the review cap`() {
    (1..5).forEach { i ->
        val w = words.add("old$i", "старое$i", now = now.minus(Duration.ofDays(1)))
        words.graduateRecognition(w.id, at = now.minus(Duration.ofDays(1)))
    }
    val trainer = trainer(Limits(newPerDay = 0, reviewsPerDay = 2)).apply { reload() }
    assertEquals(2, trainer.progress.remaining)
    assertEquals(3, trainer.progress.waitingRepeats)
}
```

`Limits(newPerDay = 0, …)` must be inside `Limits.ALLOWED_NEW`. Check the range in `Limits.kt`, and if 0 is not allowed, use the smallest allowed value and adjust the expected numbers.

- [ ] **Step 2: Run them to verify they fail.** Run `./gradlew test --tests 'lingue.app.LearningQueueTest'`. Expected: the gate tests and the cap test fail on queue contents.

- [ ] **Step 3: Implement the queue rule above in `reload`.** Read `allCards()` and `answerSpans()` once per reload. Sibling lookup goes through a `Map<Long /*wordId*/, StudyCard /*recognition*/>`. The gate's day boundary is `today.atStartOfDay(zone)`. Pass `repeatsOn` into `publishProgress` wherever `reviewedOn` was passed, in both `reload` and `commit`.

- [ ] **Step 4: Run the full suite and repair setups.** Run `./gradlew test`. Tests that reach production cards by answering recognition and calling `reload()` the same day will now fail. That is intended: same-day production is gone.
  - Repair them by putting the recognition card in `REVIEW` a day earlier with `graduateRecognition(id, now − 1 day)` in the setup, for example `SpellingDrillTest.productionSession()`.
  - Do not weaken any assertion.
  - Rewrite `TrainerTest` `the buried sibling comes back in the next session` to use the next day.
  - Update `the daily limit counts cards` to count words. Rename it, keep the value 4.
  - Expected: all tests pass. The task report lists every repaired test and why.

---

### Task 3: The circle — re-insert learning cards, cap answers, survive reload

**Files:**
- Modify: `src/main/kotlin/lingue/app/Trainer.kt` (`commit`, `reload`, a new private `sessionAnswers`, constants next to `DRILL_REPETITIONS`)
- Create: `src/test/kotlin/lingue/app/LearningCircleTest.kt`

**Interfaces:**
- Consumes: the fixtures and the queue rule from Task 2.
- Produces:
  - `const val REQUEUE_GAP = 4`;
  - `const val CIRCLE_ANSWER_LIMIT = 4`;
  - `private val sessionAnswers = mutableMapOf<Long, Int>()`. It is never cleared by `reload()`, and it lives as long as the `Trainer`.

**Rule in `commit`:** increment `sessionAnswers[card.id]`. If `updated.state` is `LEARNING` or `RELEARNING` and the count is below `CIRCLE_ANSWER_LIMIT`, insert at `min(REQUEUE_GAP, queue.size)`. This replaces `if (rating == Rating.AGAIN) queue.addLast(updated)`.

- [ ] **Step 1: Write the failing tests.** Seed 8 new words with `Limits(newPerDay = 8)`. The `introduction` step does not exist yet: until Task 4, answering directly is allowed.

```kotlin
@Test
fun `a new word comes back after four other cards`() {
    val trainer = trainer().apply { reload() }
    val first = assertNotNull(trainer.question).card
    trainer.answerChoiceCorrectly()          // GOOD -> LEARNING, re-inserted at index 4
    trainer.advance()
    repeat(4) {
        assertNotEquals(first.id, assertNotNull(trainer.question).card.id)
        trainer.answerChoiceCorrectly()
        trainer.advance()
    }
    assertEquals(first.id, assertNotNull(trainer.question).card.id)
}

@Test
fun `the circle ends when the card graduates`() { /* one word: GOOD, GOOD -> question becomes null */ }

@Test
fun `a slow reader still leaves the circle after four answers`() {
    // one word; advance the clock 11 s before each answer so every pick is HARD
    // expect exactly 4 answers in reviews for that card, then question == null
}

@Test
fun `a forgotten old card comes back a few cards later, not at the end`() { /* graduated card due today among 8 repeats, answer AGAIN, it is the 5th question after */ }

@Test
fun `adding a word mid-circle does not lose the circling word`() {
    // one word answered GOOD (LEARNING, due in 10 min), then words.add(...), trainer.reload()
    // the circling card is in queuedCards()
}

@Test
fun `a card that used up its answers is not brought back by a rebuild`() { /* slow reader to 4 answers, reload(), card absent */ }
```

Write each test body out fully. Bodies shown as comments state their exact setup and assertions. Helper: `Trainer.answerChoiceCorrectly()`.

- [ ] **Step 2: Run them to verify they fail.** Run `./gradlew test --tests 'lingue.app.LearningCircleTest'`. Expected: the first test fails, because the card does not return. The cap test fails, because more than 4 answers are possible or the card stays.

- [ ] **Step 3: Implement the rule in `commit`.** In `reload`, the learning filter from Task 2 now uses `sessionAnswers`.

- [ ] **Step 4: Run the full suite.** Run `./gradlew test`. Expected: all tests pass. `TrainerTest` `a wrong answer brings the card back within the session` must still pass unchanged.

---

### Task 4: Introduction — `introducing`, `understood()`, `alreadyKnown()`

**Files:**
- Modify: `src/main/kotlin/lingue/app/Trainer.kt`
- Create: `src/test/kotlin/lingue/app/IntroductionTest.kt`
- Modify: test helpers that answer new recognition cards directly (`TrainerTest.answerCorrectly`, `LearningCircleTest`, `LearningQueueTest` if needed, `DailyAllowanceTest`, `SynonymTest`, `QuestionBuilder`-independent ones). Each calls `understood()` first while `introducing` is true.

**Interfaces:**
- Produces:
  - `var introducing: Boolean`, a Compose state with a private setter. It is true exactly when `question` is a `Question.Choice` whose `card.reviews == 0`, and the user has not yet dismissed it. It is set in `advance()`.
  - `fun understood()`: sets `introducing = false` and resets `askedAt = clock()`, so the reading time does not count as answer time.
  - `fun alreadyKnown()`: does the same, and remembers the current card id as claimed.
  - `answerChoice` and `dontKnow` are ignored while `introducing` is true.
  - In `answerChoice`, a correct pick on a claimed card is rated `Rating.EASY`. A wrong pick follows the normal rule (`AGAIN`).

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun `a brand-new word is introduced before it is asked`()
// reload with one new word: introducing == true, question is the Choice, answerChoice(correct) writes no review

@Test fun `after the introduction the same word is asked`()
// understood(): introducing == false, same card id, answerChoice(correct) -> one review, rating GOOD

@Test fun `a started card is never introduced`()
// word with recognition answered AGAIN yesterday: introducing == false

@Test fun `a word claimed as known and then picked right graduates at once`()
// alreadyKnown(), correct pick -> rating EASY, card state REVIEW, not re-queued

@Test fun `a word claimed as known but picked wrong enters the circle`()
// alreadyKnown(), wrong pick -> rating AGAIN, card comes back within the session

@Test fun `time spent reading the introduction is not answer time`()
// clock +30 s during introduction, understood(), answer at +1 s -> rating GOOD, not HARD
```

- [ ] **Step 2: Run them to verify they fail.** Run `./gradlew test --tests 'lingue.app.IntroductionTest'`. Expected: compile failure, because `introducing`, `understood` and `alreadyKnown` are undefined.

- [ ] **Step 3: Implement as in Interfaces.** Clear the claimed id in `advance()`.

- [ ] **Step 4: Run the full suite and fix helpers.** Run `./gradlew test`. Expected: tests that answered new words directly now fail. Fix their helpers to call `understood()` first. All tests pass.

---

### Task 5: "Не знаю" — `dontKnow()`

**Files:**
- Modify: `src/main/kotlin/lingue/app/Trainer.kt`
- Test: `src/test/kotlin/lingue/app/IntroductionTest.kt` (append), or a new `DontKnowTest.kt`

**Interfaces:**
- Produces: `fun dontKnow()`. It applies only to a `Question.Choice` without feedback, and only when not `introducing`. It commits `Rating.AGAIN`, `correct = false`, `given = ""`, `expected = current.answer`, `confusedWith = null`, `candidate = null`. Feedback is shown as for any wrong answer.

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun `not knowing is recorded as a miss without a confusion`()
// rating AGAIN, correct false, reviews row has confused_with_word_id NULL, feedback.correct == false

@Test fun `not knowing an old word sends it into relearning and back into the circle`()
// graduated card due today among 6 repeats -> state RELEARNING, returns after 4 cards

@Test fun `not knowing starts no spelling exercise`()
// drill == null
```

- [ ] **Step 2: Run them to verify they fail.** Expected: compile failure, because `dontKnow` is undefined.

- [ ] **Step 3: Implement `dontKnow()` through `commit`.**

- [ ] **Step 4: Run the full suite.** Run `./gradlew test`. Expected: all tests pass.

---

### Task 6: UI, wording, docs, install

**Files:**
- Modify: `src/main/kotlin/lingue/ui/TrainScreen.kt`, `src/main/kotlin/lingue/ui/SettingsScreen.kt`, `src/main/kotlin/lingue/core/Limits.kt`, `README.md`, `docs/BACKLOG.md`
- Test: `src/test/kotlin/lingue/core/` (Limits) if `expectedDailyReviews` has a test, otherwise create `LimitsTest.kt`

**Interfaces:**
- Consumes: `Trainer.introducing`, `understood()`, `alreadyKnown()`, `dontKnow()`.

- [ ] **Step 1: Write the failing test for the load estimate.** `expectedDailyReviews(10) == 200`: two cards per word, about ten reviews each. Run it and see it fail (currently 100).

- [ ] **Step 2: Implement `expectedDailyReviews(newPerDay) = newPerDay * CARDS_PER_WORD * 10`, with `CARDS_PER_WORD = 2`.** Run the test: PASS.

- [ ] **Step 3: Training screen.**
  - **Introduction screen.** When `trainer.introducing` is true, it replaces the choice options. It shows:
    - the term, in the existing prompt style;
    - the translation;
    - the example, if present;
    - the existing "listen again" button.
  - **Buttons on the introduction:** «Понятно», which calls `understood()` (Enter or Space), and «Уже знаю», which calls `alreadyKnown()` (key `K`).
  - **Heading on the introduction:** «Новое слово».
  - **Audio.** It already plays on sight of a `Choice` via `LaunchedEffect(question)`. Do not play it again on `understood()`.
  - **On the choice question,** add a «Не знаю» button below the options, which calls `dontKnow()` (key `0` / `NumPad0`).
  - **Header wording.** `"Сегодня: …/… новых"` becomes `"… новых слов"`. `"… ответов"` becomes `"… повторений"`, since it now shows `repeatsOn`. In the finished-session text, `waitingNew` reads `"… новых слов"`.

- [ ] **Step 4: Settings wording.**
  - The field label becomes «Новых слов».
  - The estimate line becomes `"При N новом слове / новых словах в день нагрузка со временем установится около M повторений в день."` Use `formatCount` with the word forms «новом слове», «новых словах», «новых словах».
  - In the explanation bullets, «новая карточка» becomes «новое слово» and «Каждое новое слово тянет за собой примерно двадцать будущих повторений — по десять на каждое направление». Do the same in «Приостановить новые слова».
  - The `notStarted` sentence about cards stays as is: it counts cards.

- [ ] **Step 5: Docs.**
  - **README:** describe the flow. Introduction, then a circle of recall within the session, then typing from the next day. Mention «Не знаю». The new-words limit counts words. The TSV third column should hold the sentence from the book.
  - **`docs/BACKLOG.md` "Done":** add a "Learning new words" section with the non-obvious reasoning:
    - the 91% / 53% numbers;
    - why the circle stops at graduation (GOOD ×3 shortens 6 d → 4 d);
    - why the gate is "REVIEW now and first answered before today";
    - why unlocked production counts against reviews.

- [ ] **Step 6: Build, test, install.** Run `./gradlew test`, then `scripts/install-desktop.sh`. Expected: all tests pass and the install succeeds. Then launch the installed app once against a copy of the database, and screenshot the introduction and the «Не знаю» button. Use the `run` skill if available. Otherwise ask the user to look.
