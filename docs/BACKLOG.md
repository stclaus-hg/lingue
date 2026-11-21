# Backlog

## Done

The first four items — smarter distractors, the short-window layout fix,
pronunciation, and statistics — are implemented. What is kept below is the
reasoning that is not obvious from the code.

### Distractors

Measured on the 103-word list before building anything:

| Rule | Coverage |
|---|---|
| Same root in English | 27%, with false pairs (`consequence`/`consider`) |
| Similar translation (edit distance ≤ 35%) | 21% |

Neither rule covers the list, and **full coverage is not the goal**: a word with
no close neighbour cannot be confused, so an easy question for it is an accurate
measurement rather than a lenient one.

Similarity is looked for **among the options, not the prompts** — the options on
screen are translations, so that is where confusion happens.

Four layers, strongest first, in `QuestionBuilder.pickDistractors`:

1. Words this user actually picked by mistake, from `reviews.confused_with_word_id`
2. Translations that read alike
3. English words sharing a root
4. Similar translation length

Stemming collects *every* plausible root and intersects the two sets rather than
committing to one stem: the longest-suffix rule turns "invention" into "inven"
(no longer matching "invent"), and a prefix rule strips a leading "in-" from
"invent" that was never there. A test pins both cases.

### Statistics

Single values are stat tiles, not charts; only the two time series are plotted.
Activity bars deliberately do **not** stack correct against wrong — two
categories in one bar would need a colourblind-validated palette and would
answer two questions at once.

### Pronunciation

Ready-made human recordings were rejected on coverage: 26 of 103 entries are
phrases (`to change one's mind`, `to be jealous of`), which no dictionary
records. Piper covers everything with one voice, offline.

Audio is generated ahead of time; the app only opens WAVs. Same division as the
FSRS optimiser — heavy Python stays a build-time tool.

**The constraint to keep in mind:** never speak the English term before a
production answer is submitted.

### Strict spelling, and the drill

Typing answers were originally graded leniently — a one-character slip cost
*hard* rather than resetting the card — on the argument that a typo is motor
noise rather than a memory failure.

**That argument was wrong here, and the rule was reversed.** It assumed the card
measures recall of meaning. A production card asks whether the word can be
*written*, and under that goal a misspelling is not interference with the
measurement, it is the measurement. Only formatting is still forgiven: case,
stray spaces, and a leading "to".

The known cost, from the history at the time of the change: 23 of 85 production
answers (27%) were near misses. Those become failures, taking the production
failure rate from 18% to roughly 45%, with correspondingly more repeats. Watch
the per-direction retention on the statistics tab; if production retention sits
far below recognition for long, the answer is fewer new cards per day, not a
softer ruler.

Every misspelling now leads into the writing exercise. `skipDrill` is the escape
hatch rather than a narrower trigger.

Two decisions worth keeping:

- **The first repetition shows the model, the rest do not.** Copying and
  reproducing are different exercises; three copies would train the eyes only.
- **Repetitions never reach the scheduler.** The rating belongs to the first
  answer. If practice could raise it, the schedule would stop describing what is
  known.

`reviews.answer_text` (schema v3) records what was typed, which is what
`WordRepository.misspellings()` reads. Nothing surfaces it yet — see below.

### Synonyms

Two words with overlapping translations make a choice question unanswerable: more
than one option is right, and the answer is marked wrong either way.

**No string metric can detect this.** The distractor layers deliberately prefer
translations that look alike, because "предсказуемый" and "непредсказуемый" are
orthographically close and semantically opposite — an excellent question. A
synonym pair is orthographically close and semantically *identical* — a terrible
one. Edit distance cannot tell those apart; only a reader can.

So the pair is recorded when it is met, from the feedback panel, in one click.
Once linked, the two words are never offered as each other's wrong option and
each is accepted in place of the other.

The answer that triggered the link stays wrong. The review is already written and
rewriting it to be kinder would put a guess into the memory model; the card
returns within the minute with the link already in force.

