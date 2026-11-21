#!/usr/bin/env bash
#
# Snapshots the Lingue database and pushes it to the `backup` branch of the
# project's GitHub repository. Each snapshot is one commit, so any earlier day
# can be restored (see restore-db.sh). An unchanged database makes no commit.
#
# Safe while the app is running: the copy is taken with SQLite's backup API,
# which reads a consistent state, including writes still in the WAL file.
#
# If the push fails (offline), the snapshot stays committed locally, the script
# exits non-zero, and the next run pushes it.
#
# Run every morning at 08:00 by the systemd timer that install-backup.sh sets up.
#
# Environment (defaults in brackets):
#   LINGUE_DB             database to back up   [~/.local/share/lingue/lingue.db]
#   LINGUE_BACKUP_DIR     local checkout        [~/.local/share/lingue/backup]
#   LINGUE_BACKUP_REMOTE  repository, used only when the checkout is first made
#                         [git@github.com:stclaus-hg/lingue.git]

set -euo pipefail

DB="${LINGUE_DB:-$HOME/.local/share/lingue/lingue.db}"
DIR="${LINGUE_BACKUP_DIR:-$HOME/.local/share/lingue/backup}"
REMOTE="${LINGUE_BACKUP_REMOTE:-git@github.com:stclaus-hg/lingue.git}"
BRANCH=backup

[ -f "$DB" ] || { echo "no database at $DB" >&2; exit 1; }

if [ ! -d "$DIR/.git" ]; then
    # Exit code 2 means the remote answered and has no such branch; anything
    # else is a failure to reach it. Starting a fresh history on a network
    # error would later collide with the real one, so only 2 starts afresh.
    status=0
    git ls-remote --exit-code --heads "$REMOTE" "$BRANCH" > /dev/null || status=$?
    case "$status" in
        0) git clone -q --single-branch --branch "$BRANCH" "$REMOTE" "$DIR" ;;
        2) git init -q -b "$BRANCH" "$DIR"
           git -C "$DIR" remote add origin "$REMOTE" ;;
        *) echo "cannot reach $REMOTE" >&2; exit 1 ;;
    esac
fi

python3 - "$DB" "$DIR/lingue.db.tmp" <<'EOF'
import sqlite3, sys
source = sqlite3.connect(sys.argv[1])
target = sqlite3.connect(sys.argv[2])
source.backup(target)
target.close()
source.close()
EOF
mv "$DIR/lingue.db.tmp" "$DIR/lingue.db"

cd "$DIR"
git add lingue.db
if git diff --cached --quiet; then
    echo "database unchanged since the last snapshot"
else
    git commit -q -m "Snapshot $(date '+%Y-%m-%d %H:%M')"
    echo "snapshot committed"
fi

git push -q -u origin "$BRANCH"
echo "pushed to $(git remote get-url origin) ($BRANCH)"
