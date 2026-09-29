/*
 * rb_profile.h — browser profiles for Room Browser core.
 *
 * Ports android/core/domain/.../profile/ProfileManager.kt and
 * .../model/Profile.kt.  The rules are the same ones the Android edition
 * enforces, because they are what keeps profile isolation honest:
 *
 *   - The UUID is the storage identity, never the name.  Renaming a profile
 *     therefore never moves, merges or loses its data.
 *   - Names are trimmed, non-empty, at most RB_PROFILE_MAX_NAME bytes, and
 *     unique case-insensitively across the registry.
 *   - The registry always has exactly one default profile (the first one
 *     created, or the next remaining one after a delete).
 *   - Deleting a profile deletes its whole subtree, not just its registry row.
 *   - A newly created profile gets a random User-Agent preset unless it was
 *     given an explicit one, so two fresh profiles do not look alike to the
 *     sites they visit.
 *
 * Persistence is JSON-lines, one profile per line, at profiles.jsonl in the
 * data directory.  The per-profile settings travel as a nested object of
 * strings, so a key written by a newer build survives a round trip through an
 * older one.
 */

#ifndef RB_PROFILE_H
#define RB_PROFILE_H

#include "rb_settings.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ProfileManager.MAX_NAME: bytes, not code points (matching the Android
 * edition, which counts UTF-16 units). */
#define RB_PROFILE_MAX_NAME   40

/* Canonical UUID text length (8-4-4-4-12). */
#define RB_PROFILE_ID_LEN     36

/* ProfileId.safeSuffix length: a UUID without dashes is exactly 32 chars,
 * which is also the limit a web-engine data directory suffix accepts. */
#define RB_PROFILE_SUFFIX_LEN 32

/* One profile.  Treat as read-only; every mutation goes through the registry
 * functions below so the invariants above cannot be bypassed.
 *
 * LIFETIME: rb_profile_at() / rb_profile_by_id() / rb_profile_default() hand
 * out pointers INTO the registry, and every registry mutation (create,
 * delete, duplicate, load) can move or free what they point at.  Copy an id
 * into a local buffer before mutating — delete() in particular frees the
 * very row whose id it was called with. */
typedef struct {
    char id[RB_PROFILE_ID_LEN + 1]; /* immutable storage identity */
    char *name;
    char *icon;              /* UTF-8 emoji; never NULL, defaults to a person */
    unsigned int color_argb; /* 0xAARRGGBB */
    int is_locked;
    int is_default;
    long long created_at;     /* ms since the epoch */
    long long last_active_at; /* ms since the epoch */
    char *theme_json;         /* per-profile theme snapshot; "" = default */
    rb_settings *settings;    /* defaults from rb_prefs_profile_defaults() */
} rb_profile;

/* Ordered registry of profiles. */
typedef struct rb_profile_registry rb_profile_registry;

/* Fresh, EMPTY registry (no profiles).  A profile is created with
 * rb_profile_create(); the registry does not invent one, so the caller
 * decides what the first profile is called. */
rb_profile_registry *rb_profile_registry_new(void);
void                 rb_profile_registry_free(rb_profile_registry *r);

int                  rb_profile_count(const rb_profile_registry *r);
const rb_profile    *rb_profile_at(const rb_profile_registry *r, int index);
int                  rb_profile_index_of(const rb_profile_registry *r, const char *id);
const rb_profile    *rb_profile_by_id(const rb_profile_registry *r, const char *id);
const rb_profile    *rb_profile_default(const rb_profile_registry *r);

/* Creates a profile.  Returns its index, or -1 when the name is empty, too
 * long, or already taken (case-insensitively, after trimming).
 *
 * `icon` may be NULL/empty for the default person glyph.  When
 * `randomize_ua` is non-zero and the new profile's settings still say
 * ua_mode=default, a random desktop UA preset is installed — the same
 * behaviour, and the same reason, as ProfileManager.create().  Import and
 * restore callers pass 0 so the payload's settings survive verbatim. */
int rb_profile_create(rb_profile_registry *r, const char *name, const char *icon,
                      unsigned int color_argb, int randomize_ua);

/* Renames a profile.  1 on success; 0 when the profile is unknown, the name
 * is empty/too long, or another profile already uses it. */
int rb_profile_rename(rb_profile_registry *r, const char *id, const char *name);

/* Cosmetic update of icon and/or colour (pass NULL/0 to keep a field).
 * Storage identity is untouched. */
int rb_profile_restyle(rb_profile_registry *r, const char *id,
                       const char *icon, unsigned int color_argb);

