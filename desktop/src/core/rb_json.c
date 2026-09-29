/*
 * rb_json.c — minimal JSON helpers for Room Browser core.
 * Pure C11; only the C standard library is used.
 *
 * Extracted from the copies that used to live in rb_bookmarks.c and
 * rb_history.c so the three stores behave identically.
 */

#include "rb_json.h"

#include "rb_str.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

char *rb_json_strdup_n(const char *s, size_t n)
{
    char *p = (char *)malloc(n + 1);

    if (p == NULL) {
        fprintf(stderr, "rb_json: out of memory\n");
        exit(1);
    }
    if (n > 0 && s != NULL) {
        memcpy(p, s, n);
    } else {
        n = 0;
    }
    p[n] = '\0';
    return p;
}

char *rb_json_strdup(const char *s)
{
    size_t n;
    char *p;

    if (s == NULL) {
        s = "";
    }
    n = strlen(s) + 1;
    p = (char *)malloc(n);
    if (p == NULL) {
        fprintf(stderr, "rb_json: out of memory\n");
        exit(1);
    }
    memcpy(p, s, n);
    return p;
}

void rb_json_skip_ws(const char *s, size_t *i)
{
    while (s[*i] == ' ' || s[*i] == '\t') {
        (*i)++;
    }
}

static int rb_json_hex_val(char c)
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

static void rb_json_append_utf8(rb_str *b, unsigned cp)
{
    char one[2];

    if (cp > 0x10FFFFu || (cp >= 0xD800u && cp <= 0xDFFFu)) {
        cp = 0xFFFDu; /* out of range / lone surrogate -> replacement char */
    }
    one[1] = '\0';
    if (cp < 0x80u) {
        one[0] = (char)cp;
        rb_str_append(b, one);
    } else if (cp < 0x800u) {
        one[0] = (char)(0xC0u | (cp >> 6));
        rb_str_append(b, one);
        one[0] = (char)(0x80u | (cp & 0x3Fu));
        rb_str_append(b, one);
    } else if (cp < 0x10000u) {
        one[0] = (char)(0xE0u | (cp >> 12));
        rb_str_append(b, one);
        one[0] = (char)(0x80u | ((cp >> 6) & 0x3Fu));
        rb_str_append(b, one);
        one[0] = (char)(0x80u | (cp & 0x3Fu));
        rb_str_append(b, one);
    } else {
        one[0] = (char)(0xF0u | (cp >> 18));
        rb_str_append(b, one);
        one[0] = (char)(0x80u | ((cp >> 12) & 0x3Fu));
        rb_str_append(b, one);
        one[0] = (char)(0x80u | ((cp >> 6) & 0x3Fu));
        rb_str_append(b, one);
        one[0] = (char)(0x80u | (cp & 0x3Fu));
        rb_str_append(b, one);
    }
}

int rb_json_parse_string(const char *s, size_t *i, char **out)
{
    rb_str b;

    *out = NULL;
    if (s[*i] != '"') {
        return 0;
    }
    (*i)++;
    rb_str_init(&b);
    while (s[*i] != '"' && s[*i] != '\0') {
        char c = s[*i];
        if (c == '\\') {
            char e = s[*i + 1];
            switch (e) {
            case '"':
                rb_str_append(&b, "\"");
                break;
            case '\\':
                rb_str_append(&b, "\\");
                break;
            case '/':
                rb_str_append(&b, "/");
                break;
            case 'b':
                rb_str_append(&b, "\b");
                break;
            case 'f':
                rb_str_append(&b, "\f");
                break;
            case 'n':
                rb_str_append(&b, "\n");
                break;
            case 'r':
                rb_str_append(&b, "\r");
                break;
            case 't':
                rb_str_append(&b, "\t");
                break;
            case 'u': {
                /* *i indexes the backslash, so the four hex digits start two
                 * bytes along (after "\u").  Reading from *i + 1 instead
                 * finds the 'u' and rejects every escape. */
                unsigned cp = 0;
                int k;
                for (k = 0; k < 4; k++) {
                    int hv = rb_json_hex_val(s[*i + 2 + (size_t)k]);
                    if (hv < 0) {
                        goto fail;
                    }
                    cp = cp * 16u + (unsigned)hv;
                }
                (*i) += 6;
                /* A high surrogate followed by \uDC00-\uDFFF is one astral
                 * code point; combining it here is what keeps an emoji
                 * written as a surrogate pair from becoming two U+FFFD. */
                if (cp >= 0xD800u && cp <= 0xDBFFu && s[*i] == '\\' &&
                    s[*i + 1] == 'u') {
                    unsigned lo = 0;
                    int ok_lo = 1;
                    for (k = 0; k < 4; k++) {
                        int hv = rb_json_hex_val(s[*i + 2 + (size_t)k]);
                        if (hv < 0) {
                            ok_lo = 0;
                            break;
                        }
                        lo = lo * 16u + (unsigned)hv;
                    }
                    if (ok_lo && lo >= 0xDC00u && lo <= 0xDFFFu) {
                        cp = 0x10000u + ((cp - 0xD800u) << 10) +
                             (lo - 0xDC00u);
                        (*i) += 6;
                    }
                }
                rb_json_append_utf8(&b, cp);
                continue;
            }
            default:
                goto fail;
            }
            (*i) += 2;
        } else {
            char one[2];
            one[0] = c;
            one[1] = '\0';
            rb_str_append(&b, one);
            (*i)++;
        }
    }
    if (s[*i] != '"') {
        goto fail; /* unterminated */
    }
    (*i)++;
    *out = (b.data != NULL) ? b.data : rb_json_strdup("");
    return 1;
fail:
    rb_str_free(&b);
    return 0;
}

