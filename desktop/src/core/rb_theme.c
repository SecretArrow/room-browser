/*
 * rb_theme.c — per-profile theme system for Room Browser core.
 * Pure C11; only the C standard library is used.
 *
 * The 18 palettes below are a value-for-value port of BuiltInThemes in the
 * Android edition's core/domain theme/ThemeSpec.kt (obsidian, arctic, ocean,
 * emerald, midnight, aurora, sunset, cyber, royal, sakura, forest, aqua,
 * crimson, golden, slate, lavender, coffee, rose), in the same order.
 *
 * The Android builder takes 11 tokens explicitly and derives the remaining
 * three, so RB_PAL does exactly the same at compile time:
 *   button    = primary
 *   icon      = text_secondary
 *   selection = primary at 28 % alpha
 */

#include "rb_theme.h"

#include <stdio.h>
#include <string.h>

/* bg, surface, surfaceAlt, primary, secondary, textPrimary, textSecondary,
 * addressBar, tabBar, navBar, border -> the full 14-token palette. */
#define RB_PAL(bg, sf, alt, pri, sec, tx, tx2, addr, tab, nav, bd)            \
    { (bg), (sf), (alt), (pri), (sec), (tx), (tx2),                           \
      (addr), (tab), (nav), (pri), (bd), (tx2),                               \
      (((pri) & 0x00FFFFFFu) | 0x47000000u) }

