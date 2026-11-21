#!/usr/bin/env bash
#
# Puts a snapshot made by backup-db.sh back in place of the Lingue database.
# The database being replaced is kept as lingue.db.before-restore.
#
# Usage:
#   scripts/restore-db.sh            # the latest snapshot
#   scripts/restore-db.sh <commit>   # a specific one
#
# To pick a commit:  git -C ~/.local/share/lingue/backup log --oneline
#
# Close the app first; the script refuses while it is running.
#
# Environment: LINGUE_DB, LINGUE_BACKUP_DIR, LINGUE_BACKUP_REMOTE as in backup-db.sh.

set -euo pipefail

DB="${LINGUE_DB:-$HOME/.local/share/lingue/lingue.db}"
DIR="${LINGUE_BACKUP_DIR:-$HOME/.local/share/lingue/backup}"
REMOTE="${LINGUE_BACKUP_REMOTE:-git@github.com:stclaus-hg/lingue.git}"
REVISION="${1:-}"

if [ -z "${LINGUE_SKIP_APP_CHECK:-}" ] && { pgrep -x lingue || pgrep -f 'lingue\.MainKt'; } > /dev/null; then
    echo "Lingue is running — close it first." >&2
    exit 1
fi

# On a new machine there is no checkout yet: fetch the snapshots first.
if [ ! -d "$DIR/.git" ]; then
    git clone -q --single-branch --branch backup "$REMOTE" "$DIR"
elif [ -z "$REVISION" ]; then
    git -C "$DIR" pull -q --ff-only || echo "could not fetch newer snapshots; using the local ones" >&2
fi

snapshot="$(mktemp)"
trap 'rm -f "$snapshot"' EXIT
git -C "$DIR" show "${REVISION:-HEAD}:lingue.db" > "$snapshot"

python3 - "$snapshot" <<'EOF'
import sqlite3, sys
result = sqlite3.connect(sys.argv[1]).execute("PRAGMA integrity_check").fetchone()[0]
if result != "ok":
    sys.exit(f"the snapshot is damaged: {result}")
EOF

if [ -f "$DB" ]; then
    # Through SQLite rather than cp, so writes still in the WAL are kept too.
    python3 - "$DB" "$DB.before-restore" <<'EOF'
import sqlite3, sys
source = sqlite3.connect(sys.argv[1])
target = sqlite3.connect(sys.argv[2])
source.backup(target)
target.close()
source.close()
EOF
fi

mkdir -p "$(dirname "$DB")"
rm -f "$DB-wal" "$DB-shm"
cp "$snapshot" "$DB"
echo "restored $(git -C "$DIR" log -1 --format='%h %s' "${REVISION:-HEAD}")"
if [ -f "$DB.before-restore" ]; then
    echo "the previous database is kept as $DB.before-restore"
fi
