/*
 * Room Browser (desktop) - GTK entry point.
 *
 * The --version contract is handled BEFORE the GTK application starts:
 * it prints exactly one line
 *   "Room Browser <RB_VERSION> (linux x86_64)"
 * plus a trailing newline to stdout and optionally writes the same line
 * to the file given via --out <path>, then exits with status 0.
 *
 * History, bookmarks and settings are loaded from the rb_paths_data_dir()
 * files (history.jsonl / bookmarks.jsonl / settings.txt) before the UI is
 * created and saved on change plus once more when the application shuts
 * down (rb_on_shutdown -> rb_data_shutdown).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "chrome.h"
#include "rb_version.h"

static const char RB_PLATFORM_TAG[] = "(linux x86_64)";

/* Scans argv for --version and --out <path>. Returns 1 when --version was
 * requested. *out_path is NULL unless --out with a following argument was
 * seen (the value is owned by argv, not by us). */
static int rb_args_scan(int argc, char **argv, const char **out_path)
{
    int i, hit = 0;
    *out_path = NULL;
    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--version") == 0) {
            hit = 1;
        } else if (strcmp(argv[i], "--out") == 0 && i + 1 < argc) {
            *out_path = argv[i + 1];
            i++;
        }
    }
    return hit;
}

static void rb_version_output(const char *out_path)
{
    char line[192];
    int n = snprintf(line, sizeof line, "Room Browser %s %s\n",
                     RB_VERSION, RB_PLATFORM_TAG);

    if (n < 0) n = 0;
    if ((size_t)n >= sizeof line) n = (int)sizeof line - 1;

    fputs(line, stdout);
    fflush(stdout);

    if (out_path && out_path[0]) {
        FILE *f = fopen(out_path, "wb");
        if (f) {
            fputs(line, f);
            fclose(f);
        } else {
            fprintf(stderr, "Room Browser: cannot write %s\n", out_path);
        }
    }
}

int main(int argc, char **argv)
{
    App *app = &g_app;
    const char *out_path = NULL;
    int status;

    /* Parse arguments BEFORE any GTK initialization. */
    if (rb_args_scan(argc, argv, &out_path)) {
        rb_version_output(out_path);
        return 0;
    }

    memset(app, 0, sizeof *app);

    if (rb_data_init(app) != 0) {
        fprintf(stderr, "Room Browser: cannot initialize the data directory\n");
        return 1;
    }

    app->app = gtk_application_new("com.roombrowser.Desktop",
                                    G_APPLICATION_FLAGS_NONE);
    g_signal_connect(app->app, "activate", G_CALLBACK(rb_on_activate), app);
    g_signal_connect(app->app, "shutdown", G_CALLBACK(rb_on_shutdown), app);

    /* No command-line arguments are forwarded: Room Browser takes none
     * beyond --version/--out (handled above). */
    status = g_application_run(G_APPLICATION(app->app), 0, NULL);

    g_object_unref(app->app);
    app->app = NULL;

    rb_data_free(app);
    return status;
}
