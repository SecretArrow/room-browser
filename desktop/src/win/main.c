/*
 * Room Browser (desktop) - Windows entry point.
 *
 * Entry: wWinMain (Unicode). Two arguments are handled BEFORE any window or
 * control is created:
 *
 *   --version   prints exactly one line
 *               "Room Browser <RB_VERSION> (windows x86_64)"
 *               to the attached parent console (GUI-subsystem stdout is
 *               unreliable, so AttachConsole(ATTACH_PARENT_PROCESS) is used
 *               and output falls back to WriteFile UTF-8 for redirected
 *               pipes/files) and optionally writes the same line plus a
 *               trailing newline to the file given via --out <path>.
 *
 *   --selftest  drives the state layer the GUI is built on (profile
 *               registry, per-profile settings, filter engine, theme)
 *               against a private temp data directory and prints a PASS/FAIL
 *               summary. Exits 0 when every check passed, 1 otherwise.
 *
 * Both accept --out <path> so CI can read the result from a file rather than
 * from a console a GUI-subsystem process may not have.
 */
#include <windows.h>
#include <shellapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#include "chrome.h"
#include "webview.h"
#include "rb_version.h"

static const char RB_PLATFORM_TAG[] = "(windows x86_64)";

/* ------------------------------------------------------------------ */
/* Output helpers.
 *
 * A GUI-subsystem process has no console of its own, so both the version
 * line and the self-test summary go through here: attach to the parent
 * console when there is one, otherwise write UTF-8 to whatever stdout has
 * been redirected to. */

static void rb_write_console(const char *line)
{
    HANDLE out;
    size_t n = strlen(line);

    AttachConsole(ATTACH_PARENT_PROCESS);
    out = GetStdHandle(STD_OUTPUT_HANDLE);
    if (out == NULL || out == INVALID_HANDLE_VALUE) return;
    {
        DWORD ft = GetFileType(out) & ~FILE_TYPE_REMOTE;
        DWORD written = 0;
        if (ft == FILE_TYPE_CHAR) {
            wchar_t *wl = rb_utf8_to_wide(line);
            if (wl) {
                WriteConsoleW(out, wl, (DWORD)wcslen(wl), &written, NULL);
                free(wl);
            }
        } else if (ft == FILE_TYPE_DISK || ft == FILE_TYPE_PIPE) {
            WriteFile(out, line, (DWORD)n, &written, NULL);
        }
    }
}

/* Writes `line` to `path`.  Returns 0 on success, 1 when a path was given
 * but could not be written (honest failure: CI keys off the exit code AND
 * the file; a transient AV/Defender handle race on a freshly-linked unsigned
 * exe must not read as OK).  Retries briefly: 5 attempts, 100 ms apart. */
