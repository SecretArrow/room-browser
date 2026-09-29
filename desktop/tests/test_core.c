/*
 * test_core.c — Room Browser core unit tests (plain C, no framework).
 *
 * Build (gcc):  gcc -std=c11 -Wall -Wextra -Wpedantic -Isrc \
 *                   -o /tmp/rb_tests tests/test_core.c src/core (all .c files)
 * Build (CMake): target rb_tests (links rb_core).
 *
 * Every module is covered, including save/load roundtrips through a temp
 * file ("rb-test-tmp.txt" in the cwd, removed afterwards).
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>

#include "core/rb_str.h"
#include "core/rb_url.h"
#include "core/rb_tabs.h"
#include "core/rb_history.h"
#include "core/rb_bookmarks.h"
#include "core/rb_settings.h"
#include "core/rb_paths.h"

#define TMP "rb-test-tmp.txt"

static int g_checks = 0;

#define CHECK(cond)                                                          \
    do {                                                                     \
        g_checks++;                                                          \
        if (!(cond)) {                                                       \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);  \
            exit(1);                                                         \
        }                                                                    \
    } while (0)

#define STREQ(a, b) (strcmp((a), (b)) == 0)

/* --------------------------------- rb_str -------------------------------- */

static void test_rb_str(void)
{
    rb_str s;
    rb_str t;
    size_t i;

    rb_str_init(&s);
    CHECK(rb_str_c(&s) != NULL);
    CHECK(rb_str_c(&s)[0] == '\0');
    CHECK(s.len == 0);

    rb_str_append(&s, "Hello");
    rb_str_append(&s, ", ");
    rb_str_append(&s, "World");
    CHECK(STREQ(rb_str_c(&s), "Hello, World"));
    CHECK(s.len == 12);
    CHECK(s.cap >= s.len);
    CHECK(s.data[12] == '\0');

    rb_str_append(&s, NULL); /* documented no-op */
    CHECK(s.len == 12);

    rb_str_appendf(&s, " %d-%s!", 42, "go");
    CHECK(STREQ(rb_str_c(&s), "Hello, World 42-go!"));
    CHECK(s.len == 19);

    rb_str_clear(&s);
    CHECK(s.len == 0);
    CHECK(rb_str_c(&s)[0] == '\0');

    /* growth: 200 appends of 10 bytes -> 2000 bytes */
    for (i = 0; i < 200; i++) {
        rb_str_append(&s, "0123456789");
    }
    CHECK(s.len == 2000);
    CHECK(s.data != NULL);
    CHECK(s.data[2000] == '\0');
    CHECK(STREQ(rb_str_c(&s) + 1990, "0123456789"));

    rb_str_free(&s);
    CHECK(s.data == NULL);
    CHECK(s.len == 0);
    CHECK(s.cap == 0);
    CHECK(STREQ(rb_str_c(&s), ""));
    rb_str_free(&s); /* double free must stay safe */

    rb_str_init(&t);
    rb_str_appendf(&t, "%s=%d", "x", 5); /* appendf on an empty buffer */
    CHECK(STREQ(rb_str_c(&t), "x=5"));
    rb_str_free(&t);
}

/* --------------------------------- rb_url -------------------------------- */

