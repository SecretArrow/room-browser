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
 * surrounding whitespace is tolerated (it is trimmed first).
 *
 * This is NOT the omnibox policy — rb_url_classify is, and the omnibox goes
 * through rb_url_decide.  This answers the narrower question "could this be
 * an address?", so it calls "about:blank" navigable where classify() would
 * search for it.  Use it to decide whether to offer a URL-ish affordance,
 * never to decide what to load. */
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

/* Omnibox decider: the navigable URL for `input`, with the default engine
 * behind the search branch.  A thin wrapper over rb_url_classify, so the
 * omnibox and the classifier can never disagree. */
char *rb_url_decide(const char *input);

/* Same decision, but the search branch uses the given engine id (see
 * rb_search.h). An unknown/NULL id falls back to DuckDuckGo, so
 * rb_url_decide(x) == rb_url_decide_engine(x, "duckduckgo"). */
char *rb_url_decide_engine(const char *input, const char *engine_id);

/* --- UrlIntelligence.classify --- */

/* Which branch of classify() an input took, matching UrlIntelligence.Input. */
enum {
    RB_URL_INPUT_WEB = 0,
    RB_URL_INPUT_SEARCH = 1
};

/* The port of UrlIntelligence.classify, and the single decider behind both
 * rb_url_decide entry points.  `input` is trimmed, then classified in the
 * Android order — file://, whitespace, IPv4, IPv6, localhost, explicit
 * scheme, bare domain, search — and the URL to navigate to is returned
 * malloc'd.  `out_kind` receives RB_URL_INPUT_WEB when that URL is the page
 * itself and RB_URL_INPUT_SEARCH when it is a search for the input;
 * `out_upgraded` receives 1 when a bare domain was navigated as https://
 * that the user did not type (see the note below).  Both out-parameters may
 * be NULL.  Blank input yields a malloc'd "" of kind SEARCH.
 *
 * The cases worth knowing, because they are deliberate and not obvious:
 *  - an IPv4/IPv6 literal or localhost goes to http://, never https://
 *  - an explicitly typed http:// or https:// URL is loaded as typed (only
 *    link navigations inside pages are upgraded automatically)
 *  - about:, data:, blob: and javascript: are SEARCHED FOR, not loaded
 *  - a bare domain with a dot is upgraded to https://, but a dotted name with
 *    a PORT is not a bare domain at all: "word:..." matches the explicit-
 *    scheme pattern, which is tested first, so "example.com:8443" is searched
 *    for.  (The port group in Android's LOOKS_LIKE_DOMAIN is unreachable for
 *    the same reason.)  An IP literal or localhost with a port is caught
 *    earlier still, and does go to http:// with the port kept.
 *
 * DEVIATION: Android's Input.Web.upgradedToHttps is false on the bare-domain
 * branch (upgrade() is handed a URL that is already https, so the rewrite
 * never fires) and nothing in the Android app reads that flag.  Here
 * `out_upgraded` answers the useful question — "did the browser choose https
 * for a bare domain?" — which is the signal the desktop HTTPS-first wiring
 * wants.  The returned URL is identical either way. */
char *rb_url_classify(const char *input, const char *engine_id, int *out_kind,
                      int *out_upgraded);

/* --- URL inspection (all results malloc'd; caller frees) --- */

/* 1 when the URL starts with "http://" (scheme compared case-insensitively). */
int   rb_url_is_http(const char *url);

/* 1 when the URL starts with "https://". */
int   rb_url_is_https(const char *url);

/* Host component of a URL, lowercased and without userinfo/port/brackets.
 * NULL when the URL has no authority (e.g. "about:blank") or is NULL. */
char *rb_url_host_of(const char *url);

/* Path component ("/..." including a leading slash), defaulting to "/" when
 * the URL carries no path. Never NULL for a parseable absolute URL. */
char *rb_url_path_of(const char *url);

/* Omnibox-friendly shortening: drops a leading "http://" / "https://" and a
 * trailing "/". Everything else is preserved verbatim. */
char *rb_url_display(const char *url);

/* HTTPS-First upgrade (mirrors Android's UrlIntelligence.upgrade()):
 *  - a non-http URL is returned unchanged with *out_upgraded = 0
 *  - "http://host/path" (no port, or an explicit :80) becomes
 *    "https://host/path" with *out_upgraded = 1
 *  - an explicit NON-DEFAULT port (anything but 80) is left on http with
 *    *out_upgraded = 0: such ports are spelled out precisely because the
 *    endpoint is non-standard (localhost dev servers, router/IoT panels)
 *    and TLS there is rare — upgrading them dead-ends the load.
 * *out_upgraded may be NULL when the caller does not care. */
char *rb_url_upgrade_to_https(const char *url, int *out_upgraded);

/* Percent-encodes every byte outside the RFC 3986 unreserved set
 * (A-Z a-z 0-9 - . _ ~) as UTF-8 %XX.  Never NULL; "" encodes to "".
 *
 * Deliberately not rb_search_encode(), which applies the same encoding but
 * also trims leading and trailing whitespace — right for a search box, wrong
 * for a value that has to survive verbatim inside another URL's query. */
char *rb_url_encode_component(const char *s);

/* The Google Translate web wrapper for `url`, in language `target`:
 *
 *   https://translate.google.com/translate?sl=auto&tl=<target>&u=<url encoded>
 *
 * the URL Android's TranslateDialog builds, `sl=auto` included (the source
 * language is whatever the page is).  Not byte-identical to Android's: that
 * one spells the value with Java's URLEncoder, which writes a space as "+"
 * and escapes "~", where this writes "%20" and leaves "~" alone.  Both forms
 * decode to the same string in the `u` parameter, and this one is the RFC
 * 3986 spelling, so the request that leaves here is the same request.
 *
 * `target` is used verbatim: a blank value produces "tl=", which is what
 * Android produces from a blank setting too, and inventing a fallback here
 * would translate the same stored value into two different languages
 * depending on which edition read it.
 *
 * NULL when there is nothing to translate — a NULL or empty URL, or one whose
 * scheme is "about:".  Android refuses its own about:home page; this refuses
 * every page the browser owns rather than the one page Android happens to
 * have. */
char *rb_url_translate_wrapper(const char *url, const char *target);

#ifdef __cplusplus
}
#endif

#endif /* RB_URL_H */
