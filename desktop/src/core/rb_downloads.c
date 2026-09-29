/*
 * rb_downloads.c — download records for Room Browser core.
 * Pure C11; only the C standard library is used.
 */

#include "rb_downloads.h"

#include "rb_json.h"
#include "rb_str.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define RB_DOWNLOADS_LINE 8192

struct rb_downloads {
    rb_download *items; /* newest first, matching the Android DAO's ORDER BY */
    int count;
    int cap;
    long long next_id;
};

/* --------------------------- small helpers ------------------------------ */

static int rb_dl_hex_val(char c)
{
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
        return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
        return c - 'A' + 10;
    }
    return -1;
}

static char rb_dl_lower(char c)
{
    return (c >= 'A' && c <= 'Z') ? (char)(c - 'A' + 'a') : c;
}

/* 1 when the string is NULL, empty, or only ASCII whitespace.  Mirrors
 * Kotlin's String.isBlank(). */
static int rb_dl_blank(const char *s)
{
    size_t i;

    if (s == NULL) {
        return 1;
    }
    for (i = 0; s[i] != '\0'; i++) {
        if (s[i] != ' ' && s[i] != '\t' && s[i] != '\r' && s[i] != '\n') {
            return 0;
        }
    }
    return 1;
}

static int rb_dl_starts_ci(const char *hay, const char *needle)
{
    size_t i;

    for (i = 0; needle[i] != '\0'; i++) {
        if (rb_dl_lower(hay[i]) != rb_dl_lower(needle[i])) {
            return 0;
        }
    }
    return 1;
}

/* java.net.URLDecoder.decode(s, "UTF-8"), which is what
 * DownloadEngine.guessFileName applies to both filename candidates.
 *
 * That decoder also turns '+' into a space, which a strict RFC 3986 path
 * decoder would not.  Room Browser mirrors Android here on purpose: the same
 * Content-Disposition header has to produce the same file name in both
 * editions.
 *
 * Two deliberate differences, both in the direction of "never fail":
 *   - a malformed %XX is left as literal text instead of throwing, and
 *   - a %00 is dropped, because the name is a C string and no filesystem
 *     accepts an embedded NUL anyway. */
static char *rb_dl_url_decode(const char *s)
{
    rb_str b;
    size_t i;

    rb_str_init(&b);
    for (i = 0; s != NULL && s[i] != '\0'; i++) {
        char one[2];
        int hi;
        int lo;

        if (s[i] == '+') {
            rb_str_append(&b, " ");
            continue;
        }
        hi = rb_dl_hex_val(s[i + 1]);
        lo = (s[i] == '%' && hi >= 0) ? rb_dl_hex_val(s[i + 2]) : -1;
        if (s[i] == '%' && hi >= 0 && lo >= 0) {
            one[0] = (char)(hi * 16 + lo);
            one[1] = '\0';
            if (one[0] != '\0') {
                rb_str_append(&b, one);
            }
            i += 2;
            continue;
        }
        one[0] = s[i];
        one[1] = '\0';
        rb_str_append(&b, one);
    }
    return (b.data != NULL) ? b.data : rb_json_strdup("");
}

/* The value of a Content-Disposition filename parameter, URL-decoded, or NULL
 * when the header carries none.  Mirrors the regex
 *   filename\*?=(?:UTF-8''|"?)([^";]+)"?
 * case-insensitively. */
static char *rb_dl_disposition_filename(const char *cd)
{
    size_t i;

    if (cd == NULL) {
        return NULL;
    }
    for (i = 0; cd[i] != '\0'; i++) {
        size_t j;
        size_t start;
        char *raw;

        if (!rb_dl_starts_ci(cd + i, "filename")) {
            continue;
        }
        j = i + 8; /* strlen("filename") */
        if (cd[j] == '*') {
            j++;
        }
        if (cd[j] != '=') {
            continue;
        }
        j++;
        if (rb_dl_starts_ci(cd + j, "UTF-8''")) {
            j += 7;
        }
        if (cd[j] == '"') {
            j++;
        }
        start = j;
        while (cd[j] != '\0' && cd[j] != '"' && cd[j] != ';') {
            j++;
        }
        if (j == start) {
            continue; /* the regex requires at least one character */
        }
        raw = (char *)malloc(j - start + 1);
        if (raw == NULL) {
            fprintf(stderr, "rb_downloads: out of memory\n");
            exit(1);
        }
        memcpy(raw, cd + start, j - start);
        raw[j - start] = '\0';
        {
            char *decoded = rb_dl_url_decode(raw);
            free(raw);
            return decoded;
        }
    }
    return NULL;
}

