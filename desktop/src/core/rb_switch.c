/*
 * rb_switch.c — the profile-switch security protocol for Room Browser core.
 * Pure C11; only the C standard library is used.
 */

#include "rb_switch.h"

#include "rb_json.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *const RB_SWITCH_STEP_NAMES[RB_SWITCH_STEP_COUNT] = {
    "STOP_NAVIGATION",
    "SAVE_TAB_STATE",
    "DESTROY_BROWSER_CONTEXT",
    "FLUSH_PROFILE_STATE",
    "RELEASE_PROFILE_RESOURCES",
    "LOAD_NEW_PROFILE_CONTEXT",
    "RESTORE_NEW_PROFILE_TABS"
};

static const char *const RB_SWITCH_STATE_NAMES[] = { "IDLE", "SWITCHING",
                                                     "COMPLETE", "FAILED" };

struct rb_switch_machine {
    rb_switch_state state;
    int current_step; /* -1 for "none" */
    int *completed;   /* step orders, in completion order */
    int completed_count;
    int completed_cap;
    char *from;
    char *to;
    char *error;
};

const char *rb_switch_step_name(rb_switch_step step)
{
    if ((int)step < 0 || step >= RB_SWITCH_STEP_COUNT) {
        return RB_SWITCH_STEP_NAMES[RB_SWITCH_STOP_NAVIGATION];
    }
    return RB_SWITCH_STEP_NAMES[step];
}

const char *rb_switch_state_name(rb_switch_state state)
{
    if ((int)state < 0 || state > RB_SWITCH_FAILED) {
        return RB_SWITCH_STATE_NAMES[RB_SWITCH_IDLE];
    }
    return RB_SWITCH_STATE_NAMES[state];
}

rb_switch_machine *rb_switch_new(void)
{
    rb_switch_machine *m = (rb_switch_machine *)calloc(1, sizeof(*m));

    if (m == NULL) {
        fprintf(stderr, "rb_switch: out of memory\n");
        exit(1);
    }
    m->state = RB_SWITCH_IDLE;
    m->current_step = -1;
    return m;
}

static void rb_switch_free_str(char **p)
{
    free(*p);
    *p = NULL;
}

void rb_switch_free(rb_switch_machine *m)
{
    if (m == NULL) {
        return;
    }
    free(m->completed);
    rb_switch_free_str(&m->from);
    rb_switch_free_str(&m->to);
    rb_switch_free_str(&m->error);
    free(m);
}

/* ------------------------------- reading -------------------------------- */

rb_switch_state rb_switch_state_of(const rb_switch_machine *m)
{
    return (m != NULL) ? m->state : RB_SWITCH_IDLE;
}

const char *rb_switch_from(const rb_switch_machine *m)
{
    return (m != NULL) ? m->from : NULL;
}

const char *rb_switch_to(const rb_switch_machine *m)
{
    return (m != NULL) ? m->to : NULL;
}

const char *rb_switch_error(const rb_switch_machine *m)
{
    return (m != NULL) ? m->error : NULL;
}

int rb_switch_step_of(const rb_switch_machine *m)
{
    return (m != NULL) ? m->current_step : -1;
}

int rb_switch_completed_count(const rb_switch_machine *m)
{
    return (m != NULL) ? m->completed_count : 0;
}

int rb_switch_completed_at(const rb_switch_machine *m, int index)
{
    if (m == NULL || index < 0 || index >= m->completed_count) {
        return -1;
    }
    return m->completed[index];
}

int rb_switch_involves(const rb_switch_machine *m, const char *profile_id)
{
    if (m == NULL || profile_id == NULL || m->state != RB_SWITCH_SWITCHING) {
        return 0;
    }
    if (m->from != NULL && strcmp(m->from, profile_id) == 0) {
        return 1;
    }
    return m->to != NULL && strcmp(m->to, profile_id) == 0;
}

/* -------------------------------- events -------------------------------- */

