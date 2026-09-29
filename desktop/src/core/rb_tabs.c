/*
 * rb_tabs.c — tab table for Room Browser core.
 * Pure C11; only the C standard library is used.
 *
 * INVARIANT: `items[0 .. open_count-1]` are the OPEN tabs in display order
 * (pinned first, then position ascending, ties by id); `items[open_count ..]`
 * are the closed ones in no particular order.  Every mutation below restores
 * that invariant, which is what makes rb_tabs_at() an O(1) array read
 * instead of a sort on every call.
 */

#include "rb_tabs.h"

#include "rb_json.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define RB_TABS_LINE 16384

struct rb_tabs {
    rb_tab *items;
    int count;      /* open + closed */
    int open_count; /* size of the open run at the front */
    int cap;
    long next_id;
};

/* ------------------------------- helpers -------------------------------- */

static char *rb_tabs_strdup(const char *s)
{
    return rb_json_strdup(s);
}

static void rb_tab_release(rb_tab *tab)
{
    free(tab->title);
    free(tab->url);
    free(tab->group_name);
    memset(tab, 0, sizeof(*tab));
}

static void rb_tabs_grow(rb_tabs *t)
{
    int ncap;
    rb_tab *grown;

    if (t->count < t->cap) {
        return;
    }
    ncap = (t->cap > 0) ? t->cap * 2 : 8;
    grown = (rb_tab *)realloc(t->items, (size_t)ncap * sizeof(rb_tab));
    if (grown == NULL) {
        fprintf(stderr, "rb_tabs: out of memory\n");
        exit(1);
    }
    t->items = grown;
    t->cap = ncap;
}

/* 1 when `a` sorts before `b`: pinned first, then position, then id — the id
 * only breaks ties, so a duplicated position is still deterministic. */
static int rb_tab_before(const rb_tab *a, const rb_tab *b)
{
    if (a->is_pinned != b->is_pinned) {
        return a->is_pinned > b->is_pinned;
    }
    if (a->position != b->position) {
        return a->position < b->position;
    }
    return a->id < b->id;
}

/* Stable insertion sort of the open run.  Tab strips are short and the list
 * is nearly sorted after a single move, which is the case this is built for. */
static void rb_tabs_sort_open(rb_tabs *t)
{
    int i;

    for (i = 1; i < t->open_count; i++) {
        rb_tab key = t->items[i];
        int j = i - 1;

        while (j >= 0 && rb_tab_before(&key, &t->items[j])) {
            t->items[j + 1] = t->items[j];
            j--;
        }
        t->items[j + 1] = key;
    }
}

static int rb_tabs_index_of(const rb_tabs *t, long id)
{
    int i;

    if (t == NULL) {
        return -1;
    }
    for (i = 0; i < t->count; i++) {
        if (t->items[i].id == id) {
            return i;
        }
    }
    return -1;
}

/* Highest position in use among the OPEN tabs, or -1 when there are none.
 * Closed tabs are excluded on purpose: BrowserRepository.newTab computes its
 * position from openTabs(), so a closed strip leaves the numbers reusable. */
static int rb_tabs_max_position(const rb_tabs *t)
{
    int i;
    int best = -1;

    for (i = 0; i < t->open_count; i++) {
        if (t->items[i].position > best) {
            best = t->items[i].position;
        }
    }
    return best;
}

/* Moves the row at `i` out of the open run and into the closed tail,
 * preserving the order of everything that stays open. */
static void rb_tabs_retire(rb_tabs *t, int i)
{
    rb_tab moved = t->items[i];

    memmove(&t->items[i], &t->items[i + 1],
            (size_t)(t->count - i - 1) * sizeof(rb_tab));
    t->items[t->count - 1] = moved;
    t->open_count--;
}

/* Moves the closed row at `i` (which must be past the open run) back into the
 * open run and re-sorts. */
static void rb_tabs_reinstate(rb_tabs *t, int i)
{
    rb_tab moved = t->items[i];

    memmove(&t->items[t->open_count + 1], &t->items[t->open_count],
            (size_t)(i - t->open_count) * sizeof(rb_tab));
    t->items[t->open_count] = moved;
    t->open_count++;
    rb_tabs_sort_open(t);
}

static void rb_tab_init(rb_tab *tab, long id, const char *title, const char *url,
                        int position, long long now_ms)
{
    memset(tab, 0, sizeof(*tab));
    tab->id = id;
    tab->title = rb_tabs_strdup(title);
    tab->url = rb_tabs_strdup(url);
    tab->position = position;
    tab->is_private = 0;
    tab->is_pinned = 0;
    tab->group_name = rb_tabs_strdup("");
    tab->created_at = now_ms;
    tab->last_viewed_at = now_ms;
    tab->closed_at = 0;
}