/* url.substringBefore('?').substringAfterLast('/'), URL-decoded.
 *
 * The '#' fragment is deliberately NOT stripped, because Android does not
 * strip it either: both editions must name the same download identically.
 * A server that puts a fragment on a downloadable URL is vanishingly rare,
 * and disagreeing about it would be worse than the wart. */
static char *rb_dl_last_segment(const char *url)
{
    const char *q;
    size_t len;
    size_t start = 0;
    size_t k;
    char *raw;
    char *decoded;

    if (url == NULL) {
        return rb_json_strdup("");
    }
    q = strchr(url, '?');
    len = (q != NULL) ? (size_t)(q - url) : strlen(url);
    for (k = 0; k < len; k++) {
        if (url[k] == '/') {
            start = k + 1;
        }
    }
    raw = (char *)malloc(len - start + 1);
    if (raw == NULL) {
        fprintf(stderr, "rb_downloads: out of memory\n");
        exit(1);
    }
    memcpy(raw, url + start, len - start);
    raw[len - start] = '\0';
    decoded = rb_dl_url_decode(raw);
    free(raw);
    return decoded;
}

/* The extension DownloadEngine derives from a MIME type.  Returns a string
 * literal (never NULL, "" when the type is unknown) — do not free. */
static const char *rb_dl_ext_for_mime(const char *mime_type)
{
    static const struct {
        const char *mime;
        const char *ext;
    } map[] = {
        {"text/html", ".html"},  {"text/plain", ".txt"}, {"image/png", ".png"},
        {"image/jpeg", ".jpg"},  {"image/gif", ".gif"},  {"image/webp", ".webp"},
        {"application/pdf", ".pdf"}, {"application/zip", ".zip"},
        {"audio/mpeg", ".mp3"},  {"video/mp4", ".mp4"}
    };
    char buf[64];
    size_t n = 0;
    size_t i;

    while (mime_type != NULL && mime_type[n] != '\0' && mime_type[n] != ';' &&
           n < sizeof(buf) - 1) {
        buf[n] = rb_dl_lower(mime_type[n]);
        n++;
    }
    buf[n] = '\0';
    for (i = 0; i < sizeof(map) / sizeof(map[0]); i++) {
        if (strcmp(buf, map[i].mime) == 0) {
            return map[i].ext;
        }
    }
    return "";
}

/* ------------------------------- names ---------------------------------- */

char *rb_download_sanitize_name(const char *name)
{
    rb_str b;
    size_t i;
    size_t len;
    char *out;
    const char *built;

    rb_str_init(&b);
    for (i = 0; name != NULL && name[i] != '\0'; i++) {
        char one[2];
        char c = name[i];

        if (c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' ||
            c == '"' || c == '<' || c == '>' || c == '|') {
            rb_str_append(&b, "_");
            continue;
        }
        if (c == '.' && name[i + 1] == '.') {
            /* A run of dots collapses to one, which is what keeps ".."
             * from surviving as a path traversal. */
            rb_str_append(&b, ".");
            while (name[i + 1] == '.') {
                i++;
            }
            continue;
        }
        one[0] = c;
        one[1] = '\0';
        rb_str_append(&b, one);
    }

    built = rb_str_c(&b);
    len = (built != NULL) ? strlen(built) : 0;
    if (len > RB_DOWNLOAD_MAX_NAME) {
        /* Truncate on a character boundary.  Android's take(120) counts
         * UTF-16 units and can cut a surrogate pair in half; cutting a UTF-8
         * sequence in half would leave a name that is not valid UTF-8 at
         * all, which every later JSON round trip would then carry along. */
        len = RB_DOWNLOAD_MAX_NAME;
        while (len > 0 && ((unsigned char)built[len] & 0xC0u) == 0x80u) {
            len--;
        }
    }

    out = (char *)malloc(len + 1);
    if (out == NULL) {
        fprintf(stderr, "rb_downloads: out of memory\n");
        exit(1);
    }
    if (len > 0) {
        memcpy(out, built, len);
    }
    out[len] = '\0';
    rb_str_free(&b);

    if (rb_dl_blank(out)) {
        free(out);
        return rb_json_strdup("download");
    }
    return out;
}