static void test_rb_url(void)
{
    char *u;

    /* --- rb_url_is_probably_url --- */
    CHECK(rb_url_is_probably_url("example.com") == 1);
    CHECK(rb_url_is_probably_url("sub.example.co.uk/some/path") == 1);
    CHECK(rb_url_is_probably_url("https://example.com") == 1);
    CHECK(rb_url_is_probably_url("http://example.com/path?q=1") == 1);
    CHECK(rb_url_is_probably_url("about:blank") == 1);
    CHECK(rb_url_is_probably_url("file:///tmp/x.html") == 1);
    CHECK(rb_url_is_probably_url("localhost") == 1);
    CHECK(rb_url_is_probably_url("localhost:8080") == 1);
    CHECK(rb_url_is_probably_url("192.168.1.1") == 1);
    CHECK(rb_url_is_probably_url("192.168.1.1:8080") == 1);
    CHECK(rb_url_is_probably_url("::1") == 1);
    CHECK(rb_url_is_probably_url("example.com:8080/path") == 1);
    CHECK(rb_url_is_probably_url("  example.com  ") == 1); /* trimmed */
    CHECK(rb_url_is_probably_url("hello world") == 0);
    CHECK(rb_url_is_probably_url("what is a browser") == 0);
    CHECK(rb_url_is_probably_url("example") == 0); /* no dot, no scheme */
    CHECK(rb_url_is_probably_url("café") == 0);    /* non-ASCII -> search */
    CHECK(rb_url_is_probably_url("1.5") == 0);     /* version-like */
    CHECK(rb_url_is_probably_url("12:30") == 0);   /* time-like */
    CHECK(rb_url_is_probably_url("") == 0);
    CHECK(rb_url_is_probably_url("   ") == 0);
    CHECK(rb_url_is_probably_url(NULL) == 0);

    /* --- rb_url_normalize --- */
    u = rb_url_normalize("example.com");
    CHECK(STREQ(u, "https://example.com/"));
    free(u);
    u = rb_url_normalize("http://example.com");
    CHECK(STREQ(u, "http://example.com/")); /* existing scheme kept */
    free(u);
    u = rb_url_normalize("https://example.com");
    CHECK(STREQ(u, "https://example.com/"));
    free(u);
    u = rb_url_normalize("example.com/path");
    CHECK(STREQ(u, "https://example.com/path"));
    free(u);
    u = rb_url_normalize("example.com?x=1");
    CHECK(STREQ(u, "https://example.com/?x=1"));
    free(u);
    u = rb_url_normalize("example.com#frag");
    CHECK(STREQ(u, "https://example.com/#frag"));
    free(u);
    u = rb_url_normalize("  https://Example.COM  ");
    CHECK(STREQ(u, "https://Example.COM/"));
    free(u);
    u = rb_url_normalize("about:blank");
    CHECK(STREQ(u, "about:blank"));
    free(u);
    u = rb_url_normalize("file:///tmp/x.html");
    CHECK(STREQ(u, "file:///tmp/x.html"));
    free(u);
    u = rb_url_normalize("HTTPS://example.com"); /* scheme is case-insensitive */
    CHECK(STREQ(u, "HTTPS://example.com/"));
    free(u);
    u = rb_url_normalize("");
    CHECK(STREQ(u, ""));
    free(u);
    u = rb_url_normalize(NULL);
    CHECK(STREQ(u, ""));
    free(u);

    /* --- rb_url_build_search: percent-encoding --- */
    u = rb_url_build_search("cats & dogs"); /* space and ampersand */
    CHECK(STREQ(u, "https://duckduckgo.com/?q=cats%20%26%20dogs"));
    free(u);
    u = rb_url_build_search("café"); /* non-ASCII UTF-8 bytes */
    CHECK(STREQ(u, "https://duckduckgo.com/?q=caf%C3%A9"));
    free(u);
    u = rb_url_build_search("100% done"); /* percent itself */
    CHECK(STREQ(u, "https://duckduckgo.com/?q=100%25%20done"));
    free(u);
    u = rb_url_build_search("a.b-c_d~e f"); /* unreserved stay literal */
    CHECK(STREQ(u, "https://duckduckgo.com/?q=a.b-c_d~e%20f"));
    free(u);
    u = rb_url_build_search("");
    CHECK(STREQ(u, "https://duckduckgo.com/?q="));
    free(u);
    u = rb_url_build_search(NULL);
    CHECK(STREQ(u, "https://duckduckgo.com/?q="));
    free(u);
    u = rb_url_build_search("  padded  "); /* surrounding whitespace trimmed */
    CHECK(STREQ(u, "https://duckduckgo.com/?q=padded"));
    free(u);

    /* --- rb_url_decide --- */
    u = rb_url_decide("example.com");
    CHECK(STREQ(u, "https://example.com/"));
    free(u);
    u = rb_url_decide("http://example.com");
    CHECK(STREQ(u, "http://example.com/"));
    free(u);
    u = rb_url_decide("how to boil water");
    CHECK(STREQ(u, "https://duckduckgo.com/?q=how%20to%20boil%20water"));
    free(u);
    u = rb_url_decide("café tools");
    CHECK(STREQ(u, "https://duckduckgo.com/?q=caf%C3%A9%20tools"));
    free(u);
}