/* id, name, DARK, LIGHT, gradient, cornerRadius, transparency, blur, contrast */
static const rb_theme RB_THEMES[] = {
    { "obsidian", "Obsidian",
      RB_PAL(0xFF0B0B0F, 0xFF14141B, 0xFF1D1D27, 0xFFA78BFA, 0xFFC4B5FD,
             0xFFF1F0F7, 0xFFA5A3B4, 0xFF17171F, 0xFF101017, 0xFF0E0E14,
             0xFF2A2A38),
      RB_PAL(0xFFFAFAFC, 0xFFFFFFFF, 0xFFEEEDF4, 0xFF6D28D9, 0xFF8B5CF6,
             0xFF191822, 0xFF5A5870, 0xFFF2F1F8, 0xFFFFFFFF, 0xFFF7F6FB,
             0xFFE3E1EC),
      RB_GRADIENT_NONE, 20, 0, 0, 100 },

    { "arctic", "Arctic",
      RB_PAL(0xFF0E1622, 0xFF16202F, 0xFF1E2A3C, 0xFF7DD3FC, 0xFFBAE6FD,
             0xFFEFF6FC, 0xFF9DB2C6, 0xFF182335, 0xFF111A27, 0xFF101826,
             0xFF27374B),
      RB_PAL(0xFFF4F8FC, 0xFFFFFFFF, 0xFFE8F1F8, 0xFF0284C7, 0xFF38BDF8,
             0xFF12202C, 0xFF516878, 0xFFEDF4FA, 0xFFFFFFFF, 0xFFF0F6FB,
             0xFFD8E6F0),
      RB_GRADIENT_NONE, 20, 0, 0, 100 },

    { "ocean", "Ocean",
      RB_PAL(0xFF0A1929, 0xFF10233A, 0xFF16304C, 0xFF38BDF8, 0xFF60A5FA,
             0xFFEDF5FC, 0xFF93AAC2, 0xFF12283F, 0xFF0D1F33, 0xFF0C1C2E,
             0xFF1F3A57),
      RB_PAL(0xFFF2F7FC, 0xFFFFFFFF, 0xFFE4EFF8, 0xFF0277BD, 0xFF26C6DA,
             0xFF10222F, 0xFF4F6878, 0xFFEBF3F9, 0xFFFFFFFF, 0xFFEFF6FB,
             0xFFD6E5F0),
      RB_GRADIENT_NONE, 20, 0, 0, 100 },

    { "emerald", "Emerald",
      RB_PAL(0xFF07130D, 0xFF0E1F16, 0xFF142A1E, 0xFF34D399, 0xFFA7F3D0,
             0xFFEDF9F2, 0xFF8FAF9E, 0xFF102419, 0xFF0A1A12, 0xFF091710,
             0xFF1D3A2A),
      RB_PAL(0xFFF3FAF6, 0xFFFFFFFF, 0xFFE5F3EB, 0xFF047857, 0xFF10B981,
             0xFF122119, 0xFF54705F, 0xFFEBF6F0, 0xFFFFFFFF, 0xFFEFF8F3,
             0xFFD5E9DC),
      RB_GRADIENT_NONE, 20, 0, 0, 100 },

    { "midnight", "Midnight",
      RB_PAL(0xFF050B18, 0xFF0B1526, 0xFF111F36, 0xFF93C5FD, 0xFF60A5FA,
             0xFFECF3FC, 0xFF8CA1BD, 0xFF0D1A2E, 0xFF081222, 0xFF071020,
             0xFF1A2C49),
      RB_PAL(0xFFF1F5FB, 0xFFFFFFFF, 0xFFE3ECF7, 0xFF1E3A8A, 0xFF3B82F6,
             0xFF101B2A, 0xFF4E5F76, 0xFFEAF1F9, 0xFFFFFFFF, 0xFFEDF3FA,
             0xFFD5DFEC),
      RB_GRADIENT_NONE, 20, 0, 0, 100 },

    { "aurora", "Aurora",
      RB_PAL(0xFF0B0F1E, 0xFF121829, 0xFF192134, 0xFF818CF8, 0xFF34D399,
             0xFFEEF1FB, 0xFF96A0BE, 0xFF141B30, 0xFF0E1322, 0xFF0D1120,
             0xFF222B47),
      RB_PAL(0xFFF5F6FD, 0xFFFFFFFF, 0xFFE9EBFA, 0xFF6366F1, 0xFF10B981,
             0xFF15172A, 0xFF585D7E, 0xFFEEF0FB, 0xFFFFFFFF, 0xFFF1F3FC,
             0xFFDDE0F0),
      RB_GRADIENT_LINEAR, 20, 0, 0, 100 },

    { "sunset", "Sunset",
      RB_PAL(0xFF1A0E0A, 0xFF251510, 0xFF321D15, 0xFFFB923C, 0xFFF87171,
             0xFFFDF2EA, 0xFFC0A18E, 0xFF2A1811, 0xFF20110D, 0xFF1D0F0B,
             0xFF3C241A),
      RB_PAL(0xFFFDF7F2, 0xFFFFFFFF, 0xFFFAEDE2, 0xFFEA580C, 0xFFEF4444,
             0xFF271710, 0xFF7A5C4B, 0xFFFBF0E7, 0xFFFFFFFF, 0xFFFCF5EE,
             0xFFF0DECF),
      RB_GRADIENT_LINEAR, 20, 0, 0, 100 },

    { "cyber", "Cyber",
      RB_PAL(0xFF05070D, 0xFF0A0F1A, 0xFF101726, 0xFF00E5FF, 0xFFF472B6,
             0xFFEAFBFF, 0xFF7E93A6, 0xFF0C1220, 0xFF070C15, 0xFF060A12,
             0xFF12303A),
      RB_PAL(0xFFEAFBF9, 0xFFFFFFFF, 0xFFDCF5F1, 0xFF0891B2, 0xFFC026D3,
             0xFF0A1A1E, 0xFF4E6A6E, 0xFFE2F6F2, 0xFFFFFFFF, 0xFFE8FAF7,
             0xFFC4E8E1),
      RB_GRADIENT_RADIAL, 20, 0, 0, 100 },

    { "royal", "Royal",
      RB_PAL(0xFF0E0A1A, 0xFF160F28, 0xFF1E1536, 0xFFC084FC, 0xFF818CF8,
             0xFFF3EFFB, 0xFFA79BC0, 0xFF191231, 0xFF110C22, 0xFF100B20,
             0xFF2A1F4A),
      RB_PAL(0xFFF8F5FD, 0xFFFFFFFF, 0xFFF0EAFB, 0xFF7C3AED, 0xFF4F46E5,
             0xFF1B152A, 0xFF5D5478, 0xFFF3EEFB, 0xFFFFFFFF, 0xFFF5F1FC,
             0xFFE4DCF2),
      RB_GRADIENT_NONE, 20, 0, 0, 100 },

    { "sakura", "Sakura",
      RB_PAL(0xFF170F13, 0xFF211620, 0xFF2C1E2A, 0xFFF9A8D4, 0xFFFBCFE8,
             0xFFFDF0F6, 0xFFC4A3B4, 0xFF251925, 0xFF1C121B, 0xFF1A1018,
             0xFF382433),
      RB_PAL(0xFFFDF5F8, 0xFFFFFFFF, 0xFFFAE8F0, 0xFFDB2777, 0xFFF472B6,
             0xFF27141D, 0xFF7A5B6A, 0xFFFBEDF3, 0xFFFFFFFF, 0xFFFCF2F6,
             0xFFF2DCE5),
      RB_GRADIENT_NONE, 20, 0, 0, 100 },

    { "forest", "Forest",
      RB_PAL(0xFF0A120A, 0xFF111C11, 0xFF182718, 0xFF7FB069, 0xFFA3B18A,
             0xFFEFF7EA, 0xFF9BAA90, 0xFF142114, 0xFF0D170D, 0xFF0C150C,
             0xFF223322),
      RB_PAL(0xFFF4F8F1, 0xFFFFFFFF, 0xFFE9F1E4, 0xFF3A5A40, 0xFF588157,
             0xFF142014, 0xFF5A6E5C, 0xFFEEF4EA, 0xFFFFFFFF, 0xFFF0F6ED,
             0xFFD9E5D3),
      RB_GRADIENT_NONE, 20, 0, 0, 100 },

    { "aqua", "Aqua",
      RB_PAL(0xFF061418, 0xFF0A1E23, 0xFF0F2930, 0xFF2DD4BF, 0xFF22D3EE,
             0xFFEAFBF9, 0xFF8AA9AB, 0xFF0C232A, 0xFF081A1F, 0xFF07171C,
             0xFF16393F),
      RB_PAL(0xFFF0FBF9, 0xFFFFFFFF, 0xFFE1F5F1, 0xFF0D9488, 0xFF06B6D4,
             0xFF0C2020, 0xFF4E6B68, 0xFFE8F7F3, 0xFFFFFFFF, 0xFFECFAF7,
             0xFFCBE9E2),
      RB_GRADIENT_NONE, 20, 0, 0, 100 },

    { "crimson", "Crimson",
      RB_PAL(0xFF140607, 0xFF1E0B0C, 0xFF281011, 0xFFF87171, 0xFFDC2626,
             0xFFFCF0F0, 0xFFB98D8D, 0xFF22100F, 0xFF180909, 0xFF160808,
             0xFF351718),
      RB_PAL(0xFFFBF4F4, 0xFFFFFFFF, 0xFFF7E9E9, 0xFFB91C1C, 0xFFEF4444,
             0xFF231111, 0xFF775656, 0xFFF8EEEE, 0xFFFFFFFF, 0xFFFAF2F2,
             0xFFF0DCDC),
      RB_GRADIENT_NONE, 20, 0, 0, 100 },

    { "golden", "Golden",
      RB_PAL(0xFF0C0A04, 0xFF151107, 0xFF1F1809, 0xFFF5C542, 0xFFD4AF37,
             0xFFFBF6E7, 0xFFB3A579, 0xFF181307, 0xFF100D05, 0xFF0F0C04,
             0xFF2B2310),
      RB_PAL(0xFFFBF8EE, 0xFFFFFFFF, 0xFFF6EFDA, 0xFFB8860B, 0xFFD4AF37,
             0xFF211C0B, 0xFF776C48, 0xFFF8F2E1, 0xFFFFFFFF, 0xFFFAF5E9,
             0xFFEBE2C4),
      RB_GRADIENT_NONE, 20, 0, 0, 100 },

    { "slate", "Slate",
      RB_PAL(0xFF0F1114, 0xFF171A1F, 0xFF1F232A, 0xFFCBD5E1, 0xFF94A3B8,
             0xFFF1F5F9, 0xFF9AA4B2, 0xFF191D23, 0xFF121519, 0xFF111418,
             0xFF272C34),
      RB_PAL(0xFFF6F7F9, 0xFFFFFFFF, 0xFFEDEFF3, 0xFF475569, 0xFF64748B,
             0xFF151A21, 0xFF5B6572, 0xFFF0F2F5, 0xFFFFFFFF, 0xFFF2F4F7,
             0xFFDFE3E9),
      RB_GRADIENT_NONE, 20, 0, 0, 100 },

    { "lavender", "Lavender",
      RB_PAL(0xFF14121C, 0xFF1C1928, 0xFF252136, 0xFFC4B5FD, 0xFFE9D5FF,
             0xFFF4F1FB, 0xFFABA2C2, 0xFF1F1C2D, 0xFF171523, 0xFF161420,
             0xFF2E2944),
      RB_PAL(0xFFF8F6FD, 0xFFFFFFFF, 0xFFF1EDFB, 0xFF8B5CF6, 0xFFA78BFA,
             0xFF1B172A, 0xFF5E5578, 0xFFF4F0FC, 0xFFFFFFFF, 0xFFF5F2FD,
             0xFFE7E0F2),
      RB_GRADIENT_NONE, 20, 0, 0, 100 },

    { "coffee", "Coffee",
      RB_PAL(0xFF15100B, 0xFF1F1811, 0xFF2A2118, 0xFFD7A86E, 0xFFB08968,
             0xFFFAF3E9, 0xFFB5A389, 0xFF231B13, 0xFF191309, 0xFF171108,
             0xFF362B1D),
      RB_PAL(0xFFFAF6F0, 0xFFFFFFFF, 0xFFF3ECE1, 0xFF8B5E34, 0xFFA47148,
             0xFF231A10, 0xFF7A6A57, 0xFFF7F0E6, 0xFFFFFFFF, 0xFFF8F3EB,
             0xFFEAE0CF),
      RB_GRADIENT_NONE, 20, 0, 0, 100 },

    { "rose", "Rose",
      RB_PAL(0xFF150C10, 0xFF1F1218, 0xFF2A1820, 0xFFFDA4AF, 0xFFFB7185,
             0xFFFCF1F3, 0xFFBE9AA4, 0xFF231519, 0xFF180E13, 0xFF160D11,
             0xFF361F27),
      RB_PAL(0xFFFDF5F6, 0xFFFFFFFF, 0xFFFAE9EC, 0xFFBE123C, 0xFFE11D48,
             0xFF231217, 0xFF78565E, 0xFFFBEDF0, 0xFFFFFFFF, 0xFFFCF1F3,
             0xFFF1DCE1),
      RB_GRADIENT_NONE, 20, 0, 0, 100 }
};

