/*
 * rb_filterlist.h — the bundled, offline blocklist compiled into the binary.
 *
 * The definition lives in rb_filterlist.c, which is GENERATED from
 * assets/filters/hosts.txt (the same file the Android edition ships in its
 * APK). Nothing here phones home; there is no remote fetch path at all.
 */

#ifndef RB_FILTERLIST_H
#define RB_FILTERLIST_H

#ifdef __cplusplus
extern "C" {
#endif

/* "<category>|<host>" lines, with "#" comment lines interleaved, in file
 * order, terminated by a NULL sentinel. */
extern const char *const RB_FILTERLIST_LINES[];

#ifdef __cplusplus
}
#endif

#endif /* RB_FILTERLIST_H */