/* ------------------------------- lifecycle ------------------------------- */

rb_tabs *rb_tabs_new(void)
{
    rb_tabs *t = (rb_tabs *)calloc(1, sizeof(*t));

    if (t == NULL) {
        fprintf(stderr, "rb_tabs: out of memory\n");
        exit(1);
    }
    t->next_id = 1;
    return t;
}

void rb_tabs_free(rb_tabs *t)
{
    int i;

    if (t == NULL) {
        return;
    }
    for (i = 0; i < t->count; i++) {
        rb_tab_release(&t->items[i]);
    }
    free(t->items);
    free(t);
}

long rb_tabs_add(rb_tabs *t, const char *title, const char *url, long long now_ms)
{
    rb_tab *slot;

    if (t == NULL) {
        return 0;
    }
    rb_tabs_grow(t);
    /* Open tabs occupy the front, so the new row goes at open_count and the
     * closed tail shifts up by one. */
    if (t->count > t->open_count) {
        memmove(&t->items[t->open_count + 1], &t->items[t->open_count],
                (size_t)(t->count - t->open_count) * sizeof(rb_tab));
    }
    slot = &t->items[t->open_count];
    rb_tab_init(slot, t->next_id++, title, url, rb_tabs_max_position(t) + 1,
                now_ms);
    t->count++;
    t->open_count++;
    return slot->id;
}

int rb_tabs_count(const rb_tabs *t)
{
    return (t != NULL) ? t->open_count : 0;
}

const rb_tab *rb_tabs_at(const rb_tabs *t, int index)
{
    if (t == NULL || index < 0 || index >= t->open_count) {
        return NULL;
    }
    return &t->items[index];
}

rb_tab *rb_tabs_get(rb_tabs *t, long id)
{
    int i = rb_tabs_index_of(t, id);

    return (i >= 0) ? &t->items[i] : NULL;
}

int rb_tabs_close(rb_tabs *t, long id, long long now_ms)
{
    int i = rb_tabs_index_of(t, id);

    if (i < 0 || i >= t->open_count) {
        return 0; /* unknown, or already closed */
    }
    t->items[i].closed_at = now_ms;
    rb_tabs_retire(t, i);
    return 1;
}

int rb_tabs_close_others(rb_tabs *t, long id, int mode, long long now_ms)
{
    int active;
    int active_pos;
    int closed = 0;
    int i;

    if (t == NULL) {
        return 0;
    }
    active = rb_tabs_index_of(t, id);
    if (active < 0 || active >= t->open_count) {
        return 0; /* the tab to keep has to be open */
    }
    active_pos = t->items[active].position;

    /* Walk downwards: retiring a row shifts the ones above it down, so an
     * ascending walk would skip the row that slid into the current index. */
    for (i = t->open_count - 1; i >= 0; i--) {
        int close_it;

        if (t->items[i].id == id) {
            continue;
        }
        switch (mode) {
        case RB_TABS_KEEP_LEFT:
            close_it = t->items[i].position < active_pos;
            break;
        case RB_TABS_KEEP_RIGHT:
            close_it = t->items[i].position > active_pos;
            break;
        default:
            close_it = 1;
            break;
        }
        if (!close_it) {
            continue;
        }
        t->items[i].closed_at = now_ms;
        rb_tabs_retire(t, i);
        closed++;
    }
    return closed;
}

int rb_tabs_reopen(rb_tabs *t, long id)
{
    int i = rb_tabs_index_of(t, id);

    if (i < 0 || i < t->open_count) {
        return 0; /* unknown, or already open */
    }
    t->items[i].closed_at = 0;
    rb_tabs_reinstate(t, i);
    return 1;
}

int rb_tabs_touch(rb_tabs *t, long id, long long now_ms)
{
    int i = rb_tabs_index_of(t, id);

    if (i < 0) {
        return 0;
    }
    t->items[i].last_viewed_at = now_ms;
    return 1;
}

/* ----------------------------- closed tabs ------------------------------ */

int rb_tabs_closed_count(const rb_tabs *t)
{
    return (t != NULL) ? (t->count - t->open_count) : 0;
}

/* 1 when `cand` is already in the list built so far.  Compared by address:
 * the pointers alias rows of this table, so identity is the right test and
 * two closed tabs that happen to match on every field stay distinct. */
static int rb_tab_ptr_listed(const rb_tab *const *out, int n, const rb_tab *cand)
{
    int k;

    for (k = 0; k < n; k++) {
        if (out[k] == cand) {
            return 1;
        }
    }
    return 0;
}