char *rb_download_guess_name(const char *url, const char *content_disposition,
                             const char *mime_type, long long now_ms)
{
    char *from_disposition = rb_dl_disposition_filename(content_disposition);

    if (!rb_dl_blank(from_disposition)) {
        char *out = rb_download_sanitize_name(from_disposition);
        free(from_disposition);
        return out;
    }
    free(from_disposition);

    {
        char *from_url = rb_dl_last_segment(url);

        if (!rb_dl_blank(from_url) && strchr(from_url, '.') != NULL) {
            char *out = rb_download_sanitize_name(from_url);
            free(from_url);
            return out;
        }
        free(from_url);
    }

    {
        rb_str b;
        char *out;

        rb_str_init(&b);
        rb_str_appendf(&b, "download-%lld%s", now_ms,
                       rb_dl_ext_for_mime(mime_type));
        out = (b.data != NULL) ? b.data : rb_json_strdup("download");
        return out;
    }
}

/* ------------------------------- storage -------------------------------- */

static void rb_dl_release(rb_download *dl)
{
    free(dl->profile_id);
    free(dl->url);
    free(dl->file_name);
    free(dl->mime_type);
    free(dl->destination);
    free(dl->error);
    memset(dl, 0, sizeof(*dl));
}

static void rb_downloads_reserve(rb_downloads *d)
{
    int ncap;
    rb_download *grown;

    if (d->count < d->cap) {
        return;
    }
    ncap = (d->cap > 0) ? d->cap * 2 : 8;
    grown = (rb_download *)realloc(d->items, (size_t)ncap * sizeof(rb_download));
    if (grown == NULL) {
        fprintf(stderr, "rb_downloads: out of memory\n");
        exit(1);
    }
    d->items = grown;
    d->cap = ncap;
}

/* Inserts a fully-built record.  `front` is the enqueue path (new downloads
 * are the newest, and the list is newest-first); load() appends instead,
 * because the file order it just read is already the display order. */
static rb_download *rb_downloads_insert(rb_downloads *d, int front)
{
    rb_download *slot;

    rb_downloads_reserve(d);
    if (front && d->count > 0) {
        memmove(&d->items[1], &d->items[0],
                (size_t)d->count * sizeof(rb_download));
        slot = &d->items[0];
    } else {
        slot = &d->items[d->count];
    }
    d->count++;
    memset(slot, 0, sizeof(*slot));
    return slot;
}

static int rb_dl_name_taken(const rb_downloads *d, const char *profile_id,
                            const char *name)
{
    int i;

    for (i = 0; i < d->count; i++) {
        if (strcmp(d->items[i].profile_id, profile_id) == 0 &&
            strcmp(d->items[i].file_name, name) == 0) {
            return 1;
        }
    }
    return 0;
}

/* DownloadEngine.dedupeName: "report.pdf" -> "report (1).pdf" -> " (2)", ...
 * A leading dot is not an extension separator, matching the Kotlin check
 * `dot > 0`. */
static char *rb_dl_dedupe(const rb_downloads *d, const char *profile_id,
                          const char *name)
{
    const char *dot;
    size_t base_len;
    const char *ext;
    int counter = 1;

    if (!rb_dl_name_taken(d, profile_id, name)) {
        return rb_json_strdup(name);
    }
    dot = strrchr(name, '.');
    if (dot != NULL && dot != name) {
        base_len = (size_t)(dot - name);
        ext = dot;
    } else {
        base_len = strlen(name);
        ext = "";
    }
    for (;;) {
        char suffix[32];
        size_t total;
        char *out;

        snprintf(suffix, sizeof(suffix), " (%d)", counter);
        total = base_len + strlen(suffix) + strlen(ext) + 1;
        out = (char *)malloc(total);
        if (out == NULL) {
            fprintf(stderr, "rb_downloads: out of memory\n");
            exit(1);
        }
        memcpy(out, name, base_len);
        memcpy(out + base_len, suffix, strlen(suffix));
        memcpy(out + base_len + strlen(suffix), ext, strlen(ext) + 1);
        if (!rb_dl_name_taken(d, profile_id, out)) {
            return out;
        }
        free(out);
        counter++;
    }
}

