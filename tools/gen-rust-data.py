#!/usr/bin/env python3
"""Converts the C11 desktop data tables into Rust source so the Rust core
ships the exact same device catalogue and theme palettes.

Reads (from the repo's desktop/src/core):
  * rb_devices.c  — 1041-machine catalogue (GENERATED file, stays the source
    of truth; gen-devices.py regenerates the C, this script derives Rust)
  * rb_theme.c    — the 18 built-in theme palettes (value-for-value port of
    the Android BuiltInThemes)

Writes:
  * crates/rbcore/src/devices_data.rs
  * crates/rbcore/src/theme_data.rs

Run from anywhere; paths resolve relative to this file's repo root.
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CORE = HERE.parent / "desktop" / "src" / "core"
OUT = HERE.parent / "desktop" / "crates" / "rbcore" / "src"


def parse_devices() -> list[dict]:
    text = (CORE / "rb_devices.c").read_text()
    body = text[text.index("RB_DEVICES[") :]
    body = body[: body.index("};")]
    # Skip past the array's own opening brace so device entries sit at depth 1.
    body = body[body.index("{") + 1 :]

    # Field-split each {...} group on top-level commas (digits inside quoted
    # strings such as "RTX 3060" must never be mistaken for the numeric
    # fields), then classify every field as string or number.
    def split_fields(g: str) -> list[str]:
        fields, cur, in_str = [], [], False
        i = 0
        while i < len(g):
            c = g[i]
            if in_str:
                if c == "\\" and i + 1 < len(g):
                    cur.append(c)
                    i += 1
                    cur.append(g[i])
                else:
                    cur.append(c)
                    if c == '"':
                        in_str = False
            else:
                if c == '"':
                    in_str = True
                    cur.append(c)
                elif c == ",":
                    fields.append("".join(cur).strip())
                    cur = []
                else:
                    cur.append(c)
            i += 1
        if cur:
            fields.append("".join(cur).strip())
        return [f for f in fields if f]

    devices = []
    depth = 0
    cur: list[str] = []
    groups: list[str] = []
    in_str = False
    for ch in body:
        if not in_str and ch == '"':
            in_str = True
            cur.append(ch)
        elif in_str and ch == '"':
            in_str = False
            cur.append(ch)
        elif not in_str and ch == "{":
            depth += 1
            if depth == 1:
                cur = []
                continue
            cur.append(ch)
        elif not in_str and ch == "}":
            depth -= 1
            if depth == 0:
                groups.append("".join(cur))
                continue
            cur.append(ch)
        else:
            cur.append(ch)

    for g in groups:
        fields = split_fields(g)
        if len(fields) != 16:
            continue
        def unq(f: str) -> str:
            return f[1:-1] if f.startswith('"') and f.endswith('"') else f
        devices.append({
            "id": unq(fields[0]), "brand": unq(fields[1]), "model": unq(fields[2]),
            "year": int(fields[3]), "os": unq(fields[4]), "form": unq(fields[5]),
            "arch": unq(fields[6]), "chrome": unq(fields[7]), "gpu_vendor": unq(fields[8]),
            "gpu_renderer": unq(fields[9]), "cores": int(fields[10]),
            "memory": int(fields[11]), "platform": unq(fields[12]),
            "ua_platform": unq(fields[13]), "platform_version": unq(fields[14]),
            "ua": unq(fields[15]),
        })
    return devices


def rs_str(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def emit_devices(devices: list[dict]):
    lines = []
    lines.append("// GENERATED FILE — do not edit by hand.")
    lines.append("// Source: desktop/src/core/rb_devices.c (1041 machines, 2022-2025).")
    lines.append("// Regenerate with: python3 tools/gen-rust-data.py")
    lines.append("")
    lines.append("/// One machine of the device-identity catalogue.")
    lines.append("#[derive(Debug, Clone)]")
    lines.append("pub struct Device {")
    lines.append("    pub id: &'static str,")
    lines.append("    pub brand: &'static str,")
    lines.append("    pub model: &'static str,")
    lines.append("    pub year: i32,")
    lines.append("    pub os: &'static str,")
    lines.append("    pub form: &'static str,")
    lines.append("    pub arch: &'static str,")
    lines.append("    pub chrome: &'static str,")
    lines.append("    pub gpu_vendor: &'static str,")
    lines.append("    pub gpu_renderer: &'static str,")
    lines.append("    pub cores: i32,")
    lines.append("    pub memory: i32,")
    lines.append("    pub platform: &'static str,")
    lines.append("    pub ua_platform: &'static str,")
    lines.append("    pub platform_version: &'static str,")
    lines.append("    pub ua: &'static str,")
    lines.append("}")
    lines.append("")
    lines.append(f"pub const DEVICES: &[Device] = &[")
    for d in devices:
        lines.append(
            "    Device { id: %s, brand: %s, model: %s, year: %d, os: %s, form: %s, arch: %s, chrome: %s, gpu_vendor: %s, gpu_renderer: %s, cores: %d, memory: %d, platform: %s, ua_platform: %s, platform_version: %s, ua: %s },"
            % (
                rs_str(d["id"]), rs_str(d["brand"]), rs_str(d["model"]), d["year"],
                rs_str(d["os"]), rs_str(d["form"]), rs_str(d["arch"]), rs_str(d["chrome"]),
                rs_str(d["gpu_vendor"]), rs_str(d["gpu_renderer"]), d["cores"], d["memory"],
                rs_str(d["platform"]), rs_str(d["ua_platform"]), rs_str(d["platform_version"]),
                rs_str(d["ua"]),
            )
        )
    lines.append("];")
    lines.append("")
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "devices_data.rs").write_text("\n".join(lines) + "\n")
    return len(devices)


def parse_themes() -> list[dict]:
    text = (CORE / "rb_theme.c").read_text()
    body = text[text.index("RB_THEMES[] = {") :]
    body = body[: body.index("};")] + ","
    groups = re.findall(r"\{\s*\"([a-z]+)\",\s*\"([^\"]+)\",(.*?)\}\s*,", body, re.S)
    themes = []
    for tid, name, rest in groups:
        pals = re.findall(r"RB_PAL\((.*?)\)", rest, re.S)
        if len(pals) != 2:
            continue
        def pal(s):
            # The C macro takes 11 explicit tokens and derives the last three:
            # button = primary, icon = text_secondary,
            # selection = primary at 28 % alpha (0x47000000).
            vals = [v.strip() for v in s.split(",") if v.strip().startswith("0x")]
            if len(vals) != 11:
                return None
            bg, sf, alt, pri, sec, tx, tx2, addr, tab, nav, bd = vals
            return [
                bg, sf, alt, pri, sec, tx, tx2, addr, tab, nav,
                pri, bd, tx2,
                f"(({pri} & 0x00FF_FFFF) | 0x4700_0000)",
            ]
        dark = pal(pals[0])
        light = pal(pals[1])
        if dark is None or light is None:
            continue
        grad = re.search(r"RB_GRADIENT_(\w+)", rest)
        nums = re.findall(r"RB_GRADIENT_\w+,\s*(\d+),\s*(\d+),\s*(\d+),\s*(\d+)", rest)
        themes.append({
            "id": tid, "name": name, "dark": dark, "light": light,
            "gradient": (grad.group(1).lower() if grad else "none"),
            "corner": int(nums[0][0]), "transparency": int(nums[0][1]),
            "blur": int(nums[0][2]), "contrast": int(nums[0][3]),
        })
    return themes


def emit_themes(themes: list[dict]):
    lines = []
    lines.append("// GENERATED FILE — do not edit by hand.")
    lines.append("// Source: desktop/src/core/rb_theme.c (the 18 built-in palettes,")
    lines.append("// themselves the value-for-value port of Android BuiltInThemes).")
    lines.append("// Regenerate with: python3 tools/gen-rust-data.py")
    lines.append("")
    lines.append("/// 14-token palette (ARGB). Order: background, surface, surfaceAlt,")
    lines.append("/// primary, secondary, textPrimary, textSecondary, addressBar, tabBar,")
    lines.append("/// navBar, button, border, icon, selection.")
    lines.append("#[derive(Debug, Clone, Copy)]")
    lines.append("pub struct Palette(pub [u32; 14]);")
    lines.append("")
    lines.append("#[derive(Debug, Clone, Copy)]")
    lines.append("pub struct ThemeData {")
    lines.append("    pub id: &'static str,")
    lines.append("    pub name: &'static str,")
    lines.append("    pub dark: Palette,")
    lines.append("    pub light: Palette,")
    lines.append("    pub gradient: &'static str,")
    lines.append("    pub corner_radius: i32,")
    lines.append("    pub transparency: i32,")
    lines.append("    pub blur: i32,")
    lines.append("    pub contrast: i32,")
    lines.append("}")
    lines.append("")
    lines.append("pub const THEMES: &[ThemeData] = &[")
    for t in themes:
        def pal_lit(vals):
            return "Palette([" + ", ".join(vals) + "])"
        lines.append(
            "    ThemeData { id: %s, name: %s, dark: %s, light: %s, gradient: %s, corner_radius: %d, transparency: %d, blur: %d, contrast: %d },"
            % (
                rs_str(t["id"]), rs_str(t["name"]), pal_lit(t["dark"]), pal_lit(t["light"]),
                rs_str(t["gradient"]), t["corner"], t["transparency"], t["blur"], t["contrast"],
            )
        )
    lines.append("];")
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "theme_data.rs").write_text("\n".join(lines) + "\n")
    return len(themes)


def main():
    n_dev = emit_devices(parse_devices())
    n_theme = emit_themes(parse_themes())
    print(f"devices_data.rs: {n_dev} devices")
    print(f"theme_data.rs: {n_theme} themes")
    if n_dev < 1000:
        print("device parse looks wrong (expected ~1041)", file=sys.stderr)
        sys.exit(1)
    if n_theme != 18:
        print("theme parse looks wrong (expected 18)", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