#define RB_THEME_N ((int)(sizeof(RB_THEMES) / sizeof(RB_THEMES[0])))

/* Index of the fallback theme ("obsidian", the Android default). */
#define RB_THEME_DEFAULT_INDEX 0

static const char *const RB_TOKEN_NAMES[RB_TOKEN_COUNT] = {
    "background", "surface", "surfaceAlt", "primary", "secondary",
    "textPrimary", "textSecondary", "addressBar", "tabBar", "navBar",
    "button", "border", "icon", "selection"
};

int rb_theme_count(void)
{
    return RB_THEME_N;
}

const rb_theme *rb_theme_at(int index)
{
    if (index < 0 || index >= RB_THEME_N) {
        return NULL;
    }
    return &RB_THEMES[index];
}

const rb_theme *rb_theme_by_id(const char *id)
{
    int i;

    if (id == NULL || id[0] == '\0') {
        return NULL;
    }
    for (i = 0; i < RB_THEME_N; i++) {
        if (strcmp(RB_THEMES[i].id, id) == 0) {
            return &RB_THEMES[i];
        }
    }
    return NULL;
}

const rb_theme *rb_theme_default(void)
{
    return &RB_THEMES[RB_THEME_DEFAULT_INDEX];
}

const rb_theme *rb_theme_resolve(const char *id)
{
    const rb_theme *t = rb_theme_by_id(id);
    return (t != NULL) ? t : rb_theme_default();
}

