#!/bin/sh
# Compile (and link) the GTK layer against the private GTK3/WebKitGTK prefix
# built by scripts/fetch-gtk-deps.sh.
#
# This is the local verification loop for src/gtk when the machine has no
# desktop toolkit installed: `-c` alone catches type/arity drift against the
# core headers, and the link catches calls to core functions that do not
# exist at all (an undeclared function is only a warning in C11, so it would
# otherwise survive to link time).
#
# It does NOT run the binary: the WebKitGTK runtime needs GStreamer and a
# display, which this environment does not have.
#
# Usage:  scripts/check-gtk.sh
set -e
DEPS=${RB_DEPS:-/var/tmp/rbdeps}
ROOT=$DEPS/root
LIB=$DEPS/lib
BUILD=${RB_BUILD_DIR:-/tmp/rbbuild}

[ -d "$ROOT" ] || { echo "run scripts/fetch-gtk-deps.sh first" >&2; exit 2; }

PKG_CONFIG_PATH=$(find "$ROOT" -name '*.pc' | sed 's|/[^/]*$||' | sort -u | tr '\n' ':')
export PKG_CONFIG_PATH PKG_CONFIG_SYSROOT_DIR="$ROOT"

cd "$(dirname "$0")/.."
CFLAGS="-std=c11 -Wall -Wextra -Wpedantic -Werror=implicit-function-declaration"
INCS="-Isrc -Isrc/gtk -I$BUILD"
GENTYPE=$(pkg-config --cflags gtk+-3.0 webkit2gtk-4.1)

rc=0
OBJS=""
for f in src/gtk/main.c src/gtk/chrome.c src/gtk/webview.c; do
    o=$DEPS/$(basename "$f" .c).o
    if gcc $CFLAGS -c "$f" -o "$o" $GENTYPE $INCS; then
        echo "ok    $f"
        OBJS="$OBJS $o"
    else
        echo "FAIL  $f"
        rc=1
    fi
done
[ "$rc" = 0 ] || exit 1

# Link against the core the same way CMake does (rb_core + PkgConfig::GTK).
if [ -f "$BUILD/librb_core.a" ]; then
    if gcc -o "$DEPS/roombrowser" $OBJS "$BUILD/librb_core.a" -L"$LIB" \
        $(pkg-config --libs gtk+-3.0 webkit2gtk-4.1) -Wl,-rpath,"$LIB"; then
        echo "ok    link -> $DEPS/roombrowser"
    else
        echo "FAIL  link"
        rc=1
    fi
else
    echo "note  $BUILD/librb_core.a not built; skipped the link"
fi
exit $rc
