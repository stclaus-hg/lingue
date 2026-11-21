#!/usr/bin/env bash
#
# Tests backup-db.sh and restore-db.sh against throwaway directories and a local
# bare repository standing in for GitHub. Touches nothing outside a temp dir.
#
# Usage: scripts/test-backup-db.sh

set -euo pipefail

SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

export LINGUE_DB="$WORK/data/lingue.db"
export LINGUE_BACKUP_DIR="$WORK/backup"
export LINGUE_BACKUP_REMOTE="$WORK/remote.git"
export LINGUE_SKIP_APP_CHECK=1   # the real app may well be open while this runs
export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.com
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.com

failures=0
pass() { echo "  ok   $1"; }
fail() { echo "  FAIL $1" >&2; failures=$((failures + 1)); }
check() { local name="$1"; shift; if "$@"; then pass "$name"; else fail "$name"; fi; }

sql() { python3 -c 'import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); r=c.execute(sys.argv[2]).fetchall(); c.commit(); print(r[0][0] if r else "")' "$@"; }
remote_commits() { git -C "$LINGUE_BACKUP_REMOTE" rev-list --count backup 2>/dev/null || echo 0; }
remote_snapshot_words() {
    git -C "$LINGUE_BACKUP_REMOTE" show "backup:lingue.db" > "$WORK/peek.db"
    sql "$WORK/peek.db" "SELECT COUNT(*) FROM words"
}

mkdir -p "$WORK/data"
git init -q --bare "$LINGUE_BACKUP_REMOTE"
python3 - "$LINGUE_DB" <<'EOF'
import sqlite3, sys
c = sqlite3.connect(sys.argv[1])
c.execute("PRAGMA journal_mode = WAL")
c.execute("CREATE TABLE words (id INTEGER PRIMARY KEY, term TEXT)")
c.execute("INSERT INTO words (term) VALUES ('cozy')")
c.execute("PRAGMA user_version = 7")
c.commit()
EOF
# Keep a connection open with an uncheckpointed write, as the running app would.
python3 - "$LINGUE_DB" <<'EOF' &
import sqlite3, sys, time
c = sqlite3.connect(sys.argv[1])
c.execute("INSERT INTO words (term) VALUES ('in-wal')")
c.commit()
time.sleep(30)
EOF
holder=$!
sleep 1

echo "first run"
"$SCRIPTS/backup-db.sh" > /dev/null
check "creates the backup branch on the remote" test "$(remote_commits)" = 1
check "the snapshot includes writes still in the WAL" test "$(remote_snapshot_words)" = 2
check "the snapshot keeps the schema version" test "$(sql "$WORK/peek.db" "PRAGMA user_version")" = 7
kill "$holder" 2>/dev/null || true

echo "unchanged database"
"$SCRIPTS/backup-db.sh" > /dev/null
check "makes no new commit" test "$(remote_commits)" = 1

echo "changed database"
sql "$LINGUE_DB" "INSERT INTO words (term) VALUES ('reliable')" > /dev/null
"$SCRIPTS/backup-db.sh" > /dev/null
check "makes a new commit" test "$(remote_commits)" = 2

echo "remote unreachable"
sql "$LINGUE_DB" "INSERT INTO words (term) VALUES ('curious')" > /dev/null
if git -C "$LINGUE_BACKUP_DIR" remote set-url origin "$WORK/missing.git" \
    && ! "$SCRIPTS/backup-db.sh" > /dev/null 2>&1; then pass "reports the failed push"; else fail "reports the failed push"; fi
check "keeps the snapshot committed locally" test "$(git -C "$LINGUE_BACKUP_DIR" rev-list --count HEAD)" = 3
git -C "$LINGUE_BACKUP_DIR" remote set-url origin "$LINGUE_BACKUP_REMOTE"
"$SCRIPTS/backup-db.sh" > /dev/null
check "pushes the held-back snapshot on the next run" test "$(remote_commits)" = 3

echo "fresh machine"
rm -rf "$LINGUE_BACKUP_DIR"
sql "$LINGUE_DB" "INSERT INTO words (term) VALUES ('a witness')" > /dev/null
"$SCRIPTS/backup-db.sh" > /dev/null
check "continues the existing history" test "$(remote_commits)" = 4

echo "restore"
first="$(git -C "$LINGUE_BACKUP_REMOTE" rev-list --reverse backup | head -1)"
touch "$LINGUE_DB-wal" "$LINGUE_DB-shm"
"$SCRIPTS/restore-db.sh" "$first" > /dev/null
check "puts the chosen snapshot in place" test "$(sql "$LINGUE_DB" "SELECT COUNT(*) FROM words")" = 2
check "keeps the replaced database aside" test "$(sql "$LINGUE_DB.before-restore" "SELECT COUNT(*) FROM words")" = 5
check "removes the stale WAL files" test ! -e "$LINGUE_DB-wal"
"$SCRIPTS/restore-db.sh" > /dev/null
check "restores the latest snapshot by default" test "$(sql "$LINGUE_DB" "SELECT COUNT(*) FROM words")" = 5

if [ "$failures" -eq 0 ]; then echo "all passed"; else echo "$failures failed" >&2; exit 1; fi
