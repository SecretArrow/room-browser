/*
 * rb_url.h — omnibox URL heuristics for Room Browser core.
 *
 * All returned strings are freshly malloc'd (caller frees with free()).
 * All input/output strings are UTF-8.
 */

#ifndef RB_URL_H
#define RB_URL_H

#ifdef __cplusplus
extern "C" {
#endif

/* Heuristic: 1 when the input looks like something to navigate to (has a
 * scheme such as "https://", "about:"; looks like host[:port], an IPv4/IPv6
 * literal, or "localhost"), 0 when it should be treated as search text.
 * Interior whitespace or any non-ASCII byte makes the input search text;
 * surrounding whitespace is tolerated (it is trimmed first). */
int rb_url_is_probably_url(const char *input);

/* Normalizes an omnibox input into a navigable URL:
 *   "example.com"      -> "https://example.com/"
 *   "http://example.com" -> "http://example.com/"   (scheme is kept)
 *   "about:blank"      -> "about:blank"             (opaque schemes untouched)
 * A "/" is inserted after a bare authority, also in front of a leading
 * '?' or '#'.  Surrounding whitespace is trimmed.  NULL or empty input
 * yields a malloc'd "" (caller decides what that means). */
char *rb_url_normalize(const char *input);

/* Builds a DuckDuckGo search URL with the query percent-encoded as UTF-8:
 * space -> %20, '&' -> %26, '%' -> %25, non-ASCII bytes -> %XX each;
 * A-Z a-z 0-9 '-' '.' '_' '~' stay literal.  NULL query encodes as "". */
char *rb_url_build_search(const char *query);

/* Omnibox decider: probably-URL -> rb_url_normalize(), otherwise
 * rb_url_build_search(). */
char *rb_url_decide(const char *input);

#ifdef __cplusplus
}
#endif

#endif /* RB_URL_H */
