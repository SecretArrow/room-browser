#!/usr/bin/env python3
"""gen-filterlist.py - embed the bundled blocklist into C.

The desktop editions have no runtime asset lookup: the blocklist that ships
with Room Browser is compiled straight into the binary, so a single static
executable behaves identically on Windows and Linux and there is no path to
get wrong at install time.

The input file is the SAME file the Android edition ships in its APK
(android/app/src/main/assets/filters/hosts.txt), so the two editions always
block the same hosts.

Usage:
    scripts/gen-filterlist.py assets/filters/hosts.txt src/core/rb_filterlist.c

Re-run this whenever assets/filters/hosts.txt changes.
"""

import sys


HEADER = '''/*
 * rb_filterlist.c - GENERATED FILE, DO NOT EDIT BY HAND.
 *
 * Regenerate with:
 *     desktop/scripts/gen-filterlist.py \\
 *         desktop/assets/filters/hosts.txt \\
 *         desktop/src/core/rb_filterlist.c
 *
 * One entry per line of assets/filters/hosts.txt, the same list the Android
 * edition ships. Format: <category>|<host>. Terminated by a NULL sentinel.
 *
 * Stored as an array of lines rather than one big string literal because
 * ISO C99 only guarantees string literals up to 4095 characters, and this
 * list is longer than that (-Woverlength-strings under -Wpedantic).
 */

#include "rb_filterlist.h"

#include <stddef.h> /* NULL */

const char *const RB_FILTERLIST_LINES[] = {
'''


def c_escape(raw: str) -> str:
    """Escape one line for a C string literal.

    Non-ASCII bytes become \\NNN octal escapes so the generated file is pure
    ASCII: MSVC would otherwise interpret the source in the system codepage
    and mis-decode the em dashes in the list's comments. Octal (not \\x) is
    used because it is exactly three digits and therefore cannot swallow a
    following hex digit the way \\x does.
    """
    out = []
    for byte in raw.encode("utf-8"):
        if byte == 0x22:
            out.append('\\"')
        elif byte == 0x5C:
            out.append("\\\\")
        elif 0x20 <= byte < 0x7F:
            out.append(chr(byte))
        else:
            out.append("\\%03o" % byte)
    return "".join(out)


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2

    src, dst = sys.argv[1], sys.argv[2]
    with open(src, "r", encoding="utf-8") as fh:
        text = fh.read()

    lines = ['    "%s",' % c_escape(raw) for raw in text.split("\n")]
    # Drop the trailing empty line produced by the final newline.
    if lines and lines[-1] == '    "",':
        lines.pop()

    with open(dst, "w", encoding="utf-8") as fh:
        fh.write(HEADER)
        fh.write("\n".join(lines))
        fh.write("\n    NULL\n};\n")
    return 0



if __name__ == "__main__":
    sys.exit(main())
