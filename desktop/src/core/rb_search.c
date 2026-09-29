/*
 * rb_search.c — search-engine registry for Room Browser core.
 * Pure C11; only the C standard library is used.
 *
 * The table below is a verbatim port of the Android edition's
 * SearchEngines.all (ids, labels, templates), so the two editions build
 * identical search URLs for the same engine + query.
 */

#include "rb_search.h"

#include "rb_str.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const rb_search_engine RB_SEARCH_ENGINES[] = {
    { "google", "Google",
      "https://www.google.com/search?q={query}",
      "https://www.google.com/complete/search?client=firefox&q={query}" },
    { "bing", "Bing",
      "https://www.bing.com/search?q={query}",
      NULL },
    { "duckduckgo", "DuckDuckGo",
      "https://duckduckgo.com/?q={query}",
      "https://duckduckgo.com/ac/?q={query}&type=list" },
    { "brave", "Brave Search",
      "https://search.brave.com/search?q={query}",
      NULL },
    { "startpage", "Startpage",
      "https://www.startpage.com/sp/search?query={query}",
      NULL },
    { "ecosia", "Ecosia",
      "https://www.ecosia.org/search?q={query}",
      NULL }
};

#define RB_SEARCH_N \
    ((int)(sizeof(RB_SEARCH_ENGINES) / sizeof(RB_SEARCH_ENGINES[0])))

/* malloc'd empty string, for the paths that would otherwise hand back an
 * unallocated rb_str buffer as NULL. */
static char *rb_search_empty(void)
{
    char *p = (char *)malloc(1);
    if (p == NULL) {
        fprintf(stderr, "rb_search: out of memory\n");
        exit(1);
    }
    p[0] = '\0';
    return p;
}

/* The engine every unknown/NULL id falls back to (index into the table). */
#define RB_SEARCH_DEFAULT_INDEX 2 /* duckduckgo */

int rb_search_count(void)
{
    return RB_SEARCH_N;
}

const rb_search_engine *rb_search_at(int index)
{
    if (index < 0 || index >= RB_SEARCH_N) {
        return NULL;
    }
    return &RB_SEARCH_ENGINES[index];
}

static int rb_search_ci_eq(const char *a, const char *b)
{
    if (a == NULL || b == NULL) {
        return 0;
    }
    while (*a != '\0' && *b != '\0') {
        if (tolower((unsigned char)*a) != tolower((unsigned char)*b)) {
            return 0;
        }
        a++;
        b++;
    }
    return *a == *b;
}

const rb_search_engine *rb_search_by_id(const char *id)
{
    int i;

    if (id == NULL || id[0] == '\0') {
        return NULL;
    }
    for (i = 0; i < RB_SEARCH_N; i++) {
        if (rb_search_ci_eq(RB_SEARCH_ENGINES[i].id, id)) {
            return &RB_SEARCH_ENGINES[i];
        }
    }
    return NULL;
}

const rb_search_engine *rb_search_default(void)
{
    return &RB_SEARCH_ENGINES[RB_SEARCH_DEFAULT_INDEX];
}

const rb_search_engine *rb_search_resolve(const char *id)
{
    const rb_search_engine *e = rb_search_by_id(id);
    return (e != NULL) ? e : rb_search_default();
}

char *rb_search_encode(const char *query)
{
    const char *a;
    const char *b;
    rb_str out;

    if (query == NULL) {
        query = "";
    }
    a = query;
    b = query + strlen(query);
    while (a < b && (*a == ' ' || *a == '\t' || *a == '\r' || *a == '\n')) {
        a++;
    }
    while (b > a && (b[-1] == ' ' || b[-1] == '\t' || b[-1] == '\r' ||
                     b[-1] == '\n')) {
        b--;
    }

    rb_str_init(&out);
    while (a < b) {
        unsigned char c = (unsigned char)*a++;
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
            (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' ||
            c == '~') {
            char one[2];
            one[0] = (char)c;
            one[1] = '\0';
            rb_str_append(&out, one);
        } else {
            rb_str_appendf(&out, "%%%02X", (unsigned)c);
        }
    }
    /* An empty query leaves the buffer unallocated, but the contract is that
     * this returns a string, never NULL — a caller doing strcat() or
     * strlen() on the result must not have to guard it. */
    if (out.data == NULL) {
        return rb_search_empty();
    }
    return out.data;
}

char *rb_search_expand(const char *tmpl, const char *query)
{
    static const char PLACEHOLDER[] = "{query}";
    const size_t plen = sizeof(PLACEHOLDER) - 1;
    const char *p;
    char *encoded;
    rb_str out;

    if (tmpl == NULL) {
        tmpl = "";
    }
    rb_str_init(&out);

    p = tmpl;
    encoded = rb_search_encode(query); /* "" for a NULL/empty query */
    while ((p = strstr(tmpl, PLACEHOLDER)) != NULL) {
        rb_str_appendf(&out, "%.*s", (int)(p - tmpl), tmpl);
        rb_str_append(&out, encoded);
        tmpl = p + plen;
    }
    rb_str_append(&out, tmpl);
    free(encoded);
    if (out.data == NULL) {
        return rb_search_empty();
    }
    return out.data;
}

char *rb_search_url(const char *engine_id, const char *query)
{
    const rb_search_engine *e = rb_search_resolve(engine_id);
    return rb_search_expand(e->search_template, query);
}

char *rb_search_suggest_url(const char *engine_id, const char *query)
{
    const rb_search_engine *e = rb_search_resolve(engine_id);

    if (e->suggest_template == NULL || query == NULL || query[0] == '\0') {
        return NULL;
    }
    return rb_search_expand(e->suggest_template, query);
}