static void rb_switch_push_completed(rb_switch_machine *m, int step)
{
    if (m->completed_count == m->completed_cap) {
        int ncap = (m->completed_cap > 0) ? m->completed_cap * 2 : 8;
        int *grown = (int *)realloc(m->completed, (size_t)ncap * sizeof(int));

        if (grown == NULL) {
            fprintf(stderr, "rb_switch: out of memory\n");
            exit(1);
        }
        m->completed = grown;
        m->completed_cap = ncap;
    }
    m->completed[m->completed_count++] = step;
}

int rb_switch_begin(rb_switch_machine *m, const char *from_id, const char *to_id)
{
    if (m == NULL) {
        return RB_SWITCH_ERR_BLANK;
    }
    /* Kotlin's check(state == IDLE) — a switch may not be begun on top of
     * another one. */
    if (m->state != RB_SWITCH_IDLE) {
        return RB_SWITCH_ERR_BUSY;
    }
    if (from_id == NULL || from_id[0] == '\0' || to_id == NULL ||
        to_id[0] == '\0') {
        return RB_SWITCH_ERR_BLANK;
    }
    /* Kotlin's require(from != to): switching a profile onto itself would
     * destroy a live context and reload the same storage. */
    if (strcmp(from_id, to_id) == 0) {
        return RB_SWITCH_ERR_SAME;
    }
    /* NOTE: the completed list is deliberately NOT cleared here.  Kotlin's
     * Begin does not clear it either, and this port keeps that — the list is
     * a running log the snapshot exposes, and the next step is derived from
     * its tail, so a stale prefix cannot skip a step.  Callers that want a
     * clean log call rb_switch_reset() first. */
    rb_switch_free_str(&m->from);
    rb_switch_free_str(&m->to);
    m->from = rb_json_strdup(from_id);
    m->to = rb_json_strdup(to_id);
    m->state = RB_SWITCH_SWITCHING;
    m->current_step = RB_SWITCH_STOP_NAVIGATION;
    return 0;
}

int rb_switch_step_done(rb_switch_machine *m)
{
    int last_order = -1;
    int i;

    if (m == NULL || m->state != RB_SWITCH_SWITCHING) {
        return -1;
    }
    if (m->current_step >= 0) {
        rb_switch_push_completed(m, m->current_step);
    }
    if (m->completed_count > 0) {
        last_order = m->completed[m->completed_count - 1];
    }
    /* firstOrNull { it.order == last.order + 1 } — with no completed step at
     * all this asks for order 0, which is STOP_NAVIGATION again. */
    m->current_step = -1;
    for (i = 0; i < RB_SWITCH_STEP_COUNT; i++) {
        if (i == last_order + 1) {
            m->current_step = i;
            break;
        }
    }
    if (m->current_step < 0) {
        m->state = RB_SWITCH_COMPLETE;
    }
    return 0;
}

int rb_switch_fail(rb_switch_machine *m, rb_switch_step step, const char *reason)
{
    const char *why = (reason != NULL) ? reason : "unknown error";
    size_t need;

    if (m == NULL || m->state != RB_SWITCH_SWITCHING) {
        return -1;
    }
    rb_switch_free_str(&m->error);
    /* "step <STEP>: <reason>", the Kotlin interpolation.  The step name is
     * the enum's toString(), not the lowercase spelling. */
    need = strlen("step ") + strlen(rb_switch_step_name(step)) + strlen(": ") +
           strlen(why) + 1;
    m->error = (char *)malloc(need);
    if (m->error == NULL) {
        fprintf(stderr, "rb_switch: out of memory\n");
        exit(1);
    }
    snprintf(m->error, need, "step %s: %s", rb_switch_step_name(step), why);
    m->state = RB_SWITCH_FAILED;
    return 0;
}

void rb_switch_abort(rb_switch_machine *m)
{
    if (m == NULL) {
        return;
    }
    /* Any state, and the error text survives: the caller reports it after
     * the switch has already been rolled back. */
    m->state = RB_SWITCH_IDLE;
    m->current_step = -1;
}

void rb_switch_reset(rb_switch_machine *m)
{
    if (m == NULL) {
        return;
    }
    m->state = RB_SWITCH_IDLE;
    m->current_step = -1;
    m->completed_count = 0;
    rb_switch_free_str(&m->from);
    rb_switch_free_str(&m->to);
    rb_switch_free_str(&m->error);
}
