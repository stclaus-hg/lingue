#!/usr/bin/env bash
#
# Installs Lingue as a normal desktop application: a self-contained copy under
# ~/.local/opt, an entry in the applications menu, and a shortcut on the desktop.
#
# No sudo involved — everything lands in the home directory.
#
# Re-run after changing the code to update the installed copy.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREFIX="$HOME/.local/opt/lingue"
APPLICATIONS="$HOME/.local/share/applications"
ICONS="$HOME/.local/share/icons/hicolor/512x512/apps"
ENTRY="$APPLICATIONS/lingue.desktop"

cd "$ROOT"

echo "==> Building a self-contained application image"
./gradlew createDistributable --console=plain --quiet

image="build/compose/binaries/main/app/lingue"
[ -x "$image/bin/lingue" ] || { echo "expected launcher at $image/bin/lingue" >&2; exit 1; }

echo "==> Installing into $PREFIX"
# The build directory is disposable (./gradlew clean wipes it), so the launcher
# the desktop entry points at has to live somewhere stable.
rm -rf "$PREFIX"
mkdir -p "$(dirname "$PREFIX")"
cp -r "$image" "$PREFIX"

echo "==> Registering the icon and menu entry"
install -Dm644 icons/lingue.png "$ICONS/lingue.png"
mkdir -p "$APPLICATIONS"

cat > "$ENTRY" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=Lingue
GenericName=Vocabulary trainer
Comment=Тренажёр английских слов
Exec=$PREFIX/bin/lingue
Icon=lingue
Terminal=false
Categories=Education;Languages;
StartupWMClass=lingue
EOF
chmod +x "$ENTRY"

update-desktop-database "$APPLICATIONS" 2>/dev/null || true
gtk-update-icon-cache -f -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true

desktop_dir="$(xdg-user-dir DESKTOP 2>/dev/null || true)"
: "${desktop_dir:=$HOME/Desktop}"

if [ -d "$desktop_dir" ]; then
    echo "==> Placing a shortcut in $desktop_dir"
    cp "$ENTRY" "$desktop_dir/lingue.desktop"
    chmod +x "$desktop_dir/lingue.desktop"
    # GNOME refuses to launch desktop files it does not consider trusted.
    gio set "$desktop_dir/lingue.desktop" metadata::trusted true 2>/dev/null || true
else
    echo "==> No desktop directory found; menu entry only"
fi

echo "==> Done. Launch from the applications menu, the desktop, or:"
echo "    $PREFIX/bin/lingue"
