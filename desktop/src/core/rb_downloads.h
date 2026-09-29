/*
 * rb_downloads.h — download records for Room Browser core.
 *
 * Ports the record-keeping half of
 * android/app/.../browser/engine/DownloadEngine.kt together with
 * DownloadEntity and DownloadStatus: what a download is, how its filename is
 * chosen, which state transitions are legal, and how the queue decides what
 * starts next.
 *
 * The transfer itself is deliberately NOT here.  Android drives it with
 * OkHttp and a coroutine per download; the desktop editions hand the bytes
 * to the platform web engine (WebKitDownload on GTK, DownloadStarting on
 * WebView2) and feed the progress it reports into these records.  That split
 * is the honest one: the platform stack already does chunked encoding,
 * redirects, proxies, cookies and TLS, and reimplementing HTTP in the core
 * would only produce a second, worse copy of it.
 *
 * Ordering matches the Android DAO — the list is kept newest-first
 * (created_at DESC), which is the order the downloads window shows.
 */

#ifndef RB_DOWNLOADS_H
#define RB_DOWNLOADS_H

#ifdef __cplusplus
extern "C" {
#endif

/* DownloadStatus, in declaration order.  The NAMES go into the store, so they
 * are a file format: never renumber and never rename. */
typedef enum {
    RB_DL_QUEUED = 0,
    RB_DL_RUNNING,
    RB_DL_PAUSED,
    RB_DL_COMPLETED,
    RB_DL_FAILED,
    RB_DL_CANCELLED
} rb_download_status;

/* DownloadEngine.MAX_PARALLEL: how many transfers run at once.  The rest
 * wait in QUEUED. */
#define RB_DOWNLOAD_MAX_PARALLEL 2

/* Names are truncated to this many bytes, matching DownloadEngine.sanitize's
 * take(120). */
#define RB_DOWNLOAD_MAX_NAME 120

/* One download.  Treat as read-only; every mutation goes through the store
 * functions so the state machine cannot be bypassed.
 *
 * LIFETIME: rb_downloads_at() / rb_downloads_by_id() hand out pointers INTO
 * the store, and adding, removing or loading can move or free what they
 * point at.  Copy the id out before mutating. */
typedef struct {
    long long id;
    char *profile_id;    /* owning profile; downloads are profile-scoped */
    char *url;
    char *file_name;     /* the name on disk, already de-duplicated */
    char *mime_type;     /* never NULL; "application/octet-stream" if unknown */
    char *destination;   /* final path / URI; "" until COMPLETED */
    long long total_bytes;      /* -1 while the length is unknown */
    long long downloaded_bytes;
    rb_download_status status;
    char *error;         /* "" unless the last attempt FAILED */
    long long created_at;       /* ms since the epoch */
    long long completed_at;     /* ms; 0 means "never completed" */
} rb_download;

/* Newest-first list of downloads across all profiles. */
typedef struct rb_downloads rb_downloads;

rb_downloads *rb_downloads_new(void);
void          rb_downloads_free(rb_downloads *d);

int                rb_downloads_count(const rb_downloads *d);
const rb_download *rb_downloads_at(const rb_downloads *d, int index);
const rb_download *rb_downloads_by_id(const rb_downloads *d, long long id);

/* ---- filenames (DownloadEngine.guessFileName / sanitize) ---- */

/* Chooses a file name for a download the web engine just announced.
 *
 * `content_disposition` may be NULL.  When it carries a filename parameter
 * that wins; otherwise the last path segment of `url` is used when it looks
 * like a name (it contains a '.'); otherwise the extension is derived from
 * `mime_type` and the name becomes "download-<now_ms><ext>".  `now_ms` is
 * passed in rather than read from the clock so the fallback is testable.
 *
 * The result is always a malloc'd, sanitized, non-empty name ("" is never
 * returned). */
char *rb_download_guess_name(const char *url, const char *content_disposition,
                             const char *mime_type, long long now_ms);

/* Makes a name safe to write to disk: every byte of \ / : * ? " < > | becomes
 * '_', runs of two or more dots collapse to one dot (so ".." can never
 * survive as a traversal), the result is truncated to RB_DOWNLOAD_MAX_NAME
 * bytes, and a blank result becomes "download".  Always malloc'd. */
char *rb_download_sanitize_name(const char *name);

/* ---- queue ---- */

/* Appends a download in QUEUED state and returns its id (never 0), or 0 when
 * the arguments are unusable.  `suggested_name` is sanitized and then
 * de-duplicated against the names the same profile already holds
 * ("report.pdf" -> "report (1).pdf" -> "report (2).pdf"), exactly like
 * DownloadEngine.dedupeName.  A blank name is derived from the URL and MIME
 * type with rb_download_guess_name() — Android's caller has already done that
 * by the time it reaches enqueue(), a desktop download handler often has only
 * the URL — and a blank MIME type becomes "application/octet-stream". */
long long rb_downloads_enqueue(rb_downloads *d, const char *profile_id,
                               const char *url, const char *suggested_name,
                               const char *mime_type, long long now_ms);

/* ---- state transitions ---- */

/* Moves a download to `status`.  `error` is stored (NULL/"" clears it) and is
 * only meaningful for RB_DL_FAILED.  Returns 1 when the id was found.
 *
 * Deliberately unchecked: the caller is the transfer, which knows better than
 * a table which move is legal (a FAILED download becomes QUEUED again on
 * retry, a CANCELLED one keeps its partial file).  The predicates below are
 * what the *UI* consults before offering an action. */
int rb_downloads_set_status(rb_downloads *d, long long id,
                            rb_download_status status, const char *error);

/* Progress tick.  A `total` of -1 or less means "length unknown".  Returns 1
 * when the id was found.  Does not change the status: the engine reports
 * progress while RUNNING and the caller decides what a stall means. */
int rb_downloads_set_progress(rb_downloads *d, long long id,
                              long long downloaded, long long total);

/* Marks a download COMPLETED: sets the destination, pins the byte counts to
 * `bytes`, records completed_at and clears any previous error. */
int rb_downloads_complete(rb_downloads *d, long long id, const char *destination,
                          long long bytes, long long now_ms);

/* Forgets one download.  Returns 1 when it was present.  Does NOT touch the
 * file on disk — the platform layer owns the file. */
int rb_downloads_remove(rb_downloads *d, long long id);

/* Forgets every download of one profile (DownloadDao.deleteAllFor).  Returns
 * how many were removed. */
int rb_downloads_clear_profile(rb_downloads *d, const char *profile_id);

/* ---- status helpers ---- */

/* Stable wire name ("QUEUED", "COMPLETED", ...) — never NULL. */
const char *rb_download_status_name(rb_download_status s);

/* Parses a stored name.  An unknown or NULL name yields `fallback`. */
rb_download_status rb_download_status_parse(const char *name,
                                            rb_download_status fallback);

/* QUEUED or RUNNING: DownloadDao.active()'s definition, and what the UI
 * counts when it decides whether to show a progress area. */
int rb_download_is_active(const rb_download *dl);

/* DownloadEngine.pause() only acts on QUEUED/RUNNING; resume() and retry()
 * only act on PAUSED/FAILED.  A NULL download answers 0 to both. */
int rb_download_can_pause(const rb_download *dl);
int rb_download_can_resume(const rb_download *dl);

/* DownloadEngine.progressPercent: 0..100, and 0 when the total is unknown.
 * Clamped, so a server that over-reports cannot push a bar past its end. */
int rb_download_progress_percent(long long downloaded, long long total);

/* ---- scheduling ---- */

/* How many downloads are RUNNING right now. */
int rb_downloads_running_count(const rb_downloads *d);

/* DownloadEngine.pump(): fills up to `max_ids` slots of `out_ids` with the
 * OLDEST queued downloads that fit in the free parallel slots, marks each one
 * RUNNING, and returns how many were started (0 when the queue is empty or
 * all RB_DOWNLOAD_MAX_PARALLEL slots are taken).
 *
 * Marking them RUNNING here rather than in the caller is what stops a second
 * pump() from handing the same download out twice. */
int rb_downloads_pump(rb_downloads *d, long long *out_ids, int max_ids);

/* ---- persistence ---- */

/* JSON lines, newest first.  0 on success, -1 on I/O error. */
int rb_downloads_save(const rb_downloads *d, const char *path);

/* Replaces the store's contents.  A missing file leaves it empty and returns
 * 0.  Unparseable lines are skipped.  Ids are preserved, and the id counter
 * is advanced past the highest one seen so a later enqueue cannot collide
 * with a restored row. */
int rb_downloads_load(rb_downloads *d, const char *path);

#ifdef __cplusplus
}
#endif

#endif /* RB_DOWNLOADS_H */
