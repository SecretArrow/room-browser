/*
 * rb_tabs.c — tab table for Room Browser core.
 * Pure C11; only the C standard library is used.
 */

#include "rb_tabs.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static char *rb_tabs_strdup(const char *s)
{
    size_t n;
    char *p;

    if (s == NULL) {
        s = "";
    }
    n = strlen(s) + 1;
    p = (char *)malloc(n);
    if (p == NULL) {
        fprintf(stderr, "rb_tabs: out of memory\n");
        exit(1);
    }
    memcpy(p, s, n);
    return p;
}

struct rb_tabs {
    rb_tab *items;
    int count;
    int cap;
    long next_id;
};

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
        free(t->items[i].title);
        free(t->items[i].url);
    }
    free(t->items);
    free(t);
}

long rb_tabs_add(rb_tabs *t, const char *title, const char *url)
{
    rb_tab *slot;

    if (t == NULL) {
        return 0;
    }
    rb_tabs_grow(t);
    slot = &t->items[t->count];
    slot->id = t->next_id++;
    slot->title = rb_tabs_strdup(title);
    slot->url = rb_tabs_strdup(url);
    t->count++;
    return slot->id;
}

int rb_tabs_count(const rb_tabs *t)
{
    return (t != NULL) ? t->count : 0;
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

int rb_tabs_close(rb_tabs *t, long id)
{
    int i;

    if (t == NULL) {
        return 0;
    }
    i = rb_tabs_index_of(t, id);
    if (i < 0) {
        return 0;
    }
    free(t->items[i].title);
    free(t->items[i].url);
    if (i + 1 < t->count) {
        memmove(&t->items[i], &t->items[i + 1],
                (size_t)(t->count - i - 1) * sizeof(rb_tab));
    }
    t->count--;
    return 1;
}

rb_tab *rb_tabs_get(rb_tabs *t, long id)
{
    int i;

    if (t == NULL) {
        return NULL;
    }
    i = rb_tabs_index_of(t, id);
    return (i >= 0) ? &t->items[i] : NULL;
}

const rb_tab *rb_tabs_at(const rb_tabs *t, int index)
{
    if (t == NULL || index < 0 || index >= t->count) {
        return NULL;
    }
    return &t->items[index];
}