/* --------------------------------- rb_tabs ------------------------------- */

static void test_rb_tabs(void)
{
    rb_tabs *t = rb_tabs_new();
    long a, b, c, d;
    rb_tab *tab;
    const rb_tab *at0;

    CHECK(rb_tabs_count(t) == 0);
    CHECK(rb_tabs_at(t, 0) == NULL);
    CHECK(rb_tabs_get(t, 1) == NULL);
    CHECK(rb_tabs_close(t, 1) == 0);

    a = rb_tabs_add(t, "One", "https://one.test/");
    b = rb_tabs_add(t, "Two", "https://two.test/");
    c = rb_tabs_add(t, NULL, NULL);
    CHECK(a == 1); /* ids strictly increasing from 1 */
    CHECK(b == 2);
    CHECK(c == 3);
    CHECK(rb_tabs_count(t) == 3);

    at0 = rb_tabs_at(t, 0); /* insertion order */
    CHECK(at0 != NULL);
    CHECK(at0->id == a);
    CHECK(STREQ(at0->title, "One"));
    CHECK(STREQ(at0->url, "https://one.test/"));
    CHECK(rb_tabs_at(t, 2) != NULL);
    CHECK(STREQ(rb_tabs_at(t, 2)->title, "")); /* NULL args stored as "" */
    CHECK(STREQ(rb_tabs_at(t, 2)->url, ""));
    CHECK(rb_tabs_at(t, 3) == NULL);
    CHECK(rb_tabs_at(t, -1) == NULL);

    tab = rb_tabs_get(t, b); /* mutable slot owned by the module */
    CHECK(tab != NULL);
    CHECK(tab->id == b);
    snprintf(tab->title, strlen(tab->title) + 1, "%s", "T2");
    CHECK(STREQ(rb_tabs_get(t, b)->title, "T2"));
    CHECK(rb_tabs_get(t, 99) == NULL);

    CHECK(rb_tabs_close(t, b) == 1);
    CHECK(rb_tabs_count(t) == 2);
    CHECK(rb_tabs_get(t, b) == NULL); /* gone after close */
    CHECK(rb_tabs_close(t, b) == 0);  /* second close: not found */
    CHECK(rb_tabs_at(t, 0)->id == a); /* order compacted */
    CHECK(rb_tabs_at(t, 1)->id == c);

    d = rb_tabs_add(t, "Four", "https://four.test/"); /* ids never reused */
    CHECK(d == 4);
    CHECK(rb_tabs_count(t) == 3);
    CHECK(rb_tabs_at(t, 2)->id == d);

    rb_tabs_free(t);
    rb_tabs_free(NULL); /* must not crash */
}

/* ------------------------------- rb_history ------------------------------ */

