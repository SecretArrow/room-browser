# Theme System — per-profile theming

Room Browser ships a **per-profile theme system** (schema v4, `profiles.theme_json`).
Every profile owns a **complete, independent theme snapshot** — changing one
profile's theme can never affect another profile, even when both use the same
preset, because "Apply" always writes a full copy onto that profile's row.

## Built-in presets (18, each with hand-tuned Light + Dark palettes)

| Theme | Identity |
|-------|----------|
| **Obsidian** (default) | premium deep dark, violet accent |
| Arctic | clean bright light, ice blue |
| Ocean | modern blue |
| Emerald | premium green |
| Midnight | dark navy |
| Aurora | blue/purple/green gradient |
| Sunset | warm orange/red gradient |
| Cyber | dark neon (cyan/magenta, radial) |
| Royal | elegant purple/blue |
| Sakura | soft pink |
| Forest | deep natural green |
| Aqua | cyan/turquoise |
| Crimson | dark red |
| Golden | black and gold |
| Slate | minimal gray |
| Lavender | soft purple |
| Coffee | warm brown |
| Rose | elegant rose |

## Theme modes

- **Light** / **Dark** — force the respective palette.
- **AMOLED** — dark palette with true-black background + black bars.
- **Auto** — follows the system light/dark appearance.

## Theme Studio (own activity)

Open it from:
- Page menu (⋮) → **Appearance → Theme studio**
- Browser Settings → Profile Settings → Appearance → **Theme studio**
- Profile card menu (launcher screen) → **Theme studio**

Features:
- **Live preview** — a miniature Room Browser re-renders while you edit.
- **Mode**: Light / Dark / AMOLED / Auto.
- **Colors** (per palette, light & dark): background, surface/cards, surface
  alt, primary accent, secondary accent, text, secondary text, address bar,
  tab bar, navigation bar, button, border, icon, selection/highlight.
  Each color opens a picker with curated professional swatches + hex input.
- **Gradient**: none / linear / radial + 6 directions.
- **Shape & feel**: corner radius (4–32 dp), UI transparency (0–90 %),
  blur intensity (0–100 %, real RenderEffect on API 31+), contrast (50–150 %).
- **Presets before applying** — tapping a preset only previews; *Apply*
  (button or FAB) persists to THIS profile.
- **My themes gallery**: save, duplicate, rename, delete custom themes.
- **Reset** to the built-in default.
- **Import / export as JSON** (clipboard + share sheet).

The `:browser` engine process observes the profile row (Room multi-instance
invalidation) and **re-themes itself live** — no restart needed.

## Data model

```
profiles.theme_json  — full RoomThemeSpec snapshot ("" = default Obsidian)
themes               — user-saved custom theme gallery (id, name, spec_json)
Room v3 → v4 migration: ALTER TABLE + CREATE TABLE (additive, lossless)
```

`RoomThemeSpec` JSON is forward-compatible (`ignoreUnknownKeys`) and
validated on decode (malformed input falls back to the default theme).

## Where theming applies

- Whole Material 3 scheme + shapes + typography (all screens, all processes).
- Browser chrome: omnibox pill, glass bottom bar (themed navBar color,
  transparency + blur), tab cards, sheets, settings.
- Launcher profile picker wears the **default profile's theme** as a preview.
- System-bar icon contrast stays in sync with the effective theme.