rb_theme_colors rb_theme_palette(const rb_theme *t, rb_theme_mode mode,
                                 int system_dark)
{
    rb_theme_colors c;

    if (t == NULL) {
        t = rb_theme_default();
    }
    switch (mode) {
    case RB_THEME_LIGHT:
        c = t->light;
        break;
    case RB_THEME_DARK:
    case RB_THEME_AMOLED:
        c = t->dark;
        break;
    case RB_THEME_AUTO:
    default:
        c = system_dark ? t->dark : t->light;
        break;
    }

    if (mode == RB_THEME_AMOLED) {
        /* AMOLED: true black background and chrome, accents kept. */
        c.background = 0xFF000000u;
        c.surface    = 0xFF060606u;
        c.address_bar = 0xFF000000u;
        c.tab_bar     = 0xFF000000u;
        c.nav_bar     = 0xFF000000u;
    }
    return c;
}

unsigned int rb_theme_color_at(const rb_theme_colors *c, int token)
{
    if (c == NULL) {
        return 0;
    }
    switch (token) {
    case RB_TOKEN_BACKGROUND:     return c->background;
    case RB_TOKEN_SURFACE:        return c->surface;
    case RB_TOKEN_SURFACE_ALT:    return c->surface_alt;
    case RB_TOKEN_PRIMARY:        return c->primary;
    case RB_TOKEN_SECONDARY:      return c->secondary;
    case RB_TOKEN_TEXT_PRIMARY:   return c->text_primary;
    case RB_TOKEN_TEXT_SECONDARY: return c->text_secondary;
    case RB_TOKEN_ADDRESS_BAR:    return c->address_bar;
    case RB_TOKEN_TAB_BAR:        return c->tab_bar;
    case RB_TOKEN_NAV_BAR:        return c->nav_bar;
    case RB_TOKEN_BUTTON:         return c->button;
    case RB_TOKEN_BORDER:         return c->border;
    case RB_TOKEN_ICON:           return c->icon;
    case RB_TOKEN_SELECTION:      return c->selection;
    default:                      return 0;
    }
}