static void test_rb_history(void)
{
    rb_history *h = rb_history_new();
    rb_history *h2;
    const rb_hist_entry *rec;
    int n = 0;
    long long ts0;

    CHECK(rb_history_count(h) == 0);
    CHECK(rb_history_recent(h, 5, &n) == NULL);
    CHECK(n == 0);

    rb_history_append(h, "https://a.test/", "A");
    rb_history_append(h, "https://b.test/", "B");
    CHECK(rb_history_count(h) == 2);

    /* consecutive same-URL: entry replaced, count unchanged */
    rb_history_append(h, "https://b.test/", "B revisited");
    CHECK(rb_history_count(h) == 2);
    rec = rb_history_recent(h, 10, &n);
    CHECK(rec != NULL);
    CHECK(n == 2);
    CHECK(STREQ(rec[0].url, "https://b.test/"));
    CHECK(STREQ(rec[0].title, "B revisited")); /* title was replaced */
    CHECK(STREQ(rec[1].url, "https://a.test/"));
    CHECK(rec[0].visited_at >= rec[1].visited_at);

    /* a different URL in between breaks the consecutive dedupe */
    rb_history_append(h, "https://a.test/", "A again");
    CHECK(rb_history_count(h) == 3);
    rec = rb_history_recent(h, 2, &n);
    CHECK(n == 2); /* most-recent first */
    CHECK(STREQ(rec[0].url, "https://a.test/"));
    CHECK(STREQ(rec[1].url, "https://b.test/"));
    rec = rb_history_recent(h, 1, &n);
    CHECK(n == 1);
    CHECK(STREQ(rec[0].url, "https://a.test/"));
    CHECK(rb_history_recent(h, 0, &n) == NULL);
    CHECK(n == 0);

    /* JSON-lines roundtrip including quotes, backslashes and newlines */
    rb_history_append(h, "https://quote.test/?x=\"y\"&z=\\",
                      "He said \"hi\" \\ line\nbreak\ttab");
    rec = rb_history_recent(h, 1, &n);
    CHECK(rec != NULL);
    ts0 = rec[0].visited_at;
    CHECK(rb_history_save(h, TMP) == 0);

    h2 = rb_history_new();
    CHECK(rb_history_load(h2, TMP) == 0);
    CHECK(rb_history_count(h2) == rb_history_count(h));
    rec = rb_history_recent(h2, 10, &n);
    CHECK(rec != NULL);
    CHECK(n == 4);
    CHECK(STREQ(rec[0].url, "https://quote.test/?x=\"y\"&z=\\"));
    CHECK(STREQ(rec[0].title, "He said \"hi\" \\ line\nbreak\ttab"));
    CHECK(rec[0].visited_at == ts0); /* timestamps survive the roundtrip */
    CHECK(STREQ(rec[2].url, "https://b.test/"));
    CHECK(STREQ(rec[2].title, "B revisited"));
    CHECK(STREQ(rec[3].url, "https://a.test/")); /* oldest is last */
    CHECK(STREQ(rec[3].title, "A"));
    rb_history_free(h2);
    remove(TMP);

    /* missing file is fine, existing state untouched */
    CHECK(rb_history_load(h, "rb-test-does-not-exist.txt") == 0);
    CHECK(rb_history_count(h) == 4);

    /* malformed lines are skipped, valid ones survive */
    {
        FILE *f = fopen(TMP, "wb");
        CHECK(f != NULL);
        if (f != NULL) {
            fprintf(f, "not json at all\n");
            fprintf(f, "{\"url\":\"https://ok.test/\",\"title\":\"OK\",\"visited_at\":42}\n");
            fprintf(f, "{\"url\":\n");
            fprintf(f, "{}\n");
            fprintf(f, "{\"title\":\"no url\",\"visited_at\":7}\n");
            fprintf(f, "\n");
            fclose(f);
        }
        h2 = rb_history_new();
        CHECK(rb_history_load(h2, TMP) == 0);
        CHECK(rb_history_count(h2) == 1);
        rec = rb_history_recent(h2, 10, &n);
        CHECK(rec != NULL);
        CHECK(n == 1);
        CHECK(STREQ(rec[0].url, "https://ok.test/"));
        CHECK(STREQ(rec[0].title, "OK"));
        CHECK(rec[0].visited_at == 42);
        rb_history_free(h2);
        remove(TMP);
    }

    rb_history_free(h);
    rb_history_free(NULL); /* must not crash */
}

/* ------------------------------ rb_bookmarks ----------------------------- */

