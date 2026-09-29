/*
 * rb_switch.h — the profile-switch security protocol for Room Browser core.
 *
 * Ports android/core/domain/.../profile/ProfileSwitchStateMachine.kt (spec
 * section 48): the switch is an explicit state machine so no step can be
 * skipped and no stale browser context can be reused for another profile.
 *
 * The seven steps, in the only order they may run:
 *
 *   1. STOP_NAVIGATION              5. RELEASE_PROFILE_RESOURCES
 *   2. SAVE_TAB_STATE               6. LOAD_NEW_PROFILE_CONTEXT
 *   3. DESTROY_BROWSER_CONTEXT      7. RESTORE_NEW_PROFILE_TABS
 *   4. FLUSH_PROFILE_STATE
 *
 * WHY THE DESKTOP NEEDS THIS AT ALL: on Android the last two steps are done by
 * restarting the browser process, because WebView's data-directory suffix is
 * process-wide.  A desktop browser has no such restriction — each profile can
 * own a separate WebKitGTK WebsiteDataManager / WebView2 user-data folder that
 * is built and torn down per switch — so all seven steps run in-process here.
 * The order and the "destroy before load" rule still matter: they are what
 * stops a cookie jar, cache or session from leaking from one profile into the
 * next.  See the platform layer for which steps are real work and which are
 * bookkeeping.
 *
 * The machine itself does no I/O.  The caller performs each step and reports
 * back with rb_switch_step_done() or rb_switch_fail().
 */

#ifndef RB_SWITCH_H
#define RB_SWITCH_H

#ifdef __cplusplus
extern "C" {
#endif

/* Step, in declaration order — the order IS the protocol. */
typedef enum {
    RB_SWITCH_STOP_NAVIGATION = 0,
    RB_SWITCH_SAVE_TAB_STATE,
    RB_SWITCH_DESTROY_BROWSER_CONTEXT,
    RB_SWITCH_FLUSH_PROFILE_STATE,
    RB_SWITCH_RELEASE_PROFILE_RESOURCES,
    RB_SWITCH_LOAD_NEW_PROFILE_CONTEXT,
    RB_SWITCH_RESTORE_NEW_PROFILE_TABS,
    RB_SWITCH_STEP_COUNT /* not a step; the number of them */
} rb_switch_step;

typedef enum {
    RB_SWITCH_IDLE = 0,
    RB_SWITCH_SWITCHING,
    RB_SWITCH_COMPLETE,
    RB_SWITCH_FAILED
} rb_switch_state;

typedef struct rb_switch_machine rb_switch_machine;

rb_switch_machine *rb_switch_new(void);
void               rb_switch_free(rb_switch_machine *m);

/* The Kotlin enum name, e.g. "DESTROY_BROWSER_CONTEXT".  These spellings are
 * the contract: the error string below embeds one, exactly as Kotlin's
 * enum toString() does.  An out-of-range value reads as "STOP_NAVIGATION". */
const char        *rb_switch_step_name(rb_switch_step step);
const char        *rb_switch_state_name(rb_switch_state state);

/* --- reading the snapshot --- */

rb_switch_state    rb_switch_state_of(const rb_switch_machine *m);
/* The two ends of the switch.  These survive an abort — Kotlin's Abort only
 * clears the state and the step, so after a failed switch the snapshot still
 * names the profiles that were involved.  rb_switch_reset() clears them. */
const char        *rb_switch_from(const rb_switch_machine *m);
const char        *rb_switch_to(const rb_switch_machine *m);
const char        *rb_switch_error(const rb_switch_machine *m); /* NULL when there is none */

/* The step in flight, or -1 when there is none (IDLE, COMPLETE, FAILED). */
int                rb_switch_step_of(const rb_switch_machine *m);

int                rb_switch_completed_count(const rb_switch_machine *m);
/* The i-th completed step in the order it was completed, or -1. */
int                rb_switch_completed_at(const rb_switch_machine *m, int index);

/* ProfileSwitchStateMachine.involves: true while a switch is in flight and
 * `profile_id` is either end of it.  A locked profile cannot be left in a
 * half-switched state, so the caller uses this to refuse to open it. */
int                rb_switch_involves(const rb_switch_machine *m,
                                      const char *profile_id);

/* --- events --- */

/* Begin(from, to).  Returns 0 on success, or one of:
 *   -1  a switch is already in progress   (Kotlin's check())
 *   -2  from and to are the same profile  (Kotlin's require())
 *   -3  a NULL or blank profile id        (Kotlin would NPE; refused here)  */
#define RB_SWITCH_ERR_BUSY  (-1)
#define RB_SWITCH_ERR_SAME  (-2)
#define RB_SWITCH_ERR_BLANK (-3)
int  rb_switch_begin(rb_switch_machine *m, const char *from_id, const char *to_id);

/* StepCompleted.  Marks the step in flight done and advances to the next one;
 * after the seventh the state becomes COMPLETE.  Returns 0, or -1 when no
 * switch is in progress (Kotlin's check()). */
int  rb_switch_step_done(rb_switch_machine *m);

/* Error(step, reason).  Records "step <STEP_NAME>: <reason>" and FAILS the
 * switch.  A NULL reason reads as "unknown error", the Kotlin default.
 * Returns 0, or -1 when no switch is in progress. */
int  rb_switch_fail(rb_switch_machine *m, rb_switch_step step,
                    const char *reason);

/* Abort.  Allowed from ANY state, including IDLE, and always returns to IDLE
 * without touching the recorded error — which is what lets the caller fail a
 * step, keep the error text for the UI, and still end up able to switch
 * again. */
void rb_switch_abort(rb_switch_machine *m);

/* Back to a virgin machine: IDLE, no step, no completed list, no ids, no
 * error. */
void rb_switch_reset(rb_switch_machine *m);

#ifdef __cplusplus
}
#endif

#endif /* RB_SWITCH_H */
