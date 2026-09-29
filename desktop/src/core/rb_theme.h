/*
 * rb_theme.h — per-profile theme system for Room Browser core.
 *
 * Port of the Android edition's RoomThemeSpec / BuiltInThemes
 * (core/domain theme/ThemeSpec.kt): the same 18 built-in themes, the same
 * 14 colour tokens, the same Light/Dark/AMOLED/Auto modes and the same
 * AMOLED override (true-black background and chrome, accents untouched).
 *
 * Every profile owns a complete, independent theme selection — changing one
 * profile's theme can never affect another. The desktop editions persist
 * only the theme *id* per profile; the palette itself is compiled in.
 *
 * Colours are packed 0xAARRGGBB, exactly like the Android ARGB longs, so the
 * values below can be diffed against ThemeSpec.kt one-for-one.
 */

#ifndef RB_THEME_H
#define RB_THEME_H

#ifdef __cplusplus
extern "C" {
#endif

/* How the theme picks between its light and dark palette. */
typedef enum {
    RB_THEME_LIGHT  = 0,
    RB_THEME_DARK   = 1,
    RB_THEME_AMOLED = 2,
    RB_THEME_AUTO   = 3   /* follow the OS */
} rb_theme_mode;

typedef enum {
    RB_GRADIENT_NONE   = 0,
    RB_GRADIENT_LINEAR = 1,
    RB_GRADIENT_RADIAL = 2
} rb_gradient_style;

/* The 14 colour tokens, in the order the Theme Studio lists them. */
typedef enum {
    RB_TOKEN_BACKGROUND = 0,
    RB_TOKEN_SURFACE,
    RB_TOKEN_SURFACE_ALT,
    RB_TOKEN_PRIMARY,
    RB_TOKEN_SECONDARY,
    RB_TOKEN_TEXT_PRIMARY,
    RB_TOKEN_TEXT_SECONDARY,
    RB_TOKEN_ADDRESS_BAR,
    RB_TOKEN_TAB_BAR,
    RB_TOKEN_NAV_BAR,
    RB_TOKEN_BUTTON,
    RB_TOKEN_BORDER,
    RB_TOKEN_ICON,
    RB_TOKEN_SELECTION,
    RB_TOKEN_COUNT
} rb_theme_token;

/* A fully resolved palette (all 14 tokens). */
typedef struct {
    unsigned int background;
    unsigned int surface;
    unsigned int surface_alt;
    unsigned int primary;
    unsigned int secondary;
    unsigned int text_primary;
    unsigned int text_secondary;
    unsigned int address_bar;
    unsigned int tab_bar;
    unsigned int nav_bar;
    unsigned int button;
    unsigned int border;
    unsigned int icon;
    unsigned int selection;
} rb_theme_colors;

/* A built-in theme. The palettes are stored as the 11 base tokens the
 * Android builder takes explicitly; button/icon/selection stay derived
 * (button = primary, icon = text_secondary, selection = 28 % primary). */
typedef struct {
    const char        *id;
    const char        *name;
    rb_theme_colors    dark;    /* expanded at first use */
    rb_theme_colors    light;
    rb_gradient_style  gradient;
    int                corner_radius; /* clamped 4..32 */
    int                transparency;  /* clamped 0..90 */
    int                blur;          /* clamped 0..100 */
    int                contrast;      /* clamped 50..150, 100 = neutral */
} rb_theme;

/* --- registry --- */

int                rb_theme_count(void);
const rb_theme    *rb_theme_at(int index);
const rb_theme    *rb_theme_by_id(const char *id);  /* NULL when unknown */
const rb_theme    *rb_theme_default(void);          /* "obsidian" */
const rb_theme    *rb_theme_resolve(const char *id);/* never NULL */

/* --- colours --- */

/* Resolve the ACTIVE palette. system_dark is consulted only by AUTO.
 * AMOLED forces true black on background + chrome and keeps the accents. */
rb_theme_colors    rb_theme_palette(const rb_theme *t, rb_theme_mode mode,
                                    int system_dark);

/* Colour of one token index (out-of-range -> 0). */
unsigned int       rb_theme_color_at(const rb_theme_colors *c, int token);

/* Token vocabulary, for building settings UIs generically. */
int                rb_theme_token_count(void);          /* == RB_TOKEN_COUNT */
const char        *rb_theme_token_name(int token);      /* NULL when out of range */

/* --- formatting helpers (for the chrome layers) --- */

/* "#RRGGBB" (alpha is dropped: both chrome layers paint opaque surfaces). */
void               rb_theme_hex(unsigned int argb, char out[8]);

/* Splits 0xAARRGGBB into 0..255 components. Any pointer may be NULL. */
void               rb_theme_rgb(unsigned int argb, int *r, int *g, int *b);

/* mode name for UI summaries ("Light"/"Dark"/"AMOLED"/"Auto"). */
const char        *rb_theme_mode_name(rb_theme_mode mode);

#ifdef __cplusplus
}
#endif

#endif /* RB_THEME_H */