rb_downloads *rb_downloads_new(void)
{
    rb_downloads *d = (rb_downloads *)calloc(1, sizeof(*d));

    if (d == NULL) {
        fprintf(stderr, "rb_downloads: out of memory\n");
        exit(1);
    }
    d->next_id = 1; /* 0 is reserved for "no such download" */
    return d;
}

void rb_downloads_free(rb_downloads *d)
{
    int i;

    if (d == NULL) {
        return;
    }
    for (i = 0; i < d->count; i++) {
        rb_dl_release(&d->items[i]);
    }
    free(d->items);
    free(d);
}

int rb_downloads_count(const rb_downloads *d)
{
    return (d != NULL) ? d->count : 0;
}

const rb_download *rb_downloads_at(const rb_downloads *d, int index)
{
    if (d == NULL || index < 0 || index >= d->count) {
        return NULL;
    }
    return &d->items[index];
}

const rb_download *rb_downloads_by_id(const rb_downloads *d, long long id)
{
    int i;

    if (d == NULL) {
        return NULL;
    }
    for (i = 0; i < d->count; i++) {
        if (d->items[i].id == id) {
            return &d->items[i];
        }
    }
    return NULL;
}

long long rb_downloads_enqueue(rb_downloads *d, const char *profile_id,
                               const char *url, const char *suggested_name,
                               const char *mime_type, long long now_ms)
{
    rb_download *slot;
    char *sanitized;
    char *name;

    if (d == NULL || rb_dl_blank(profile_id) || rb_dl_blank(url)) {
        return 0;
    }
    sanitized = rb_dl_blank(suggested_name)
                    ? rb_download_guess_name(url, NULL, mime_type, now_ms)
                    : rb_download_sanitize_name(suggested_name);
    name = rb_dl_dedupe(d, profile_id, sanitized);
    free(sanitized);

    slot = rb_downloads_insert(d, 1);
    slot->id = d->next_id++;
    slot->profile_id = rb_json_strdup(profile_id);
    slot->url = rb_json_strdup(url);
    slot->file_name = name;
    slot->mime_type = rb_dl_blank(mime_type)
                          ? rb_json_strdup("application/octet-stream")
                          : rb_json_strdup(mime_type);
    slot->destination = rb_json_strdup("");
    slot->total_bytes = -1;
    slot->downloaded_bytes = 0;
    slot->status = RB_DL_QUEUED;
    slot->error = rb_json_strdup("");
    slot->created_at = now_ms;
    slot->completed_at = 0;
    return slot->id;
}

/* --------------------------- state transitions -------------------------- */

int rb_downloads_set_status(rb_downloads *d, long long id,
                            rb_download_status status, const char *error)
{
    int i;

    if (d == NULL) {
        return 0;
    }
    for (i = 0; i < d->count; i++) {
        if (d->items[i].id != id) {
            continue;
        }
        d->items[i].status = status;
        free(d->items[i].error);
        d->items[i].error = rb_json_strdup(rb_dl_blank(error) ? "" : error);
        return 1;
    }
    return 0;
}

int rb_downloads_set_progress(rb_downloads *d, long long id,
                              long long downloaded, long long total)
{
    int i;

    if (d == NULL) {
        return 0;
    }
    for (i = 0; i < d->count; i++) {
        if (d->items[i].id != id) {
            continue;
        }
        d->items[i].downloaded_bytes = downloaded;
        d->items[i].total_bytes = total;
        return 1;
    }
    return 0;
}

