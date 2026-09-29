/*
 * Room Browser (desktop) - Windows entry point.
 *
 * Entry: wWinMain (Unicode). The --version contract is handled BEFORE any
 * window or control is created: it prints exactly one line
 *   "Room Browser <RB_VERSION> (windows x86_64)"
 * to the attached parent console (GUI-subsystem stdout is unreliable, so
 * AttachConsole(ATTACH_PARENT_PROCESS) is used and output falls back to
 * WriteFile UTF-8 for redirected pipes/files) and optionally writes the
 * same line plus a trailing newline to the file given via --out <path>.
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

static int rb_version_requested(int argc, LPWSTR *argv, const wchar_t **out_path)
{
    int i, hit = 0;
    *out_path = NULL;
    for (i = 0; i < argc; i++) {
        if (wcscmp(argv[i], L"--version") == 0) {
            hit = 1;
        } else if (wcscmp(argv[i], L"--out") == 0 && i + 1 < argc) {
            *out_path = argv[i + 1];
            i++;
        }
    }
    return hit;
}

static void rb_version_output(const wchar_t *out_path)
{
    char line[192];
    int n = snprintf(line, sizeof line, "Room Browser %s %s\n",
                     RB_VERSION, RB_PLATFORM_TAG);
    HANDLE out;

    if (n < 0) n = 0;
    if ((size_t)n >= sizeof line) n = (int)sizeof line - 1;

    AttachConsole(ATTACH_PARENT_PROCESS);
    out = GetStdHandle(STD_OUTPUT_HANDLE);
    if (out != NULL && out != INVALID_HANDLE_VALUE) {
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

    if (out_path && out_path[0]) {
        HANDLE f = CreateFileW(out_path, GENERIC_WRITE, 0, NULL,
                               CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
        if (f != INVALID_HANDLE_VALUE) {
            DWORD written = 0;
            WriteFile(f, line, (DWORD)n, &written, NULL);
            CloseHandle(f);
        }
    }
}

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance,
                    PWSTR pCmdLine, int nCmdShow)
{
    App *app = &g_app;
    MSG msg;
    LPWSTR *argv = NULL;
    int argc = 0;
    const wchar_t *out_path = NULL;

    (void)hPrevInstance;
    (void)pCmdLine;

    /* Parse arguments BEFORE creating any window. */
    argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (argv) {
        int version = rb_version_requested(argc, argv, &out_path);
        LocalFree(argv);
        if (version) {
            rb_version_output(out_path);
            return 0;
        }
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