static int rb_write_file(const wchar_t *path, const char *line)
{
    int attempt;
    size_t n = strlen(line);

    if (!path || !path[0]) return 0;
    for (attempt = 0; attempt < 5; attempt++) {
        HANDLE f = CreateFileW(path, GENERIC_WRITE, 0, NULL,
                               CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
        if (f != INVALID_HANDLE_VALUE) {
            DWORD written = 0;
            WriteFile(f, line, (DWORD)n, &written, NULL);
            CloseHandle(f);
            return 0;
        }
        Sleep(100);
    }
    return 1;
}

/* ------------------------------------------------------------------ */
/* Arguments */

typedef struct {
    int version;
    int selftest;
    const wchar_t *out_path;
} RbArgs;

static void rb_parse_args(int argc, LPWSTR *argv, RbArgs *a)
{
    int i;
    memset(a, 0, sizeof *a);
    for (i = 0; i < argc; i++) {
        if (wcscmp(argv[i], L"--version") == 0) {
            a->version = 1;
        } else if (wcscmp(argv[i], L"--selftest") == 0) {
            a->selftest = 1;
        } else if (wcscmp(argv[i], L"--out") == 0 && i + 1 < argc) {
            a->out_path = argv[i + 1];
            i++;
        }
    }
}

static int rb_version_output(const wchar_t *out_path)
{
    char line[192];
    int n = snprintf(line, sizeof line, "Room Browser %s %s\n",
                     RB_VERSION, RB_PLATFORM_TAG);

    if (n < 0) n = 0;
    if ((size_t)n >= sizeof line) n = (int)sizeof line - 1;
    rb_write_console(line);
    return rb_write_file(out_path, line);
}

/* ------------------------------------------------------------------ */
/* --selftest
 *
 * The desktop-windows CI job can otherwise only prove that this layer
 * COMPILES: the GUI needs the WebView2 runtime and an interactive desktop
 * session, neither of which a runner has.  Pointing %APPDATA% at a scratch
 * directory lets the same code paths the window drives be exercised for
 * real, so a regression in them fails the build instead of shipping. */

static int rb_st_total, rb_st_fail;

/* The failing checks are COLLECTED, not merely printed.  CI starts this
 * binary as a detached process (Start-Process), so its stderr never reaches
 * the job log — only the file named by --out does.  A run that said
 * "27 checks, 17 failed" and nothing else is what that costs: seventeen
 * invisible failures that can only be guessed at, one CI cycle each.  The
 * names ride along in the summary instead. */
#define RB_ST_MAX_FAILURES 32
static char rb_st_failures[RB_ST_MAX_FAILURES][160];
static int rb_st_fail_n;

static void rb_st_note_failure(const char *file, int line, const char *expr)
{
    if (rb_st_fail_n >= RB_ST_MAX_FAILURES) {
        return;
    }
    snprintf(rb_st_failures[rb_st_fail_n], sizeof rb_st_failures[0],
             "FAIL %s:%d: %s", file, line, expr);
    rb_st_fail_n++;
}

/* Appends to a NUL-terminated report, truncating rather than overflowing. */
static void rb_st_append(char *buf, size_t cap, size_t *len, const char *s)
{
    size_t n = strlen(s);

    if (*len + 1 >= cap) {
        return;
    }
    if (n > cap - 1 - *len) {
        n = cap - 1 - *len;
    }
    memcpy(buf + *len, s, n);
    *len += n;
    buf[*len] = '\0';
}

#define ST_CHECK(cond)                                                       \
    do {                                                                     \
        rb_st_total++;                                                       \
        if (!(cond)) {                                                       \
            rb_st_fail++;                                                    \
            rb_st_note_failure(__FILE__, __LINE__, #cond);                   \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);  \
        }                                                                    \
    } while (0)

/* Compares a filter engine category for a host. */
#define ST_CAT(host, want) ST_CHECK(rb_filters_blocked_category(app->filters, host) == (want))

static int rb_selftest(const wchar_t *out_path)
{
    App *app = &g_app;
    wchar_t tmp[MAX_PATH];
    char *tmp_u8 = NULL;
    char *saved = NULL;
    const char *old_appdata;
    rb_filter_options fo;
    char *ua;
    int rc;

    rb_st_total = 0;
    rb_st_fail = 0;

    if (GetTempPathW(MAX_PATH, tmp) == 0) {
        fprintf(stderr, "selftest: no temp directory\n");
        return 1;
    }
    if (wcslen(tmp) + 24 >= MAX_PATH) {
        fprintf(stderr, "selftest: temp path too long\n");
        return 1;
    }
    wcscat(tmp, L"roombrowser-selftest");
    tmp_u8 = rb_wide_to_utf8(tmp);
    if (!tmp_u8) return 1;

    /* rb_paths_data_dir() derives the data directory from %APPDATA%, so
     * pointing that at a scratch directory is what keeps this run from
     * touching a real installation.  _putenv_s rather than
     * SetEnvironmentVariableW: getenv() reads the CRT's copy of the
     * environment, which the Win32 call does not update. */
    old_appdata = getenv("APPDATA");
    if (old_appdata) saved = rb_strdup(old_appdata);
    if (_putenv_s("APPDATA", tmp_u8) != 0) {
        free(saved);
        free(tmp_u8);
        return 1;
    }

    memset(app, 0, sizeof *app);

    /* --- the state the chrome is built on --- */
    ST_CHECK(rb_data_init(app) == 0);

    ST_CHECK(rb_profile_count(app->profiles) >= 1);
    ST_CHECK(app->active_profile_id != NULL);
    ST_CHECK(rb_active_profile(app) != NULL);

    /* The compatibility-first 2026-09 defaults, read through the profile the
     * same way every feature reads them. */
    ST_CHECK(rb_pref_int(app, RB_PREF_JAVASCRIPT, -1) == 1);
    ST_CHECK(rb_pref_int(app, RB_PREF_BLOCK_ADS, -1) == 0);
    ST_CHECK(rb_pref_int(app, RB_PREF_BLOCK_TRACKERS, -1) == 0);
    ST_CHECK(rb_pref_int(app, RB_PREF_BLOCK_CROSS_SITE, -1) == 0);
    ST_CHECK(rb_pref_int(app, RB_PREF_BLOCK_POPUPS, -1) == 0);
    ST_CHECK(rb_pref_int(app, RB_PREF_BLOCK_MALICIOUS, -1) == 1);
    ST_CHECK(rb_pref_int(app, RB_PREF_HTTPS_UPGRADE, -1) == 1);

    ST_CHECK(app->home_url != NULL &&
             strncmp(app->home_url, "https://", 8) == 0);

    /* The filter engine actually loaded its bundled list, and classifies the
     * three categories the switches above consult. */
    if (app->filters) {
        ST_CAT("2mdn.net", RB_FILTER_AD);
        ST_CAT("2o7.net", RB_FILTER_TRACKER);
        ST_CAT("amazon-secure-login.com", RB_FILTER_MALICIOUS);
        ST_CAT("example.com", RB_FILTER_NONE);
    } else {
        ST_CHECK(0);
    }

    /* The switches reach the engine. */
    fo = rb_filter_opts(app);
    ST_CHECK(fo.block_ads == 0);
    ST_CHECK(fo.block_malicious == 1);

    ST_CHECK(rb_theme_current(app) != NULL);

    /* Mode "default" means "send no override", which is NULL — not "". */
    ua = rb_ua_current(app);
    ST_CHECK(ua == NULL);
    free(ua);

    /* A preference written through the chrome's own setter reads back, and
     * survives a save/load of the registry. */
    rb_pref_set_int(app, RB_PREF_BLOCK_ADS, 1);
    ST_CHECK(rb_pref_int(app, RB_PREF_BLOCK_ADS, -1) == 1);
    {
        rb_profile_registry *fresh = rb_profile_registry_new();
        ST_CHECK(fresh != NULL);
        if (fresh && app->path_profiles) {
            const rb_profile *p;
            ST_CHECK(rb_profile_registry_load(fresh, app->path_profiles) == 0);
            p = rb_active_profile(app);
            ST_CHECK(p != NULL);
            if (p) {
                const rb_profile *r = rb_profile_by_id(fresh, p->id);
                ST_CHECK(r != NULL);
                if (r && r->settings) {
                    ST_CHECK(rb_settings_get_int(r->settings, RB_PREF_BLOCK_ADS, -1) == 1);
                }
            }
        }
        if (fresh) rb_profile_registry_free(fresh);
    }

    ST_CHECK(app->downloads != NULL);
    ST_CHECK(app->path_profiles != NULL && rb_paths_is_file(app->path_profiles));
    ST_CHECK(app->path_history != NULL);
    ST_CHECK(app->path_bookmarks != NULL);
    ST_CHECK(app->download_dir != NULL && rb_paths_is_dir(app->download_dir));

    rb_data_shutdown(app);
    rc = rb_st_fail;
    rb_data_free(app);

    /* Built while the scratch %APPDATA% is still in place: when a path the
     * state layer stores comes back NULL, the value it was derived from is
     * the first thing anyone reading the log needs to see. */
    {
        char report[4096];
        char line[224];
        size_t used = 0;
        const char *scratch = getenv("APPDATA");
        char *data_dir;
        int i;

        report[0] = '\0';
        snprintf(line, sizeof line, "selftest: %d checks, %d failed\n",
                 rb_st_total, rc);
        rb_st_append(report, sizeof report, &used, line);
        for (i = 0; i < rb_st_fail_n; i++) {
            rb_st_append(report, sizeof report, &used, rb_st_failures[i]);
            rb_st_append(report, sizeof report, &used, "\n");
        }
        if (rc != 0) {
            if (rb_st_fail > rb_st_fail_n) {
                snprintf(line, sizeof line, "... and %d further failures\n",
                         rb_st_fail - rb_st_fail_n);
                rb_st_append(report, sizeof report, &used, line);
            }
            snprintf(line, sizeof line, "APPDATA=%s\n",
                     (scratch && scratch[0]) ? scratch : "(unset)");
            rb_st_append(report, sizeof report, &used, line);
            data_dir = rb_paths_data_dir();
            snprintf(line, sizeof line, "data dir=%s\n",
                     data_dir ? data_dir : "(NULL - could not be created)");
            rb_st_append(report, sizeof report, &used, line);
            if (data_dir) rb_paths_free(data_dir);
        }
        rb_write_console(report);
        if (rb_write_file(out_path, report) != 0) rc = 1;
    }

    if (saved) {
        _putenv_s("APPDATA", saved);
        free(saved);
    } else {
        _putenv_s("APPDATA", "");
    }
    rb_paths_remove_tree(tmp_u8);
    free(tmp_u8);

    return rc ? 1 : 0;
}

/* ------------------------------------------------------------------ */

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance,
                    PWSTR pCmdLine, int nCmdShow)
{
    App *app = &g_app;
    MSG msg;
    LPWSTR *argv = NULL;
    int argc = 0;
    RbArgs args;

    (void)hPrevInstance;
    (void)pCmdLine;

    /* Parse arguments BEFORE creating any window. */
    argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (argv) {
        rb_parse_args(argc, argv, &args);
        if (args.selftest) {
            int rc = rb_selftest(args.out_path);
            LocalFree(argv);
            return rc;
        }
        if (args.version) {
            int rc = rb_version_output(args.out_path);
            LocalFree(argv);
            return rc;
        }
        LocalFree(argv);
    }

    memset(app, 0, sizeof *app);
    app->hinst = hInstance;

    {
        INITCOMMONCONTROLSEX icc;
        icc.dwSize = sizeof icc;
        icc.dwICC = ICC_WIN95_CLASSES | ICC_STANDARD_CLASSES;
        InitCommonControlsEx(&icc);
    }

    if (rb_data_init(app) != 0) return 1;
    if (rb_chrome_create(app) != 0) {
        rb_data_free(app);
        return 1;
    }
    /* If the WebView2 runtime is missing the chrome stays usable. */
    (void)rb_wv_init(app);

    ShowWindow(app->hwnd, nCmdShow);
    UpdateWindow(app->hwnd);

    /* First tab: navigates to the "home" setting once the webview is ready. */
    rb_do_new_tab(app);

    while (GetMessageW(&msg, NULL, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }

    rb_data_free(app);
    return (int)msg.wParam;
}
