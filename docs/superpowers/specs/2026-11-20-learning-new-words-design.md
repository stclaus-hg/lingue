# Learning new words before reviewing them

Status: agreed with the user in conversation on 2026-11-20; this document is
the description to plan from.

## Why

Lingue was built as a **review** tool. The first collection came from Quizlet,
where the user had already flipped through every card, so a word arrived in the
app half-known and the first multiple-choice question was a genuine check.

The user now adds words straight from a book they are reading. They translate
each word in context, but there are many of them and they reach the app days
later, by which point the word is entirely unfamiliar. For these words the app
has no moment of *learning* — the very first time a word is shown, it is a test:

- The first recognition card is answered by guessing. The user said so directly:
  "выбор перевода чисто наугад".
- A lucky guess is graded `GOOD` like real knowledge, so FSRS treats the word as
  known.
- Half a day to two days later the production card arrives, and the user cannot
  type a word they have never actually studied. They confirmed that at that
  point they did not remember the word at all.

The history bears this out (140 words with both directions answered):

| Measure | Value |
|---|---|
| First recognition answer correct | 133 / 146 (91%) |
| First production answer correct, after one recognition answer | 68 / 129 (53%) |
| Days from first recognition to first production (median) | 1.9 |

A 91% first-sight hit rate for unfamiliar words says the choice question is easy
to pass without knowing the word. It does not say the word has been learned.

## Goals

- A new word is **studied before it is tested**: the user sees the term,
  translation and example sentence, and hears it, before any question is asked.
- In the same session, the word is **recalled a few times at short intervals**
  until it is held, rather than once.
- **Typing is not asked until recognition has survived a night.**
- **A guess can be declared as a guess**, so it is not recorded as knowledge.

## Non-goals

- No new card types or directions. Recognition and production stay as they are,
  and so does the fixed answer format per direction (see `Direction` KDoc).
- No hints or partial-typing scaffolds on production cards.
- No change to how existing, already-started cards are scheduled, beyond the
  relearning detail noted under "Learning answers and the daily caps".
- No schema change is expected (see "What is left to the builder").

## Anti-goals

These would make the change a failure even if it technically works:

- **Sessions that drag.** The user accepted that 10 new words make a session
  longer, by about 20–30 cards. Anything well beyond that, such as a word
  circling indefinitely, is a failure.
- **The schedule lying.** No answer should reach FSRS that does not reflect
  what the user actually knew at that moment. This is the reason the spelling
  drill never feeds the scheduler; the same principle applies here.
- **Words learned elsewhere being forced through the full ritual.** A word the
  user already knows must have a one-key way past the introduction.

## The design

### 1. Introduction

When the next card in the queue is a **recognition card with no reviews**, it is
preceded by an introduction screen. That is the first time the word is shown at
all.

The introduction shows:

- the English term;
- the translation;
- the example sentence, if the word has one;
- the pronunciation, played automatically.

The production direction is not yet involved, so speaking the term here does not
break the rule in `BACKLOG.md` ("never speak the English term before a
production answer is submitted").

There are two actions:

- **"Понятно"** (Enter / Space) continues to the recognition question for the
  same word, immediately.
- **"Уже знаю"**: see section 4.

The introduction is not a review. Nothing is written to `reviews` and FSRS is
not called. That keeps `introducedOn`, which counts first reviews, correct
without changes. If the app is closed between the introduction and the first
answer, the introduction is shown again next time. This is harmless.

### 2. The circle: FSRS learning steps, compressed into the session

After the introduction, the recognition question is answered and graded as
usual, through `Grading.forChoice` and `ReviewEngine.apply`.

**Rule:** after any answer, a card whose FSRS state is still `LEARNING` or
`RELEARNING` goes back into the queue a few cards later, instead of waiting for
its due time. The target is 3–5 other cards later, or the end of the queue if it
is shorter. A card in `REVIEW` state leaves the session as it does now.

*Amended 2026-11-21:* the gap counts only cards that are not circling
themselves. Counted over every card, five words missed in a row each went back
behind the other four, and nothing else was asked until all five left the
circle — found in use on the first day.

*Amended 2026-11-21:* the answer panel shows a word's progress through the
circle (right answers in a row given and needed, a slow answer not counting,
out of answers for today), since a right answer that brings the word back
looked like the session looping. Accepting a synonym now re-grades the answer
just given as right.

*Amended 2026-11-24:* a typing card has one learning step instead of two — its
word's meaning is already settled by the time typing opens, so the first right
answer settles it. Over half of first typings of known words were going round
twice. The *easy* allowance for typing grows with the answer's length (6 s, or
3 s + 0.4 s per character), since no answer of 20+ characters had ever made the
flat six seconds.

This makes the circle the scheduler's own learning steps, not a parallel
mechanism. Measured against the library in use
(`io.github.open-spaced-repetition:fsrs:1.0.0`, default `Scheduler`):