int rb_downloads_complete(rb_downloads *d, long long id, const char *destination,
                          long long bytes, long long now_ms)
{
    int i;

    if (d == NULL) {
        return 0;
    }
    for (i = 0; i < d->count; i++) {
        if (d->items[i].id != id) {
            continue;
        }
        free(d->items[i].destination);
        d->items[i].destination = rb_json_strdup(destination);
        free(d->items[i].error);
        d->items[i].error = rb_json_strdup("");
        /* The final size is the file on disk, not the Content-Length the
         * server announced: a ranged or truncated transfer ends here. */
        d->items[i].downloaded_bytes = bytes;
        d->items[i].total_bytes = bytes;
        d->items[i].status = RB_DL_COMPLETED;
        d->items[i].completed_at = now_ms;
        return 1;
    }
    return 0;
}

int rb_downloads_remove(rb_downloads *d, long long id)
{
    int i;

    if (d == NULL) {
        return 0;
    }
    for (i = 0; i < d->count; i++) {
        if (d->items[i].id != id) {
            continue;
        }
        rb_dl_release(&d->items[i]);
        if (i + 1 < d->count) {
            memmove(&d->items[i], &d->items[i + 1],
                    (size_t)(d->count - i - 1) * sizeof(rb_download));
        }
        d->count--;
        return 1;
    }
    return 0;
}

int rb_downloads_clear_profile(rb_downloads *d, const char *profile_id)
{
    int removed = 0;
    int i = 0;

    if (d == NULL || rb_dl_blank(profile_id)) {
        return 0;
    }
    while (i < d->count) {
        if (strcmp(d->items[i].profile_id, profile_id) != 0) {
            i++;
            continue;
        }
        rb_dl_release(&d->items[i]);
        if (i + 1 < d->count) {
            memmove(&d->items[i], &d->items[i + 1],
                    (size_t)(d->count - i - 1) * sizeof(rb_download));
        }
        d->count--;
        removed++;
    }
    return removed;
}

/* ----------------------------- status helpers --------------------------- */

const char *rb_download_status_name(rb_download_status s)
{
    switch (s) {
    case RB_DL_QUEUED:
        return "QUEUED";
    case RB_DL_RUNNING:
        return "RUNNING";
    case RB_DL_PAUSED:
        return "PAUSED";
    case RB_DL_COMPLETED:
        return "COMPLETED";
    case RB_DL_FAILED:
        return "FAILED";
    case RB_DL_CANCELLED:
        return "CANCELLED";
    }
    return "QUEUED";
}

rb_download_status rb_download_status_parse(const char *name,
                                            rb_download_status fallback)
{
    static const char *const names[] = {"QUEUED",  "RUNNING", "PAUSED",
                                        "COMPLETED", "FAILED", "CANCELLED"};
    size_t i;

    if (name == NULL) {
        return fallback;
    }
    for (i = 0; i < sizeof(names) / sizeof(names[0]); i++) {
        if (strcmp(name, names[i]) == 0) {
            return (rb_download_status)i;
        }
    }
    return fallback;
}

int rb_download_is_active(const rb_download *dl)
{
    return (dl != NULL &&
            (dl->status == RB_DL_QUEUED || dl->status == RB_DL_RUNNING))
               ? 1
               : 0;
}

int rb_download_can_pause(const rb_download *dl)
{
    return (dl != NULL &&
            (dl->status == RB_DL_RUNNING || dl->status == RB_DL_QUEUED))
               ? 1
               : 0;
}

int rb_download_can_resume(const rb_download *dl)
{
    return (dl != NULL &&
            (dl->status == RB_DL_PAUSED || dl->status == RB_DL_FAILED))
               ? 1
               : 0;
}

int rb_download_progress_percent(long long downloaded, long long total)
{
    long long pct;

    if (total <= 0 || downloaded <= 0) {
        return 0;
    }
    pct = (downloaded * 100) / total;
    if (pct > 100) {
        pct = 100;
    }
    if (pct < 0) {
        pct = 0;
    }
    return (int)pct;
}

/* ------------------------------ scheduling ------------------------------ */

int rb_downloads_running_count(const rb_downloads *d)
{
    int i;
    int n = 0;

    if (d == NULL) {
        return 0;
    }
    for (i = 0; i < d->count; i++) {
        if (d->items[i].status == RB_DL_RUNNING) {
            n++;
        }
    }
    return n;
}

