# Setup

## Requirements

- **JDK 17 or newer.** The system Java on Ubuntu 20.04 is 11, which Compose
  cannot use. A Temurin 21 build is installed under `~/.local/jdk` and pinned in
  `gradle.properties`, so no `JAVA_HOME` export is needed.
- **The FSRS library.** Not on Maven Central yet — see below.

## First run on a new machine

```bash
scripts/install-fsrs.sh   # clones java-fsrs, runs its tests, installs to ~/.m2
./gradlew run
```

`install-fsrs.sh` runs the upstream test suite before publishing. If those tests
fail, the dependency is not installed — that check is the reason this project
uses the library instead of porting the algorithm by hand.

## Everyday commands

| Command | What it does |
|---|---|
| `./gradlew run` | Starts the app |
| `./gradlew test` | Runs the unit tests |
| `./gradlew importWords -Pfile=data/words.tsv` | Bulk-imports a word list |
| `scripts/generate-audio.sh` | Synthesises pronunciations for words that lack one |
| `scripts/install-desktop.sh` | Installs the app with a menu entry and desktop shortcut |
| `./gradlew packageDeb` | Builds a `.deb` under `build/compose/binaries` |

## Installing it as a desktop application

```bash
scripts/install-desktop.sh
```

This runs `createDistributable`, which uses `jpackage` to bundle a trimmed JRE
alongside the classes, then copies the result to `~/.local/opt/lingue` and writes
a `.desktop` entry. Nothing needs sudo and nothing is installed system-wide.

Two details worth knowing:

- The copy step is not redundant. `createDistributable` writes into `build/`,
  which `./gradlew clean` deletes — a desktop entry pointing there would break
  the first time the project was cleaned.
- The bundled runtime is why the launcher does not depend on the JDK under
  `~/.local/jdk` remaining in place.

Re-run the script after changing the code to refresh the installed copy.

## Importing a word list

The import file is tab-separated, one word per line:

```
term<TAB>translation<TAB>example (optional)
```

Blank lines and `#` comments are ignored. Words already in the collection are
skipped rather than overwritten, so re-running an import never resets the
scheduling state of words already being learned — which means the file can be
kept alongside the database as a plain-text backup of what is being studied.

Close the app before importing: it holds the database open, and it only reads
the word list at startup.

## Pronunciation

Audio is generated ahead of time with [Piper](https://github.com/OHF-voice/piper1-gpl)
and stored as one WAV per word. The app only opens files — no speech engine is
needed at runtime, and playback is instant.

One-time setup, no sudo required:

```bash
uv venv --python 3.12 ~/.local/opt/piper-env
uv pip install --python ~/.local/opt/piper-env/bin/python piper-tts
cd ~/.local/share/lingue/voices && \
  ~/.local/opt/piper-env/bin/python -m piper.download_voices en_US-lessac-medium
```

Then, after adding words:

```bash
scripts/generate-audio.sh          # only the missing ones
scripts/generate-audio.sh --force  # everything, e.g. after changing the voice
```

Ready-made human recordings were considered and rejected: a quarter of a typical
list is phrases (`to change one's mind`), which no dictionary records. See
[BACKLOG.md](BACKLOG.md) for the full reasoning.

**A card's answer must never be spoken before it is submitted.** Recognition
cards show the English term, so they are pronounced on sight; production cards
ask for it, so they stay silent until the answer is graded. The rule is enforced
in `TrainScreen` by matching on the question type.

## Where the data lives

A single SQLite file:

```
~/.local/share/lingue/lingue.db
```

It holds both the words and the full answer history, so it is the one file that
cannot be rebuilt. Copying it with `cp` while the app is open can catch it
half-written; use the backup below instead.

## Backup

```bash
scripts/install-backup.sh   # once per machine
```

Every evening at 22:00 a systemd user timer takes a snapshot of the database
through SQLite's backup API (consistent even while the app is open) and commits
it to the `backup` branch of this repository. The branch shares no history with
`main`; each commit is one day's database, and a day with no answers makes no
commit. 22:00 rather than midnight because the laptop is off by midnight; a run
missed while the computer was off or asleep happens as soon as it is back on. A
run without network keeps its commit locally and is retried every 15 minutes.
Run `lingue-backup` by hand to snapshot a session after 22:00.

The local checkout of that branch is `~/.local/share/lingue/backup`.

```bash
lingue-backup                                    # snapshot now
git -C ~/.local/share/lingue/backup log --oneline   # list snapshots
lingue-restore                                   # restore the latest
lingue-restore <commit>                          # restore a given day
systemctl --user list-timers lingue-backup.timer # when the next run is
journalctl --user -u lingue-backup.service       # what the runs said
```

`lingue-restore` refuses while the app is running, checks the snapshot's
integrity first, and keeps the database it replaces as
`lingue.db.before-restore`. On a new machine it fetches the snapshots itself.

`scripts/test-backup-db.sh` exercises both scripts against a temporary
directory and a local stand-in for GitHub.

## Layout

```
src/main/kotlin/lingue/
├── core/     domain — no UI, no SQL, no framework
│   ├── Model.kt           Word, Direction, StudyCard, Question
│   ├── Grading.kt         answer -> FSRS rating
│   ├── QuestionBuilder.kt prompt assembly and distractors
│   ├── ReviewEngine.kt    wrapper over the FSRS scheduler
│   └── Cards.kt           card creation
├── data/     SQLite persistence
├── app/      Trainer — session state, daily limits
└── ui/       Compose screens
```

The boundary is worth keeping: `core/` contains no `androidx.compose` or
`java.sql` import, which is what makes a different front end (a web UI, a phone
app) a matter of writing another adapter rather than a rewrite.

Check it holds:

```bash
! grep -rE "androidx\.compose|java\.sql" src/main/kotlin/lingue/core/
```

## Personalising the algorithm later

FSRS ships with default parameters fitted to a large population. Once a few
hundred answers have accumulated in the `reviews` table, they can be fitted to
your own memory instead:

1. Export the `reviews` table to CSV.
2. Run the Python optimiser (`pip install "fsrs[optimizer]"`, needs Python 3.10+).
3. Paste the resulting 21 numbers into a custom `Scheduler.builder().parameters(...)`.

The optimiser is a PyTorch job, which is why it stays outside the application —
it runs once every few months, not at review time.