| Answers to a new card | States after each | Interval after the last |
|---|---|---|
| GOOD, GOOD | LEARNING (10 min) → REVIEW | 6 days |
| AGAIN, GOOD, GOOD | LEARNING (1 min) → LEARNING (10 min) → REVIEW | 1 day |
| HARD, GOOD, GOOD | LEARNING (5 min) → LEARNING (10 min) → REVIEW | 2 days |
| EASY | REVIEW | 14 days |
| GOOD, GOOD, GOOD | … → REVIEW → REVIEW | 6 d → **4 days** |

The last row is why the circle must stop at graduation. A third quick correct
answer after the card reaches `REVIEW` *shortens* the interval. Extra repetition
beyond the learning steps is not free. The rule above stops exactly at
graduation, so a word that is known costs two answers after the introduction,
and each miss adds a step.

The learning steps' wall-clock waits (1 min, 10 min) are not honoured inside the
session. The card is asked again after a few other cards, not after ten minutes.
This is deliberate: the user is in the session now, and the gap that matters for
short-term consolidation is "some other material in between", not a timer. FSRS
receives the real timestamps and accounts for the short gap itself.

This replaces the current `if (rating == Rating.AGAIN) queue.addLast(updated)`
in `Trainer.commit`. A forgotten review card enters `RELEARNING` and is caught by
the same rule. The difference is that it comes back a few cards later rather
than at the very end.

The rule applies to **both directions**. A new production card answered
correctly but slowly (`GOOD` under the typing thresholds) stays in `LEARNING` and
is typed once more a few cards later. Typing is the harder skill, and one more
retrieval while the word is fresh is exactly what it lacks.

**The circle is bounded.** In FSRS 1.0.0, `HARD` on a learning step keeps the
card on the same step. A choice slower than `SLOW_CHOICE` is always `HARD`, so a
word that is read slowly would otherwise never graduate. Repeated `AGAIN` or
"Не знаю" answers restart the steps. Therefore:

- A card is answered **at most 4 times per session**, introduction not counted.
  After the fourth answer it leaves the session in whatever state it is in. FSRS
  has already scheduled it 1–10 minutes out, and it comes back by the rule
  below.
- Grading inside the circle stays honest. A slow answer is still `HARD`. The cap
  ends the circle; it does not make the grade kinder.

**The circle survives a queue rebuild.** `reload()` runs whenever the user
returns to the training tab after adding a word, saves settings, takes another
day's allowance, or presses the button on the session-finished screen. Adding a
word mid-session is the very workflow this feature is for. So `reload()` also
queues every card that is in `LEARNING` or `RELEARNING` **and was answered
today**, even if its FSRS due time is a few minutes away. A card that has used up
its 4 answers this session is not re-queued by a rebuild in the same session.
That means the per-session answer count lives in `Trainer`, not in the queue.

### 3. Production is gated on recognition having survived a night

A **production card with no reviews** is eligible for the queue only when its
recognition sibling:

1. is **currently in `REVIEW` state**, and
2. had its **first answer on an earlier local day** than today.

Both facts are already available: the state is on the card, and the first
answer is in `reviews`. No graduation timestamp, history replay or schema change
is needed.

In practice:

- A word learned on Monday can be typed from Tuesday at the earliest.
- If Monday's circle was cut short, the word becomes typeable on the day its
  recognition card graduates.
- An existing word whose recognition card is in `REVIEW` is unaffected, even if
  that card was reviewed again today.
- If recognition is forgotten (`RELEARNING`), its unseen production sibling waits
  until recognition is relearned. There is no point typing a word whose meaning
  has just slipped.

The gate is evaluated in the zone the trainer already uses for day boundaries.

Effects on the counters:

- A gated production card is not "waiting behind the daily cap". It is not
  available at all, so it must not be counted in `waitingNew`.
- Gated cards are already excluded from the forecast chart, which only counts
  cards with reviews.

**The rule applies to existing words too.** Any production card that has never
been answered is held until its recognition sibling graduated on an earlier day.
For words already in `REVIEW` before today, this changes nothing.

### 4. "Не знаю" on choice questions, and "Уже знаю" on the introduction

**"Не знаю"** is a new action on every recognition question, not only new
ones. It records the answer as wrong, rated `AGAIN`, with no `confused_with` id,
because nothing was picked. It then shows the usual feedback with the correct
translation highlighted. It needs a keyboard key alongside 1–4; the choice of key
is the builder's.

**"Уже знаю"** on the introduction skips straight to the recognition question
with the introduction dismissed. A correct answer to that question is rated
`EASY` instead of the usual timing-based rating, so the card graduates at once
(14 days) and skips the circle. A wrong answer is rated `AGAIN` as usual. The
card enters the circle like any other new word, so a false "Уже знаю" costs
nothing but a little time.

Rationale for `EASY`: the user has just asserted prior knowledge and then proved
it without having been shown the answer. That is the case `EASY` exists for.