int rb_profile_set_locked(rb_profile_registry *r, const char *id, int locked);

/* Makes `id` the only default profile.  This is the profile the browser
 * opens on the next launch. */
int rb_profile_set_default(rb_profile_registry *r, const char *id);

/* Records that the profile was just used (last_active_at = now). */
int rb_profile_touch(rb_profile_registry *r, const char *id);

/* Removes a profile from the registry.  Returns 1 when it was present.  When
 * the removed profile was the default, the first remaining profile becomes
 * the new default — the registry is never left without one.  This does NOT
 * delete files; call rb_profile_remove_data() for that. */
int rb_profile_delete(rb_profile_registry *r, const char *id);

/* Duplicates a profile's identity and settings under a new UUID.  The name
 * becomes "<source><suffix>", then "<source><suffix> 2", " 3", ... until it
 * is unique — the same scheme as ProfileManager.duplicate().  `suffix` may be
 * NULL for " Copy".  Returns the new index, or -1 when the source is unknown
 * or the registry is full. */
int rb_profile_duplicate(rb_profile_registry *r, const char *id,
                         const char *suffix);

/* Copies every profile into the file at `path` (JSON lines, one per line).
 * 0 on success, -1 on I/O error. */
int rb_profile_registry_save(const rb_profile_registry *r, const char *path);

/* Loads profiles from `path`, replacing whatever the registry holds.  A
 * missing file is fine: the registry is left empty and 0 is returned.
 * Malformed lines are skipped; a file with no usable line leaves the
 * registry empty.  0 on success, -1 on I/O error. */
int rb_profile_registry_load(rb_profile_registry *r, const char *path);

/* ---- identity helpers ---- */

/* Writes a fresh lowercase UUID v4 (36 chars + NUL) into `out`, which must
 * hold at least RB_PROFILE_ID_LEN + 1 bytes.
 *
 * The bytes come from /dev/urandom when it can be opened and from a PRNG
 * seeded with the clock and stack addresses otherwise.  This is NOT a
 * cryptographic UUID generator, and it does not need to be: a profile id is
 * an on-disk storage identity, not a session token or a capability.  Anyone
 * who can read profiles.jsonl can already read every profile's data
 * directly — the id is not, and is not meant to be, an access control. */
void rb_profile_new_id(char *out);

/* ProfileId.safeSuffix: the id with its dashes removed, lowercased, and
 * truncated to RB_PROFILE_SUFFIX_LEN.  Writes at most
 * RB_PROFILE_SUFFIX_LEN + 1 bytes including the terminator. */
void rb_profile_safe_suffix(const char *id, char *out);

/* ---- directory layout (ProfileDirectoryLayout) ---- */

enum {
    RB_PROFILE_DIR_METADATA = 0, /* metadata   */
    RB_PROFILE_DIR_BROWSER_DATA, /* browser_data (the web engine's store) */
    RB_PROFILE_DIR_CACHE,        /* cache      */
    RB_PROFILE_DIR_DOWNLOADS,    /* downloads  */
    RB_PROFILE_DIR_SETTINGS,     /* settings   (settings.txt, history.jsonl) */
    RB_PROFILE_DIR_COUNT
};

/* malloc'd "<data_dir>/profiles/profile_<id>" (caller frees). */
char *rb_profile_dir(const char *data_dir, const char *id);

/* malloc'd "<data_dir>/profiles/profile_<id>/<which>". */
char *rb_profile_subdir(const char *data_dir, const char *id, int which);

/* malloc'd "<data_dir>/profiles". */
char *rb_profile_root(const char *data_dir);

/* Creates the five sub-directories above.  0 on success, -1 otherwise. */
int rb_profile_ensure_dirs(const char *data_dir, const char *id);

/* Recursively deletes "<data_dir>/profiles/profile_<id>".  Returns 1 when
 * the subtree is gone (also when it never existed), 0 when something is
 * still there.  Used by delete and reset. */
int rb_profile_remove_data(const char *data_dir, const char *id);

/* ---- settings access ---- */

/* Replaces the profile's settings wholesale (e.g. after a settings dialog).
 * The registry takes ownership of `settings`; pass NULL to reset the profile
 * to the defaults in rb_prefs.h. */
int rb_profile_set_settings(rb_profile_registry *r, const char *id,
                            rb_settings *settings);

/* millisecond wall clock used for created_at / last_active_at. */
long long rb_profile_now_ms(void);

#ifdef __cplusplus
}
#endif

#endif /* RB_PROFILE_H */