int rb_json_parse_number(const char *s, size_t *i, long long *out)
{
    char *end = NULL;
    long long v;

    if (s[*i] == '\0') {
        return 0;
    }
    v = strtoll(s + *i, &end, 10);
    if (end == s + *i) {
        return 0;
    }
    *i = (size_t)(end - s);
    *out = v;
    return 1;
}

int rb_json_parse_bool(const char *s, size_t *i, int *out)
{
    if (strncmp(s + *i, "true", 4) == 0) {
        *i += 4;
        *out = 1;
        return 1;
    }
    if (strncmp(s + *i, "false", 5) == 0) {
        *i += 5;
        *out = 0;
        return 1;
    }
    if (s[*i] == '0' || s[*i] == '1') {
        *out = (s[*i] == '1');
        (*i)++;
        return 1;
    }
    return 0;
}

/* Skips one JSON value starting at *i.  Understands strings (including
 * escapes), nested objects and arrays, and bare literals, so a scan that
 * walks a record cannot be thrown off by punctuation inside a value. */
static void rb_json_skip_value(const char *s, size_t *i)
{
    rb_json_skip_ws(s, i);
    if (s[*i] == '"') {
        (*i)++;
        while (s[*i] != '\0' && s[*i] != '"') {
            if (s[*i] == '\\' && s[*i + 1] != '\0') {
                (*i)++; /* the escaped byte cannot close the string */
            }
            (*i)++;
        }
        if (s[*i] == '"') {
            (*i)++;
        }
        return;
    }
    if (s[*i] == '{' || s[*i] == '[') {
        const char open = s[*i];
        const char close = (open == '{') ? '}' : ']';
        int depth = 0;

        while (s[*i] != '\0') {
            if (s[*i] == '"') {
                (*i)++;
                while (s[*i] != '\0' && s[*i] != '"') {
                    if (s[*i] == '\\' && s[*i + 1] != '\0') {
                        (*i)++;
                    }
                    (*i)++;
                }
                if (s[*i] == '"') {
                    (*i)++;
                }
                continue;
            }
            if (s[*i] == open) {
                depth++;
            } else if (s[*i] == close) {
                depth--;
                if (depth == 0) {
                    (*i)++;
                    return;
                }
            }
            (*i)++;
        }
        return;
    }
    /* number / true / false / null: runs to the next structural byte */
    while (s[*i] != '\0' && s[*i] != ',' && s[*i] != '}' && s[*i] != ']' &&
           s[*i] != ' ' && s[*i] != '\t' && s[*i] != '\r' && s[*i] != '\n') {
        (*i)++;
    }
}

int rb_json_find_key(const char *line, const char *key, size_t *value_pos)
{
    size_t i = 0;

    if (line == NULL || key == NULL) {
        return 0;
    }
    rb_json_skip_ws(line, &i);
    if (line[i] != '{') {
        return 0; /* only a top-level object has keys to find */
    }
    i++;
    for (;;) {
        char *found = NULL;

        rb_json_skip_ws(line, &i);
        if (line[i] != '"') {
            return 0; /* '}' (done, key absent) or malformed */
        }
        if (!rb_json_parse_string(line, &i, &found)) {
            return 0;
        }
        rb_json_skip_ws(line, &i);
        if (line[i] != ':') {
            free(found);
            return 0;
        }
        i++;
        rb_json_skip_ws(line, &i);
        if (strcmp(found, key) == 0) {
            free(found);
            *value_pos = i;
            return 1;
        }
        free(found);
        /* Not ours: step over the value whole, so a key name appearing
         * inside a string value is never mistaken for a real one. */
        rb_json_skip_value(line, &i);
        rb_json_skip_ws(line, &i);
        if (line[i] != ',') {
            return 0;
        }
        i++;
    }
}

char *rb_json_escape(const char *s)
{
    rb_str b;
    size_t i;

    rb_str_init(&b);
    if (s != NULL) {
        for (i = 0; s[i] != '\0'; i++) {
            unsigned char c = (unsigned char)s[i];
            switch (c) {
            case '"':
                rb_str_append(&b, "\\\"");
                break;
            case '\\':
                rb_str_append(&b, "\\\\");
                break;
            case '\n':
                rb_str_append(&b, "\\n");
                break;
            case '\r':
                rb_str_append(&b, "\\r");
                break;
            case '\t':
                rb_str_append(&b, "\\t");
                break;
            case '\b':
                rb_str_append(&b, "\\b");
                break;
            case '\f':
                rb_str_append(&b, "\\f");
                break;
            default:
                if (c < 0x20u) {
                    rb_str_appendf(&b, "\\u%04x", (unsigned)c);
                } else {
                    char one[2];
                    one[0] = (char)c;
                    one[1] = '\0';
                    rb_str_append(&b, one);
                }
            }
        }
    }
    return (b.data != NULL) ? b.data : rb_json_strdup("");
}