int rb_tabs_recently_closed(const rb_tabs *t, const rb_tab **out, int max)
{
    int filled = 0;

    if (t == NULL || out == NULL || max <= 0) {
        return 0;
    }
    while (filled < max) {
        /* Selection scan rather than a sort: `max` is 10 and the closed list
         * is the only thing being ordered. */
        int best = -1;
        int i;

        for (i = t->open_count; i < t->count; i++) {
            if (rb_tab_ptr_listed(out, filled, &t->items[i])) {
                continue;
            }
            if (best < 0 ||
                t->items[i].closed_at > t->items[best].closed_at ||
                (t->items[i].closed_at == t->items[best].closed_at &&
                 t->items[i].id > t->items[best].id)) {
                best = i;
            }
        }
        if (best < 0) {
            break;
        }
        out[filled++] = &t->items[best];
    }
    return filled;
}

int rb_tabs_purge_closed_before(rb_tabs *t, long long cutoff)
{
    int removed = 0;
    int i = t->open_count;

    if (t == NULL) {
        return 0;
    }
    while (i < t->count) {
        if (t->items[i].closed_at >= cutoff) {
            i++;
            continue;
        }
        rb_tab_release(&t->items[i]);
        memmove(&t->items[i], &t->items[i + 1],
                (size_t)(t->count - i - 1) * sizeof(rb_tab));
        t->count--;
        removed++;
    }
    return removed;
}

int rb_tabs_purge_closed(rb_tabs *t, long long now_ms)
{
    return rb_tabs_purge_closed_before(
        t, now_ms - (long long)RB_TABS_CLOSED_RETENTION_DAYS * 86400000LL);
}

int rb_tabs_forget_closed(rb_tabs *t)
{
    int removed;
    int i;

    if (t == NULL) {
        return 0;
    }
    removed = t->count - t->open_count;
    for (i = t->open_count; i < t->count; i++) {
        rb_tab_release(&t->items[i]);
    }
    t->count = t->open_count;
    return removed;
}

/* ------------------------------ arrangement ----------------------------- */

int rb_tabs_move(rb_tabs *t, long id, int position)
{
    int i = rb_tabs_index_of(t, id);

    if (i < 0 || i >= t->open_count) {
        return 0;
    }
    t->items[i].position = position;
    rb_tabs_sort_open(t);
    return 1;
}

int rb_tabs_pin(rb_tabs *t, long id, int pinned)
{
    int i = rb_tabs_index_of(t, id);

    if (i < 0 || i >= t->open_count) {
        return 0;
    }
    t->items[i].is_pinned = (pinned != 0);
    rb_tabs_sort_open(t);
    return 1;
}

int rb_tabs_group(rb_tabs *t, long id, const char *group)
{
    int i = rb_tabs_index_of(t, id);

    if (i < 0) {
        return 0;
    }
    free(t->items[i].group_name);
    t->items[i].group_name = rb_tabs_strdup(group);
    return 1;
}

int rb_tabs_set_private(rb_tabs *t, long id, int is_private)
{
    int i = rb_tabs_index_of(t, id);

    if (i < 0) {
        return 0;
    }
    t->items[i].is_private = (is_private != 0);
    return 1;
}

int rb_tabs_private_count(const rb_tabs *t)
{
    int i;
    int n = 0;

    if (t == NULL) {
        return 0;
    }
    for (i = 0; i < t->open_count; i++) {
        if (t->items[i].is_private) {
            n++;
        }
    }
    return n;
}

/* ------------------------------ persistence ----------------------------- */

static int rb_tabs_write_str(FILE *f, const char *value)
{
    char *escaped = rb_json_escape(value);
    int rc = 0;

    if (fputc('"', f) == EOF || fputs(escaped, f) == EOF || fputc('"', f) == EOF) {
        rc = -1;
    }
    free(escaped);
    return rc;
}

static int rb_tabs_write_row(FILE *f, const rb_tab *tab)
{
    int rc;

    if (fprintf(f, "{\"id\":%ld,\"position\":%d,\"is_pinned\":%d,"
                   "\"is_private\":%d,\"created_at\":%lld,"
                   "\"last_viewed_at\":%lld,\"closed_at\":%lld,\"title\":",
                tab->id, tab->position, tab->is_pinned, tab->is_private,
                tab->created_at, tab->last_viewed_at, tab->closed_at) < 0) {
        return -1;
    }
    rc = rb_tabs_write_str(f, tab->title);
    if (rc == 0 && fputs(",\"url\":", f) == EOF) {
        rc = -1;
    }
    if (rc == 0) {
        rc = rb_tabs_write_str(f, tab->url);
    }
    if (rc == 0 && fputs(",\"group_name\":", f) == EOF) {
        rc = -1;
    }
    if (rc == 0) {
        rc = rb_tabs_write_str(f, tab->group_name);
    }
    if (rc == 0 && fputs("}\n", f) == EOF) {
        rc = -1;
    }
    return rc;
}

