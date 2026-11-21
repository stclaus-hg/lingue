"""Synthesises a pronunciation for every English term in the collection.

Audio is generated ahead of time rather than at review time: the app then only
opens a WAV file, which keeps it self-contained (no Python at runtime) and makes
playback instant.

Run through scripts/generate-audio.sh, which supplies the right interpreter.
"""

import argparse
import re
import sqlite3
import sys
import wave
from pathlib import Path

from piper import PiperVoice

HOME = Path.home()
DEFAULT_DB = HOME / ".local/share/lingue/lingue.db"
DEFAULT_OUT = HOME / ".local/share/lingue/audio"
DEFAULT_VOICE = HOME / ".local/share/lingue/voices/en_US-lessac-medium.onnx"

# Vocabulary shorthand is written, not spoken. Left as-is it would be read out
# letter by letter and teach the wrong thing.
PLACEHOLDERS = {
    r"\bsth\b": "something",
    r"\bsb\b": "somebody",
    r"\bsmth\b": "something",
    r"\bone's\b": "one's",
}


def speakable(term: str) -> str:
    text = term.strip()
    for pattern, replacement in PLACEHOLDERS.items():
        text = re.sub(pattern, replacement, text, flags=re.IGNORECASE)
    return text


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", type=Path, default=DEFAULT_DB)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--voice", type=Path, default=DEFAULT_VOICE)
    parser.add_argument(
        "--force",
        action="store_true",
        help="regenerate files that already exist",
    )
    args = parser.parse_args()

    if not args.database.is_file():
        print(f"no database at {args.database}", file=sys.stderr)
        return 2
    if not args.voice.is_file():
        print(f"no voice model at {args.voice}", file=sys.stderr)
        return 2

    args.output.mkdir(parents=True, exist_ok=True)

    connection = sqlite3.connect(f"file:{args.database}?mode=ro", uri=True)
    words = connection.execute("SELECT id, term FROM words ORDER BY id").fetchall()
    connection.close()

    pending = [
        (word_id, term)
        for word_id, term in words
        if args.force or not (args.output / f"{word_id}.wav").is_file()
    ]

    if not pending:
        print(f"nothing to do — all {len(words)} words already have audio")
        return 0

    print(f"loading {args.voice.name}")
    voice = PiperVoice.load(args.voice)

    for index, (word_id, term) in enumerate(pending, start=1):
        target = args.output / f"{word_id}.wav"
        # Write to a temporary name first: an interrupted run must not leave a
        # truncated file that later looks like valid, already-generated audio.
        staging = target.with_suffix(".wav.part")
        with wave.open(str(staging), "wb") as wav:
            voice.synthesize_wav(speakable(term), wav)
        staging.replace(target)
        print(f"  [{index}/{len(pending)}] {term}")

    print(f"done — {len(pending)} generated, {len(words)} words total")
    return 0


if __name__ == "__main__":
    sys.exit(main())