static void test_rb_bookmarks(void)
{
    rb_bookmarks *b = rb_bookmarks_new();
    rb_bookmarks *b2;

    CHECK(rb_bookmarks_count(b) == 0);
    CHECK(rb_bookmarks_add(b, "https://a.test/", "A") == 1);
    CHECK(rb_bookmarks_add(b, "https://a.test/", "A again") == 0); /* dup */
    CHECK(rb_bookmarks_add(b, "https://b.test/", "B \"quoted\"") == 1);
    CHECK(rb_bookmarks_add(b, NULL, "nope") == 0);
    CHECK(rb_bookmarks_add(b, "", "nope") == 0);
    CHECK(rb_bookmarks_count(b) == 2);

    CHECK(rb_bookmarks_contains(b, "https://a.test/") == 1);
    CHECK(rb_bookmarks_contains(b, "https://zz.test/") == 0);
    CHECK(rb_bookmarks_contains(b, NULL) == 0);

    CHECK(rb_bookmarks_url_at(b, 0) != NULL);
    CHECK(STREQ(rb_bookmarks_url_at(b, 0), "https://a.test/"));
    CHECK(rb_bookmarks_title_at(b, 1) != NULL);
    CHECK(STREQ(rb_bookmarks_title_at(b, 1), "B \"quoted\""));
    CHECK(rb_bookmarks_url_at(b, 2) == NULL);
    CHECK(rb_bookmarks_url_at(b, -1) == NULL);
    CHECK(rb_bookmarks_title_at(b, 99) == NULL);

    CHECK(rb_bookmarks_save(b, TMP) == 0);
    b2 = rb_bookmarks_new();
    CHECK(rb_bookmarks_load(b2, TMP) == 0);
    CHECK(rb_bookmarks_count(b2) == 2);
    CHECK(STREQ(rb_bookmarks_url_at(b2, 0), "https://a.test/"));
    CHECK(STREQ(rb_bookmarks_title_at(b2, 1), "B \"quoted\""));
    CHECK(rb_bookmarks_load(b2, TMP) == 0); /* reload stays de-duplicated */
    CHECK(rb_bookmarks_count(b2) == 2);
    rb_bookmarks_free(b2);
    remove(TMP);

    /* missing file is fine */
    CHECK(rb_bookmarks_load(b, "rb-test-does-not-exist.txt") == 0);
    CHECK(rb_bookmarks_count(b) == 2);

    /* malformed lines skipped; duplicate URLs collapse to the first */
    {
        FILE *f = fopen(TMP, "wb");
        CHECK(f != NULL);
        if (f != NULL) {
            fprintf(f, "garbage\n");
            fprintf(f, "{\"url\":\"https://ok.test/\",\"title\":\"OK\"}\n");
            fprintf(f, "{\"url\":\"https://dup.test/\",\"title\":\"1\"}\n");
            fprintf(f, "{\"url\":\"https://dup.test/\",\"title\":\"2\"}\n");
            fprintf(f, "{\"title\":\"missing url\"}\n");
            fclose(f);
        }
        b2 = rb_bookmarks_new();
        CHECK(rb_bookmarks_load(b2, TMP) == 0);
        CHECK(rb_bookmarks_count(b2) == 2);
        CHECK(STREQ(rb_bookmarks_title_at(b2, 1), "1"));
        rb_bookmarks_free(b2);
        remove(TMP);
    }

    CHECK(rb_bookmarks_remove(b, "https://a.test/") == 1);
    CHECK(rb_bookmarks_remove(b, "https://a.test/") == 0);
    CHECK(rb_bookmarks_count(b) == 1);
    CHECK(rb_bookmarks_contains(b, "https://a.test/") == 0);
    CHECK(STREQ(rb_bookmarks_url_at(b, 0), "https://b.test/"));
    CHECK(rb_bookmarks_remove(b, NULL) == 0);

    rb_bookmarks_free(b);
    rb_bookmarks_free(NULL); /* must not crash */
}

/* ------------------------------ rb_settings ------------------------------ */

