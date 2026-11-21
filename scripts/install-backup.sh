#!/usr/bin/env bash
#
# Sets up the database backup: installs backup-db.sh and restore-db.sh as
# ~/.local/bin/lingue-backup and ~/.local/bin/lingue-restore, adds a systemd
# user timer that runs the backup every morning at 08:00, and takes the first
# snapshot.
#
# Morning rather than midnight: the laptop is off at night, and a morning run
# picks up the previous evening's answers. Persistent=true makes a run missed
# while the computer was off or asleep happen as soon as it is back on; a
# failed run (no network yet) is retried every 15 minutes.
#
# No sudo involved. Re-run after changing the scripts.
#
# Usage: scripts/install-backup.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$HOME/.local/bin"
UNITS="$HOME/.config/systemd/user"

echo "==> Installing the scripts into $BIN"
install -Dm755 "$ROOT/scripts/backup-db.sh" "$BIN/lingue-backup"
install -Dm755 "$ROOT/scripts/restore-db.sh" "$BIN/lingue-restore"

echo "==> Adding the systemd timer"
mkdir -p "$UNITS"
cat > "$UNITS/lingue-backup.service" <<'EOF'
[Unit]
Description=Back up the Lingue database to GitHub
After=network-online.target

[Service]
Type=oneshot
ExecStart=%h/.local/bin/lingue-backup
Restart=on-failure
RestartSec=15min
EOF

cat > "$UNITS/lingue-backup.timer" <<'EOF'
[Unit]
Description=Morning backup of the Lingue database

[Timer]
OnCalendar=*-*-* 08:00:00
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl --user daemon-reload
systemctl --user enable --now lingue-backup.timer

echo "==> Taking the first snapshot"
"$BIN/lingue-backup"

echo
systemctl --user list-timers lingue-backup.timer --no-pager