### 5. Learning answers and the daily caps

**`newPerDay` now counts new words, not new cards** (decided by the user, option
A). Before this change it counted any first answer to any card. Under the gate
that would let yesterday's unlocked production cards fill every new slot, and
words would arrive every other day.

- A word is "introduced" on the day its **recognition** card is first answered.
  `introducedOn`, or a replacement, counts those, and `newPerDay` caps them.
- An unlocked production card is **not** new work for the cap. It belongs to a
  word that has already started. It is queued with the repeats and is bounded by
  `reviewsPerDay`, in due order like any other repeat.

  Counted as repeats, any backlog of freshly opened production cards spreads
  over several days alongside the existing ones, rather than bypassing the new
  cap. (An earlier draft said about 70 words were in this position; checked
  against the live database, almost all of those words had never been shown at
  all, so there is no such backlog.)
- `waitingNew` and the queue header count words held back by `newPerDay`.
  `Progress.newRemaining` keeps meaning "unseen cards in the queue", and its
  KDoc already explains why that differs from new words. The settings screen
  wording for `newPerDay` must say "words".

The user accepted the consequence: at the same setting, daily new work roughly
doubles, because each day brings 10 new words plus typing for earlier ones. The
remedy, if it is too much, is a lower setting.

`newPerDay` bounds how many words enter the circle per day, and it remains the
cap that controls the total amount of work.

- Cards in `LEARNING` / `RELEARNING` that were **answered today** are queued
  **without** being limited by `reviewsPerDay`. Holding back a word halfway
  through being learned would waste the session spent on it. A learning card
  last answered on an earlier day is an ordinary repeat under the cap. On the
  day of the change, 26 cards had been left in learning by the old scheme, and
  letting all of them past the cap would have flooded one session.
- `reviewedOn` should **not** count answers to cards that are in their learning
  phase toward `reviewsPerDay`. Otherwise ten new words spend about 30 of the
  default 40 daily reviews before a single older word is repeated. The simplest
  correct rule is still the builder's to find; one candidate is to count only
  answers to cards that had a `REVIEW`-state answer before.
- That count must be a **separate query**, not a change to `reviewedOn`.
  `StatsRepository` uses `reviewedOn` for the "answered today" tile, and that
  tile should keep counting every answer.

### 6. Book sentences as examples (usage, no code)

The import already takes an example sentence in the third column, and the
introduction now shows it. When adding words from the book, put the sentence the
word was met in into that column. The introduction then brings back the original
context. No code change is needed; `README.md` should mention it where the TSV
format is described.

## Constraints

- Kotlin + Compose Desktop. `Trainer` is the only place that touches the
  scheduler and the database. The UI reads its state and calls its verbs.
- TDD throughout: `Trainer` behaviour is tested through its public verbs against
  a real `WordRepository` on a temporary database, as the existing tests do.
- FSRS state stays opaque. Branch on `card.state`, never on parsed internals.
- All code, comments, tests and docs in English. UI strings in Russian.

## Decided by Claude, not by the user

These are calls made while writing this up. The user agreed to the overall shape
(introduction, short in-session circle, typing later, "Не знаю"), not to these
specifics:

- The circle is FSRS's own learning steps, ending at graduation, not a fixed
  number of repeats.
- Re-insertion is 3–5 cards later, and the same rule replaces "AGAIN goes to the
  end of the queue" for forgotten review cards.
- Production unlocks the day after recognition **graduated**, rather than after
  a correct recognition answer on a later day. The latter would usually mean a
  wait of six days.
- "Уже знаю" followed by a correct answer is rated `EASY`.
- Learning-phase cards and answers are exempt from `reviewsPerDay`.
- The circle applies to production cards too, and is capped at 4 answers per
  card per session.
- Cards in the circle survive a queue rebuild.
- The production gate is "recognition is in `REVIEW` now and was first answered
  before today", chosen because it needs no schema change.
- Unlocked production cards count against `reviewsPerDay`, not `newPerDay`.
  The user chose that `newPerDay` counts words; this follows from that choice.

## Left to the builder

- Key binding for "Не знаю", and the exact layout of the introduction screen. It
  should be visually consistent with the existing recognition prompt.
- How `Trainer` represents "introduction pending". A new state property next to
  `question`, `feedback` and `drill` fits the existing pattern.
- Whether the gate and the learning-phase exemption are best computed in SQL
  (`WordRepository`) or in memory in `Trainer.reload`. The collection is a few
  hundred rows, and `StatsRepository` already argues for in-memory. Graduation
  day can be derived from the `reviews` table plus card state, so no schema
  change should be needed. If one turns out to be necessary, it follows the
  existing versioned migrations in `Database.kt`.
- What happens when the circling card is the only one left in the queue. Asking
  it again immediately is acceptable.
- Updating the `Progress` counters, the queue-header wording and `README.md` /
  `BACKLOG.md` so they describe the new flow.