int rb_theme_token_count(void)
{
    return RB_TOKEN_COUNT;
}

const char *rb_theme_token_name(int token)
{
    if (token < 0 || token >= RB_TOKEN_COUNT) {
        return NULL;
    }
    return RB_TOKEN_NAMES[token];
}

void rb_theme_hex(unsigned int argb, char out[8])
{
    if (out == NULL) {
        return;
    }
    snprintf(out, 8, "#%02X%02X%02X",
             (unsigned)((argb >> 16) & 0xFFu),
             (unsigned)((argb >> 8) & 0xFFu),
             (unsigned)(argb & 0xFFu));
}

void rb_theme_rgb(unsigned int argb, int *r, int *g, int *b)
{
    if (r != NULL) {
        *r = (int)((argb >> 16) & 0xFFu);
    }
    if (g != NULL) {
        *g = (int)((argb >> 8) & 0xFFu);
    }
    if (b != NULL) {
        *b = (int)(argb & 0xFFu);
    }
}

const char *rb_theme_mode_name(rb_theme_mode mode)
{
    switch (mode) {
    case RB_THEME_LIGHT:  return "Light";
    case RB_THEME_DARK:   return "Dark";
    case RB_THEME_AMOLED: return "AMOLED";
    case RB_THEME_AUTO:
    default:              return "Auto";
    }
}