int rb_tabs_save(const rb_tabs *t, const char *path)
{
    FILE *f;
    int i;
    int rc = 0;

    if (t == NULL || path == NULL) {
        return -1;
    }
    f = fopen(path, "wb");
    if (f == NULL) {
        return -1;
    }
    for (i = 0; i < t->count && rc == 0; i++) {
        if (t->items[i].is_private) {
            continue; /* never persisted; see rb_tabs.h */
        }
        rc = rb_tabs_write_row(f, &t->items[i]);
    }
    if (rc == 0 && ferror(f)) {
        rc = -1;
    }
    if (fclose(f) != 0) {
        rc = -1;
    }
    return rc;
}

static char *rb_tabs_field_str(const char *line, const char *key)
{
    size_t pos;
    char *out = NULL;

    if (!rb_json_find_key(line, key, &pos)) {
        return NULL;
    }
    if (line[pos] != '"' || !rb_json_parse_string(line, &pos, &out)) {
        return NULL;
    }
    return out;
}

static long long rb_tabs_field_num(const char *line, const char *key,
                                   long long fallback)
{
    size_t pos;
    long long v = fallback;

    if (!rb_json_find_key(line, key, &pos)) {
        return fallback;
    }
    if (!rb_json_parse_number(line, &pos, &v)) {
        return fallback;
    }
    return v;
}

int rb_tabs_load(rb_tabs *t, const char *path)
{
    char buf[RB_TABS_LINE];
    FILE *f;
    int rc = 0;

    if (t == NULL || path == NULL) {
        return -1;
    }
    for (; t->count > 0;) {
        rb_tab_release(&t->items[0]);
        memmove(&t->items[0], &t->items[1],
                (size_t)(t->count - 1) * sizeof(rb_tab));
        t->count--;
    }
    t->open_count = 0;
    t->next_id = 1;

    f = fopen(path, "rb");
    if (f == NULL) {
        return 0; /* missing file is fine, and leaves the table empty */
    }
    while (fgets(buf, (int)sizeof(buf), f) != NULL) {
        size_t len = strlen(buf);
        char *title;
        char *url;
        rb_tab row;

        if (len > 0 && buf[len - 1] != '\n' && !feof(f)) {
            int ch; /* line longer than the buffer: skip it entirely */
            while ((ch = fgetc(f)) != EOF && ch != '\n') {
                /* drain */
            }
            continue;
        }
        while (len > 0 && (buf[len - 1] == '\n' || buf[len - 1] == '\r')) {
            buf[--len] = '\0';
        }
        if (len == 0) {
            continue;
        }
        title = rb_tabs_field_str(buf, "title");
        url = rb_tabs_field_str(buf, "url");
        if (title == NULL && url == NULL) {
            continue; /* not one of our rows */
        }
        rb_tab_init(&row, 0, title, url, 0, 0);
        free(title);
        free(url);
        row.id = (long)rb_tabs_field_num(buf, "id", 0);
        row.position = (int)rb_tabs_field_num(buf, "position", 0);
        row.is_pinned = (int)rb_tabs_field_num(buf, "is_pinned", 0);
        /* A restored private tab would be a private tab the user cannot see
         * the provenance of; save() never writes one, and a hand-edited file
         * does not get to introduce one either. */
        row.is_private = 0;
        row.created_at = rb_tabs_field_num(buf, "created_at", 0);
        row.last_viewed_at = rb_tabs_field_num(buf, "last_viewed_at", 0);
        row.closed_at = rb_tabs_field_num(buf, "closed_at", 0);
        {
            char *group = rb_tabs_field_str(buf, "group_name");
            free(row.group_name);
            row.group_name = rb_tabs_strdup(group);
            free(group);
        }

        rb_tabs_grow(t);
        if (row.id <= 0) {
            row.id = t->next_id;
        }
        if (row.id >= t->next_id) {
            t->next_id = row.id + 1;
        }
        t->items[t->count++] = row;
        if (row.closed_at == 0) {
            t->open_count++;
        }
    }
    if (ferror(f)) {
        rc = -1;
    }
    fclose(f);

    /* The file's row order is not the display order: rows may have been
     * written by a build with different positions, or edited by hand.  The
     * open run is rebuilt from the pinned/position fields. */
    {
        int i;
        int open = 0;

        for (i = 0; i < t->count; i++) {
            if (t->items[i].closed_at == 0) {
                rb_tab moved = t->items[i];
                memmove(&t->items[open + 1], &t->items[open],
                        (size_t)(i - open) * sizeof(rb_tab));
                t->items[open] = moved;
                open++;
            }
        }
        t->open_count = open;
        rb_tabs_sort_open(t);
    }
    return rc;
}
