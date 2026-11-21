# Lingue

A desktop trainer for English vocabulary, built around the FSRS spaced
repetition algorithm.

The gap it fills: Anki has the best scheduler available but grades by asking you
to rate yourself, and has no multiple-choice mode. Quizlet has the two answer
modes but a simplistic scheduler. This has both — FSRS deciding when a word comes
back, and answers graded automatically from what you actually typed or picked.

## Two cards per word

Recognition and production are separate abilities — picking a meaning out of four
options says little about producing the word unprompted. Each word therefore owns
two cards, scheduled independently:

| Card | Prompt | Answer | Format |
|---|---|---|---|
| Recognition | the English term | the translation | pick one of four |
| Production | the translation | the English term | type it out |

The format is fixed per card and never changes as the card matures. If the task
got harder partway through, the resulting failure would look to FSRS like
forgetting rather than a different question, and stability would drop for the
wrong reason.

Only one direction of a word appears per session. Recognising `cozy → уютный`
moments before being asked to produce `уютный → cozy` tests short-term memory
of the previous card rather than the word — Anki calls this burying siblings.

## Learning a new word

The app was first fed words already flipped through elsewhere, so the first
question was a fair check. A word met for the first time cannot be recognised,
only guessed — and a lucky guess used to be scheduled as knowledge, with typing
due a day later for a word never actually studied. So a new word is now learned
before it is reviewed:

1. **Introduction.** The term, its translation and the example sentence are
   shown and the word is pronounced. Nothing is graded. "Уже знаю" skips
   straight to the question; a correct pick then counts as *easy* and the word
   leaves the session at once.
2. **The circle.** The word is asked as a choice question, then again four cards
   later, until FSRS moves it out of its learning steps — two correct answers,
   more after a miss, at most four per session. A forgotten older card circles
   back the same way. The four cards counted are ones not circling themselves,
   so several words missed at once take turns with the rest of the session
   instead of crowding it out. After a miss it takes two right answers in a
   row (one for a forgotten older word), and a slow one — over ten seconds —
   does not count as a step. The answer panel shows where the word stands:
   `Закрепление ●○ · ещё 1 верный ответ`, then `Закреплено ●●`.
3. **Typing, from the next day.** A word's production card opens only once its
   recognition card is in review and was first answered on an earlier day.

"Не знаю" on any choice question records a miss instead of a guess.

When adding words from a book, put the sentence the word was met in into the
third column of the TSV: the introduction shows it, which brings the original
context back.

## Grading

Grading is automatic, and it is the part worth arguing about:

- **Choice** — wrong or "Не знаю" resets the card; correct is *good*; correct but
  slow is *hard*. A correct pick earns *easy* only after "Уже знаю" on the
  introduction: recognising a word among four is much easier than producing it,
  and rewarding every pick would stretch the interval on evidence that does not
  hold up without the options present.
- **Typing** — graded strictly. The exact letters typed quickly are *easy*,
  otherwise *good*; anything else is *again*. A production card asks whether the
  word can be written, so a misspelling is not interference with the
  measurement — it is the thing being measured. Formatting stays forgiving: case,
  stray spaces and a leading "to" are not letters of the word.
- **Irregular verbs** — an entry written as three forms (`go, went, gone`) asks
  for all three, and the card says so. The forms may be separated by commas or
  by spaces; the count is still checked, so a missing form fails.

Daily limits default to 20 new words and 200 reviews. They belong together: each
card generates roughly ten reviews before its interval grows long, and a word has
two, so raising one without the other produces a backlog a week later. The new
limit counts words, on the day their recognition card is first answered. A
production card that has just opened belongs to a word already started and
counts against the review limit, as do repeats — each card once a day, however
many times it circles.

## Running it

```bash
scripts/install-fsrs.sh   # once per machine
./gradlew run
```

See [docs/SETUP.md](docs/SETUP.md) for details, including where the data file
lives, the daily backup of it (`scripts/install-backup.sh`) and how to fit the
algorithm's parameters to your own memory later.

## Spelling

A wrong answer shows the difference letter by letter rather than just printing
the right word — finding the difference yourself is the step that fixes a
spelling, so the app does not leave it to you.

When a word is actually misremembered, not just mistyped, it goes into a short
writing exercise: three repetitions, the first copied from the model and the rest
from memory. Copying three times would train the eyes; reproducing trains the
memory.

The exercise never feeds back into the scheduler. The rating was decided by the
first answer, and letting repetitions improve it would mean a card could be
talked up to a good score.

Since spelling is graded strictly, every misspelling leads into the exercise —
that is the point, and "Пропустить" is the escape hatch rather than a narrower
trigger.

## Synonyms

Answering with a different word from the collection offers a one-click "this
means the same" link. Linked words stop being offered as each other's wrong
option, and each is accepted in place of the other. Accepting the link also
turns the answer just given into a right one: it was a valid meaning, so the
word is not sent round the learning circle for it.

The pair is recorded when it is met rather than declared upfront: no string
metric can separate a good distractor (`предсказуемый` / `непредсказуемый` —
alike on paper, opposite in meaning) from an unfair one (two ways of saying the
same thing), so the judgement has to come from a reader.

## Pronunciation

Every English term has a pre-generated pronunciation. Recognition cards play it
on sight — the prompt *is* the English word. Production cards stay silent until
the answer is graded, because speaking it first would be handing it over.

## Statistics

Counts, retention split by direction, and two time series: answers over the past
fortnight and how many cards fall due over the coming week.

## Keyboard

| Key | Action |
|---|---|
| `Enter` / `Space` | On an introduction: continue to the question |
| `1`–`4` | Pick an option |
| `0` | "Не знаю" |
| `Enter` | Check a typed answer, then move on |

## Built with

- **Kotlin + Compose Multiplatform** — draws its own pixels via Skia, so the UI
  looks the same regardless of the host system's age.
- **[java-fsrs](https://github.com/open-spaced-repetition/java-fsrs)** (MIT) —
  the scheduling algorithm, from the organisation that develops it. Its own test
  suite runs before installation rather than the algorithm being reimplemented
  here.
- **SQLite** — one file, `~/.local/share/lingue/lingue.db`.