int rb_downloads_pump(rb_downloads *d, long long *out_ids, int max_ids)
{
    int slots;
    int started = 0;

    if (d == NULL || out_ids == NULL || max_ids <= 0) {
        return 0;
    }
    slots = RB_DOWNLOAD_MAX_PARALLEL - rb_downloads_running_count(d);
    if (slots > max_ids) {
        slots = max_ids;
    }
    while (started < slots) {
        /* The DAO's ORDER BY created_at ASC: oldest first, ties by id so the
         * order is total and a pump is reproducible. */
        int best = -1;
        int i;

        for (i = 0; i < d->count; i++) {
            if (d->items[i].status != RB_DL_QUEUED) {
                continue;
            }
            if (best < 0 || d->items[i].created_at < d->items[best].created_at ||
                (d->items[i].created_at == d->items[best].created_at &&
                 d->items[i].id < d->items[best].id)) {
                best = i;
            }
        }
        if (best < 0) {
            break;
        }
        d->items[best].status = RB_DL_RUNNING;
        out_ids[started++] = d->items[best].id;
    }
    return started;
}

/* ------------------------------ persistence ----------------------------- */

/* Writes a JSON string value, quotes included.  Escaping without the quotes
 * produces a file that is not JSON at all, which is exactly the kind of
 * defect a round-trip test exists to catch. */
static int rb_dl_write_str(FILE *f, const char *value)
{
    char *escaped = rb_json_escape(value);
    int rc = 0;

    if (fputc('"', f) == EOF || fputs(escaped, f) == EOF || fputc('"', f) == EOF) {
        rc = -1;
    }
    free(escaped);
    return rc;
}

int rb_downloads_save(const rb_downloads *d, const char *path)
{
    FILE *f;
    int i;
    int rc = 0;

    if (d == NULL || path == NULL) {
        return -1;
    }
    f = fopen(path, "wb");
    if (f == NULL) {
        return -1;
    }
    for (i = 0; i < d->count && rc == 0; i++) {
        const rb_download *dl = &d->items[i];

        if (fprintf(f, "{\"id\":%lld,\"profile_id\":", dl->id) < 0) {
            rc = -1;
            break;
        }
        rc = rb_dl_write_str(f, dl->profile_id);
        if (rc == 0 && fputs(",\"url\":", f) == EOF) {
            rc = -1;
        }
        if (rc == 0) {
            rc = rb_dl_write_str(f, dl->url);
        }
        if (rc == 0 && fputs(",\"file_name\":", f) == EOF) {
            rc = -1;
        }
        if (rc == 0) {
            rc = rb_dl_write_str(f, dl->file_name);
        }
        if (rc == 0 && fputs(",\"mime_type\":", f) == EOF) {
            rc = -1;
        }
        if (rc == 0) {
            rc = rb_dl_write_str(f, dl->mime_type);
        }
        if (rc == 0 && fputs(",\"destination\":", f) == EOF) {
            rc = -1;
        }
        if (rc == 0) {
            rc = rb_dl_write_str(f, dl->destination);
        }
        if (rc == 0 && fputs(",\"error\":", f) == EOF) {
            rc = -1;
        }
        if (rc == 0) {
            rc = rb_dl_write_str(f, dl->error);
        }
        if (rc == 0 &&
            fprintf(f,
                    ",\"total_bytes\":%lld,\"downloaded_bytes\":%lld,"
                    "\"status\":\"%s\",\"created_at\":%lld,"
                    "\"completed_at\":%lld}\n",
                    dl->total_bytes, dl->downloaded_bytes,
                    rb_download_status_name(dl->status), dl->created_at,
                    dl->completed_at) < 0) {
            rc = -1;
        }
    }
    if (rc == 0 && ferror(f)) {
        rc = -1;
    }
    if (fclose(f) != 0) {
        rc = -1;
    }
    return rc;
}

/* Reads one field, tolerating both a JSON string and a bare number so a
 * hand-edited file is not rejected outright. */