static void test_rb_settings(void)
{
    rb_settings *s = rb_settings_new();
    rb_settings *s2;

    /* project-wide policy: javascript NEVER defaults to 0 */
    CHECK(rb_settings_get_int(s, "javascript", 0) == 1);
    CHECK(STREQ(rb_settings_get(s, "home", "?"), "https://duckduckgo.com"));
    CHECK(STREQ(rb_settings_get(s, "search_engine", "?"), "duckduckgo"));

    CHECK(STREQ(rb_settings_get(s, "missing", "fallback"), "fallback"));
    CHECK(rb_settings_get(s, "missing", NULL) == NULL);
    CHECK(rb_settings_get_int(s, "missing", 7) == 7);

    rb_settings_set(s, "home", "https://example.com/");
    rb_settings_set_int(s, "zoom_pct", 150);
    rb_settings_set(s, "ua", "Mozilla/5.0 (X11; =; ok)");
    CHECK(STREQ(rb_settings_get(s, "home", "?"), "https://example.com/"));
    CHECK(rb_settings_get_int(s, "zoom_pct", 100) == 150);
    CHECK(rb_settings_get_int(s, "home", 3) == 3); /* not an int */

    CHECK(rb_settings_save(s, TMP) == 0);
    s2 = rb_settings_new();
    CHECK(rb_settings_get_int(s2, "javascript", 0) == 1); /* fresh defaults */
    CHECK(rb_settings_load(s2, TMP) == 0);
    CHECK(STREQ(rb_settings_get(s2, "home", "?"), "https://example.com/"));
    CHECK(rb_settings_get_int(s2, "zoom_pct", 100) == 150);
    CHECK(STREQ(rb_settings_get(s2, "search_engine", "?"), "duckduckgo"));
    CHECK(STREQ(rb_settings_get(s2, "ua", "?"), "Mozilla/5.0 (X11; =; ok)"));
    rb_settings_set_int(s2, "javascript", 0); /* user may still opt out */
    CHECK(rb_settings_get_int(s2, "javascript", 1) == 0);
    rb_settings_free(s2);
    remove(TMP);

    /* missing file keeps the defaults */
    s2 = rb_settings_new();
    CHECK(rb_settings_load(s2, "rb-test-does-not-exist.txt") == 0);
    CHECK(rb_settings_get_int(s2, "javascript", 0) == 1);
    CHECK(STREQ(rb_settings_get(s2, "home", "?"), "https://duckduckgo.com"));
    rb_settings_free(s2);

    /* malformed lines skipped; '=' inside the value survives */
    {
        FILE *f = fopen(TMP, "wb");
        CHECK(f != NULL);
        if (f != NULL) {
            fprintf(f, "noequals\n");
            fprintf(f, "  spaced  =  v  \n");
            fprintf(f, "=orphan\n");
            fprintf(f, "a=b=c\n");
            fprintf(f, "\n");
            fclose(f);
        }
        s2 = rb_settings_new();
        CHECK(rb_settings_load(s2, TMP) == 0);
        CHECK(STREQ(rb_settings_get(s2, "spaced", "?"), "  v  ")); /* key trimmed, value verbatim */
        CHECK(STREQ(rb_settings_get(s2, "a", "?"), "b=c"));
        CHECK(STREQ(rb_settings_get(s2, "noequals", "gone"), "gone"));
        CHECK(rb_settings_get(s2, "orphan", "gone") != NULL);
        rb_settings_free(s2);
        remove(TMP);
    }

    rb_settings_free(s);
    rb_settings_free(NULL); /* must not crash */
}

/* -------------------------------- rb_paths ------------------------------- */

static void test_rb_paths(void)
{
    char *dir = rb_paths_data_dir();
    const char *home;
    struct stat st;

    CHECK(dir != NULL);
    if (dir != NULL) {
        CHECK(stat(dir, &st) == 0);
        CHECK(S_ISDIR(st.st_mode));
        home = getenv("HOME");
        CHECK(home != NULL);
        if (home != NULL) {
            CHECK(strncmp(dir, home, strlen(home)) == 0);
            CHECK(strstr(dir, "RoomBrowser") != NULL);
        }
        rb_paths_free(dir);
    }
    rb_paths_free(NULL); /* must not crash */
}

int main(void)
{
    test_rb_str();
    test_rb_url();
    test_rb_tabs();
    test_rb_history();
    test_rb_bookmarks();
    test_rb_settings();
    test_rb_paths();
    remove(TMP);
    printf("core checks: %d\n", g_checks);
    printf("ALL CORE TESTS PASSED\n");
    return 0;
}
