#!/usr/bin/env bash
#
# Generates a pronunciation for every word that does not have one yet.
#
# Piper is a Python package, so it lives in its own environment outside the app;
# the app itself only ever opens the resulting WAV files. Run this after adding
# words. Passing --force regenerates everything, e.g. after changing the voice.
#
# One-time setup (no sudo needed):
#   uv venv --python 3.12 ~/.local/opt/piper-env
#   uv pip install --python ~/.local/opt/piper-env/bin/python piper-tts
#   cd ~/.local/share/lingue/voices && \
#     ~/.local/opt/piper-env/bin/python -m piper.download_voices en_US-lessac-medium

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON="$HOME/.local/opt/piper-env/bin/python"

if [ ! -x "$PYTHON" ]; then
    echo "Piper environment missing at $PYTHON — see the setup commands in this script." >&2
    exit 1
fi

exec "$PYTHON" "$ROOT/scripts/generate_audio.py" "$@"
