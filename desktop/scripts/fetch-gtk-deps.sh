#!/bin/sh
# Fetch GTK3 + WebKitGTK 4.1 development headers WITHOUT root, so the GTK
# layer can be compile-checked on a machine that has no desktop toolkit
# installed (and no sudo to install one).
#
# How it works: the .deb files are downloaded with the ordinary package
# manager, unpacked with dpkg-deb into a private prefix, and pkg-config is
# pointed at that prefix with PKG_CONFIG_SYSROOT_DIR.  Everything lands under
# $RB_DEPS (default /var/tmp/rbdeps); nothing outside it is touched and no
# package is ever installed.
#
# Notes worth keeping:
#   * --define-prefix is NOT used: Debian puts .pc files in
#     $prefix/lib/<triplet>/pkgconfig, which --define-prefix mis-reads, so the
#     sysroot variable is the correct mechanism.
#   * The apt index on a stale image lists versions the mirror has already
#     rotated out, so this refreshes the index into a private directory first
#     (Dir::State::lists), which needs no root either.
#   * The -dev packages ship "libfoo.so -> libfoo.so.N" symlinks but not the
#     shared objects themselves, and the runtime packages may be absent or
#     dangling.  A link directory of resolved symlinks is built from both the
#     private prefix and the system, which is what makes -lfoo resolvable.
#
# Usage:  scripts/fetch-gtk-deps.sh
set -e

DEPS=${RB_DEPS:-/var/tmp/rbdeps}
PKGS="libgtk-3-dev libwebkit2gtk-4.1-dev"
APTOPT="-o Dir::State::lists=$DEPS/apt/lists -o Dir::Cache=$DEPS/apt/cache -o Debug::NoLocking=1"

mkdir -p "$DEPS/apt/lists" "$DEPS/apt/cache" "$DEPS/debs" "$DEPS/root" "$DEPS/lib"

echo "== refreshing the package index into $DEPS/apt"
apt-get $APTOPT update >/dev/null

echo "== resolving $PKGS"
apt-get $APTOPT install --print-uris -y --no-install-recommends $PKGS 2>/dev/null |
    grep -oE "http[^ ']+\.deb" | sort -u > "$DEPS/urls.txt"

# The WebKit runtime is not a dependency of anything above when the image has
# no registry entry for it, and it is needed to LINK; ask for it by name too.
apt-get $APTOPT install --print-uris -y --no-install-recommends \
    libwebkit2gtk-4.1-0 2>/dev/null |
    grep -oE "http[^ ']+\.deb" | sort -u >> "$DEPS/urls.txt"
sort -u "$DEPS/urls.txt" -o "$DEPS/urls.txt"

echo "== downloading $(wc -l < "$DEPS/urls.txt") archives"
(cd "$DEPS/debs" && xargs -a "$DEPS/urls.txt" -P 8 -n 1 \
    curl -sSLO --retry 3 >/dev/null 2>&1 || true)

# A 404 leaves a tiny HTML error page behind; unpacking it "succeeds" silently
# and yields no headers, so drop anything that is not a real archive first.
find "$DEPS/debs" -name '*.deb' -size -4k -delete

echo "== unpacking into $DEPS/root"
for f in "$DEPS"/debs/*.deb; do
    dpkg-deb -x "$f" "$DEPS/root" 2>/dev/null || true
done

echo "== building the link directory"
rm -f "$DEPS"/lib/*
for d in /usr/lib/x86_64-linux-gnu "$DEPS/root/usr/lib/x86_64-linux-gnu"; do
    for f in "$d"/lib*.so.*; do
        [ -f "$f" ] && ln -sf "$f" "$DEPS/lib/"
    done
done
# libfoo.so for every libfoo.so.N, so -lfoo resolves.
(cd "$DEPS/lib" && for f in lib*.so.*; do ln -sf "$f" "${f%%.so.*}.so"; done)

echo "== done. $DEPS/root has $(find "$DEPS/root" -name '*.pc' | wc -l) .pc files"