**Merging instead of linking** — one entry with `a / b` in the term — is also
supported and costs half the cards. The two directions pull opposite ways here:

| | Merged `a / b` | Linked synonyms |
|---|---|---|
| Recognition | the pair is tested as a unit | each word tested alone |
| Production | either answer accepted | either accepted, asked twice |

The deciding question is whether the word will be met *alone* in a text. If yes,
it needs its own recognition card and the pair should stay linked, not merged.
Merging suits a word that only ever appears as an afterthought — a regional
variant you will never have to recognise on its own.

### Daily limits

Configurable on the settings tab, stored in the database (schema v4).

The two numbers do different jobs and the screen says so: **only `newPerDay`
changes the total amount of work**, because each new card drags about ten reviews
behind it. `reviewsPerDay` bounds a single day, which protects the habit, but
anything it holds back comes due all the same — capping reviews against a backlog
makes the backlog bigger.

When the number of cards already due exceeds the pace implied by `newPerDay`, the
screen says so and offers to pause new cards outright. Zero new cards for a week
or two is the normal way out of a backlog, not a failure state.

### Learning new words

Words from a book reached the app days after they were met, entirely
unfamiliar, and the first thing the app did was test them. From the history at
the time: the first choice answer was right 91% of the time (133/146), yet
typing after a single choice answer succeeded only 53% of the time (68/129).
The choice question was being passed by guessing, and the guess was scheduled
as knowledge. Design: `docs/superpowers/specs/2026-11-20-learning-new-words-design.md`.

Decisions worth keeping:

- **The circle is FSRS's own learning steps, stopping at graduation.** With the
  default scheduler, GOOD, GOOD on a new card graduates it to a 6-day interval;
  a third quick GOOD *shortens* that to 4 days. Extra repetition past the steps
  is not free. The steps' 1 and 10-minute waits are not honoured inside a
  session — "a few other cards in between" is the gap that matters while the
  user is here, and FSRS sees the real timestamps.
- **At most four answers per card per session.** HARD keeps a learning card on
  its step, and a slow pick is always HARD, so without a cap a slow reader never
  leaves. The count lives in `Trainer`, not the queue, because the queue is
  rebuilt every time the user comes back from adding a word.
- **Production opens when recognition is in review and was first answered
  before today.** Both facts are already stored (card state, `reviews`), so no
  graduation timestamp or schema change was needed. A lapse back into
  relearning closes production again.
- **The new-word limit counts words; opened production counts as a repeat.**
  Counting production as new let yesterday's words fill today's slots, so words
  arrived every other day. As repeats, any backlog of freshly opened production
  cards spreads under the review cap instead of arriving at once.
- **Only today's circle bypasses the review cap.** Under the old scheme a card
  answered once sat in its learning step until the next session; on the day of
  the change 26 such cards had been left in learning on earlier days. Treating
  every learning card as uncapped would have pushed all of them into one
  session, so a learning card last answered before today is an ordinary repeat.
- **The review cap counts started cards once a day** (`repeatsOn`), not every
  answer, so ten words in their circle do not eat the day's repeats.
  `reviewedOn` still counts every answer for the statistics.

## Next

### Show the words that keep being misspelled

`WordRepository.misspellings()` already groups them by term and wrong spelling.
It has no screen yet; the statistics tab is the obvious home.

### Editing a word

Only adding and deleting exist. Editing needs care: changing the term invalidates
its generated audio, so the file should be dropped and regenerated.

### Personalising the FSRS parameters

Once a few hundred answers have accumulated, the algorithm's 21 parameters can be
fitted to your own memory. Needs an export of `reviews` to CSV and a run of the
Python optimiser; see [SETUP.md](SETUP.md).

### Retention target

FSRS schedules for 90% recall by default. Raising it means more reviews and
firmer knowledge, lowering it the opposite. Worth exposing once the statistics
screen shows enough history to judge the trade-off.