static char *rb_dl_field_str(const char *line, const char *key)
{
    size_t pos;

    if (!rb_json_find_key(line, key, &pos)) {
        return NULL;
    }
    if (line[pos] == '"') {
        char *out = NULL;
        if (rb_json_parse_string(line, &pos, &out)) {
            return out;
        }
        return NULL;
    }
    if (line[pos] == '\0') {
        return NULL;
    }
    {
        size_t start = pos;
        while (line[pos] != '\0' && line[pos] != ',' && line[pos] != '}') {
            pos++;
        }
        {
            char *out = (char *)malloc(pos - start + 1);
            if (out == NULL) {
                fprintf(stderr, "rb_downloads: out of memory\n");
                exit(1);
            }
            memcpy(out, line + start, pos - start);
            out[pos - start] = '\0';
            return out;
        }
    }
}

static long long rb_dl_field_num(const char *line, const char *key,
                                 long long fallback)
{
    size_t pos;
    long long v = fallback;

    if (!rb_json_find_key(line, key, &pos)) {
        return fallback;
    }
    if (line[pos] == '"') {
        char *s = NULL;
        if (!rb_json_parse_string(line, &pos, &s)) {
            return fallback;
        }
        v = strtoll(s, NULL, 10);
        free(s);
        return v;
    }
    if (!rb_json_parse_number(line, &pos, &v)) {
        return fallback;
    }
    return v;
}

int rb_downloads_load(rb_downloads *d, const char *path)
{
    char buf[RB_DOWNLOADS_LINE];
    FILE *f;
    int rc = 0;

    if (d == NULL || path == NULL) {
        return -1;
    }
    /* Loading replaces the store: a half-merged list would show downloads
     * from two different files. */
    while (d->count > 0) {
        rb_dl_release(&d->items[0]);
        if (d->count > 1) {
            memmove(&d->items[0], &d->items[1],
                    (size_t)(d->count - 1) * sizeof(rb_download));
        }
        d->count--;
    }
    d->next_id = 1;

    f = fopen(path, "rb");
    if (f == NULL) {
        return 0; /* missing file is fine, and leaves the store empty */
    }
    while (fgets(buf, (int)sizeof(buf), f) != NULL) {
        size_t len = strlen(buf);
        char *pid;
        char *url;
        rb_download *slot;

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
        pid = rb_dl_field_str(buf, "profile_id");
        url = rb_dl_field_str(buf, "url");
        if (rb_dl_blank(pid) || rb_dl_blank(url)) {
            free(pid);
            free(url);
            continue; /* a record with no owner or no source is unusable */
        }
        slot = rb_downloads_insert(d, 0);
        slot->id = rb_dl_field_num(buf, "id", 0);
        slot->profile_id = pid;
        slot->url = url;
        slot->file_name = rb_dl_field_str(buf, "file_name");
        if (slot->file_name == NULL) {
            slot->file_name = rb_json_strdup("download");
        }
        slot->mime_type = rb_dl_field_str(buf, "mime_type");
        if (rb_dl_blank(slot->mime_type)) {
            free(slot->mime_type);
            slot->mime_type = rb_json_strdup("application/octet-stream");
        }
        slot->destination = rb_dl_field_str(buf, "destination");
        slot->error = rb_dl_field_str(buf, "error");
        if (slot->destination == NULL) {
            slot->destination = rb_json_strdup("");
        }
        if (slot->error == NULL) {
            slot->error = rb_json_strdup("");
        }
        {
            char *status = rb_dl_field_str(buf, "status");
            slot->status = rb_download_status_parse(status, RB_DL_QUEUED);
            free(status);
        }
        slot->total_bytes = rb_dl_field_num(buf, "total_bytes", -1);
        slot->downloaded_bytes = rb_dl_field_num(buf, "downloaded_bytes", 0);
        slot->created_at = rb_dl_field_num(buf, "created_at", 0);
        slot->completed_at = rb_dl_field_num(buf, "completed_at", 0);

        if (slot->id <= 0) {
            slot->id = d->next_id; /* an id-less row still needs one */
        }
        if (slot->id >= d->next_id) {
            d->next_id = slot->id + 1;
        }
    }
    if (ferror(f)) {
        rc = -1;
    }
    fclose(f);
    return rc;
}
