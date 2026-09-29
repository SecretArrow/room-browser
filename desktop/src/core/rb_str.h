/*
 * rb_str.h — minimal growable UTF-8 string buffer for Room Browser core.
 *
 * The buffer is always NUL-terminated when data != NULL.  rb_str_c() never
 * returns NULL (it returns "" for an empty buffer), so callers can pass the
 * result straight to printf-family functions.  All functions tolerate NULL
 * arguments as no-ops where the signature allows it.
 */

#ifndef RB_STR_H
#define RB_STR_H

#include <sys/types.h> /* size_t */

#ifdef __cplusplus
extern "C" {
#endif

typedef struct { char *data; size_t len; size_t cap; } rb_str;

/* Reset to an empty, unallocated state. */
void rb_str_init(rb_str *s);

/* Frees the buffer and resets the struct to the init() state.  Safe to call
 * twice. */
void rb_str_free(rb_str *s);

/* Keeps the buffer but makes the string empty. */
void rb_str_clear(rb_str *s);

/* Appends a NUL-terminated UTF-8 string.  NULL rhs is a no-op. */
void rb_str_append(rb_str *s, const char *rhs);

/* Appends printf-style formatted output (grows the buffer as needed). */
void rb_str_appendf(rb_str *s, const char *fmt, ...);

/* Returns the current contents; never NULL ("" when empty). */
const char *rb_str_c(const rb_str *s);

#ifdef __cplusplus
}
#endif

#endif /* RB_STR_H */
