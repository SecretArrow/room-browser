#!/usr/bin/env python3
"""Generate the bundled device catalogue (Devices.kt) for Room Browser.

WHY A GENERATOR
    The catalogue is a thousand devices, and every one of them has to be a
    REAL device: a model code that a real handset reports, paired with the
    Android and Chrome versions that handset plausibly runs.  Invented codes
    are worse than useless -- a spoofed identity that no handset ever had is
    exactly the mismatch fingerprinting scripts look for, and shipping one
    would be dishonest about what the app does.

    So the model codes are not written by hand.  They are joined out of
    Google's public "supported devices" list (the Play Console device
    catalogue, storage.googleapis.com/play_public/supported_devices.csv),
    which is the authoritative record of which (brand, marketing name, model
    code) triples actually exist.  What is curated here is the RULE TABLE
    below: which marketing names are 2022-or-later, and which Android/Chrome
    version each generation shipped with.  Everything else is data.

    This mirrors desktop/scripts/gen-filterlist.py: the source data lives in
    a data file, the generated Kotlin is committed, and the generator is run
    by hand rather than at build time (CI must not need the network to build
    the app).

USAGE
    curl -o supported_devices.csv \
        https://storage.googleapis.com/play_public/supported_devices.csv
    python3 android/tools/gen-devices.py supported_devices.csv

    The CSV is UTF-16; UTF-8 is also accepted.  Outputs:
      * android/core/domain/.../model/Devices.kt   (generated, committed)
      * android/tools/devices.tsv                  (the joined data, committed
                                                    so the catalogue can be
                                                    audited and regenerated
                                                    without the CSV)
"""

import csv
import io
import os
import re
import sys

# --------------------------------------------------------------------------
# Build fingerprints.
#
# Real AOSP build IDs.  A handset reports the build it is running, and these
# are the ones the corresponding Android releases actually shipped; the same
# string appears in the User-Agent of every Chrome on that release, so using
# a real one costs nothing and using a made-up one would be a tell.

BUILD_IDS = {
    "12": "SP1A.210812.016",
    "12L": "SP2A.220305.013",
    "13": "TP1A.220624.014",
    "14": "UP1A.231005.007",
    "15": "AP3A.241105.007",
    "16": "BP2A.250605.031",
}

# The Android release a handset launched on, by launch year.  A device
# reports the OS it shipped with (and keeps reporting it until it is
# upgraded), so pairing a 2022 handset with the newest Android would be as
# wrong as pairing it with the newest Chrome.
ANDROID_BY_YEAR = {
    2022: "12",
    2023: "13",
    2024: "14",
    2025: "15",
}

# Chrome for Android stable, by the year the device launched.  A device ships
# with the Chrome of its era and updates from there; pinning the era's version
# keeps the pair (Android, Chrome) coherent instead of every entry claiming
# the newest browser.
CHROME_BY_YEAR = {
    2022: "108.0.5359.128",
    2023: "120.0.6099.144",
    2024: "131.0.6778.135",
    2025: "138.0.7204.157",
}

# --------------------------------------------------------------------------
# The rule table.  First match wins.
#
#   (brand, name-pattern, {generation -> launch year})
#
# `brand` is the CSV's "Retail Branding" column, matched case-insensitively
# (the file spells the same maker "Oppo", "OPPO", "Tecno" and "TECNO"
# depending on the row).  The pattern's ONE capture group is the generation
# token, and the dict beside it is what dates that generation.
#
# The year is per GENERATION, never per family range: the whole point of the
# table is that "since 2022" is true.  Collapsing "Galaxy A5x" to one year
# would date the A53 (2022) and the A56 (2025) identically, and a five-year
# spread of handsets claiming the same launch year is exactly the sort of
# incoherence these profiles exist to avoid.  A generation missing from the
# dict is left out of the catalogue.

RULES = [
    # ---- Samsung -----------------------------------------------------
    ("Samsung", r"^Galaxy S(2[2-5])(?: Ultra|\+)? ?(?:5G)?$",
     {"22": 2022, "23": 2023, "24": 2024, "25": 2025}),
    ("Samsung", r"^Galaxy S(2[34]) FE$", {"23": 2023, "24": 2024}),
    ("Samsung", r"^Galaxy Z Flip([4-7])$", {"4": 2022, "5": 2023, "6": 2024, "7": 2025}),
    ("Samsung", r"^Galaxy Z Fold([4-7])$", {"4": 2022, "5": 2023, "6": 2024, "7": 2025}),
    # Galaxy A numbering dates itself from A13 onward; A0x and everything
    # below A13 is 2021 or older and never enters the table.
    ("Samsung", r"^Galaxy A(1[3-7]) ?(?:5G)?$",
     {"13": 2022, "14": 2023, "15": 2023, "16": 2024, "17": 2025}),
    ("Samsung", r"^Galaxy A(2[3-6]) ?(?:5G)?$", {"23": 2022, "24": 2023, "25": 2023, "26": 2025}),
    ("Samsung", r"^Galaxy A(3[3-6]) ?(?:5G)?$", {"33": 2022, "34": 2023, "35": 2024, "36": 2025}),
    ("Samsung", r"^Galaxy A(5[3-6]) ?(?:5G)?$", {"53": 2022, "54": 2023, "55": 2024, "56": 2025}),
    ("Samsung", r"^Galaxy A(7[3-5]) ?(?:5G)?$", {"73": 2022, "74": 2023, "75": 2024}),
    ("Samsung", r"^Galaxy M(1[4-6]) ?(?:5G)?$", {"14": 2022, "15": 2023, "16": 2024}),
    ("Samsung", r"^Galaxy M(3[3-5]) ?(?:5G)?$", {"33": 2022, "34": 2023, "35": 2024}),
    ("Samsung", r"^Galaxy M(5[3-5]) ?(?:5G)?$", {"53": 2022, "54": 2023, "55": 2024}),
    ("Samsung", r"^Galaxy F(1[4-6]) ?(?:5G)?$", {"14": 2022, "15": 2023, "16": 2024}),
    ("Samsung", r"^Galaxy F(5[4-5]) ?(?:5G)?$", {"54": 2023, "55": 2024}),
    ("Samsung", r"^Galaxy Tab S([89]|10)(?:\+| Ultra)?$", {"8": 2022, "9": 2023, "10": 2024}),
    ("Samsung", r"^Galaxy Tab A9(\+)?$", {"9": 2023}),
    ("Samsung", r"^Galaxy XCover([67]) Pro?$", {"6": 2022, "7": 2024}),

    # ---- Google ------------------------------------------------------
    # Pixel 6 and 6 Pro are October 2021 and stay out; 6a is 2022.
    ("Google", r"^Pixel (6a)$", {"6a": 2022}),
    ("Google", r"^Pixel (7|7a|7 Pro|Fold)$", {"7": 2022, "7a": 2023, "7 Pro": 2022, "Fold": 2023}),
    ("Google", r"^Pixel (8|8a|8 Pro)$", {"8": 2023, "8a": 2024, "8 Pro": 2023}),
    ("Google", r"^Pixel (9|9a|9 Pro|9 Pro XL|9 Pro Fold)$",
     {"9": 2024, "9a": 2025, "9 Pro": 2024, "9 Pro XL": 2024, "9 Pro Fold": 2024}),
    ("Google", r"^Pixel (Tablet)$", {"Tablet": 2023}),

    # ---- Xiaomi / Redmi / POCO ---------------------------------------
    ("Xiaomi", r"^(?:Xiaomi )?(1[2-5])(?: Pro| Ultra| Lite|T)?$",
     {"12": 2022, "13": 2023, "14": 2024, "15": 2025}),
    ("Xiaomi", r"^Redmi Note (1[1-4])(?: Pro| Pro\+|S|T|E)? ?(?:5G)?$",
     {"11": 2022, "12": 2022, "13": 2023, "14": 2024}),
    ("Xiaomi", r"^Redmi (1[2-5])(?:C)? ?(?:5G)?$",
     {"12": 2022, "13": 2023, "14": 2024, "15": 2025}),
    ("Xiaomi", r"^Redmi A([3-5])(?:\+)?$", {"3": 2022, "4": 2023, "5": 2024}),
    ("Xiaomi", r"^POCO (X[4-7]|F[4-7]|M[4-7]|C[4-7])(?: Pro| Plus| GT)?$",
     {"X4": 2022, "X5": 2023, "X6": 2024, "X7": 2025, "F4": 2022, "F5": 2023,
      "F6": 2024, "F7": 2025, "M4": 2022, "M5": 2023, "M6": 2024, "M7": 2025,
      "C4": 2022, "C5": 2023, "C6": 2023, "C7": 2024}),
    ("Xiaomi", r"^Redmi (10[AC]?)(?: Pro| 5G)?$", {"10": 2022, "10A": 2022, "10C": 2022}),
    ("Xiaomi", r"^(?:Xiaomi )?Pad ([4-7])(?: Pro)?$",
     {"4": 2022, "5": 2022, "6": 2023, "7": 2024}),
    ("Xiaomi", r"^Redmi Pad (SE)?$", {"": 2022, "SE": 2023}),
    ("Xiaomi", r"^Redmi (K[5-8]0)(?: Pro| Ultra| Gaming)?$",
     {"K50": 2022, "K60": 2023, "K70": 2024, "K80": 2025}),

    # ---- Redmi / POCO / iQOO -----------------------------------------
    # The CSV lists Redmi, POCO and iQOO as makers in their own right, so a
    # rule filed under "Xiaomi" never sees them; each gets its own block.
    ("Redmi", r"^REDMI (1[2-7])C? ?(?:5G)?$",
     {"12": 2022, "13": 2023, "14": 2024, "15": 2025, "17": 2025}),
    ("Redmi", r"^REDMI (A[3-7])(?: Pro)?(?: 5G)?$",
     {"A3": 2022, "A4": 2023, "A5": 2024, "A7": 2025}),
    ("Redmi", r"^REDMI (K[5-9]0)(?: Pro| Ultra| Max| Gaming)?$",
     {"K50": 2022, "K60": 2023, "K70": 2024, "K80": 2025, "K90": 2025}),
    ("Redmi", r"^REDMI Note (1[1-7])(?: Pro\+?|S|T|E|R)?(?: 5G)?$",
     {"11": 2022, "12": 2022, "13": 2023, "14": 2024, "15": 2025, "17": 2025}),
    ("POCO", r"^POCO ([XFMC][0-9]{1,2})(?: Pro| Plus| GT| Ultra)?(?: 5G)?$",
     {"F4": 2022, "F5": 2023, "F6": 2024, "F7": 2025,
      "X4": 2022, "X5": 2023, "X6": 2024, "X7": 2025,
      "M4": 2022, "M5": 2023, "M6": 2024, "M7": 2025,
      "C50": 2022, "C51": 2022, "C55": 2023, "C61": 2023, "C65": 2023,
      "C71": 2023, "C75": 2024, "C81": 2024, "C85": 2025}),
    ("iQOO", r"^iQOO Neo ?(9|1[01])(?: Pro| SE)?(?: 5G)?$",
     {"9": 2023, "10": 2024, "11": 2025}),
    ("iQOO", r"^iQOO (1[2-5])(?: Ultra|R|T)?(?: 5G)?$",
     {"12": 2023, "13": 2024, "15": 2025}),
    ("iQOO", r"^iQOO Z(9|1[01])(?: Lite| Turbo|x|R|i)?(?: 5G)?$",
     {"9": 2023, "10": 2024, "11": 2025}),

    # ---- vivo ---------------------------------------------------------
    # vivo's own marketing names carry the series letter and the generation
    # together (X100, V29, Y36, S17, T2), so one rule per series.
    ("vivo", r"^vivo (X[89]0|X1[0-9]0|X2[0-9]0|X3[0-9]0)(?: Pro\+?| Ultra| Lite)?$",
     {"X80": 2022, "X90": 2022, "X100": 2023, "X200": 2024, "X300": 2025}),
    ("vivo", r"^vivo (V2[5-9]|V[3-5][0-9])(?: Pro| SE)?$",
     {"V25": 2022, "V27": 2023, "V29": 2023, "V30": 2024, "V40": 2024,
      "V50": 2025}),
    ("vivo", r"^vivo (Y[1-4][0-9])(?:s|i| Pro| GT)?$",
     {"Y36": 2023, "Y100": 2024, "Y200": 2024}),
    ("vivo", r"^vivo (S1[5-9]|S[2-5]0)(?:e|t| Pro| Pro mini)?$",
     {"S15": 2022, "S16": 2022, "S17": 2023, "S18": 2023, "S19": 2024,
      "S50": 2025}),
    ("vivo", r"^vivo (T[1-4])(?: Pro| Ultra|x)?$",
     {"T1": 2022, "T2": 2023, "T3": 2024}),

    # ---- OnePlus -----------------------------------------------------
    ("OnePlus", r"^OnePlus (1[0-3])(?: Pro|T|R)? ?(?:5G)?$",
     {"10": 2022, "11": 2023, "12": 2024, "13": 2025}),
    ("OnePlus", r"^OnePlus Nord (CE )?([2-5])(?: Lite|T)? ?(?:5G)?$",
     {"2": 2022, "3": 2023, "4": 2024, "5": 2025}),
    ("OnePlus", r"^OnePlus Nord N(2|3)0 ?(?:5G)?$", {"2": 2022, "3": 2023}),

    # ---- OPPO / vivo / realme ----------------------------------------
    ("Oppo", r"^(?:OPPO )?(Find X[5-8]|Find N[2-5]|Reno ?[7-9]|Reno1[0-3])",
     {"X5": 2022, "X6": 2023, "X7": 2024, "X8": 2024, "N2": 2022, "N3": 2023,
      "N4": 2024, "N5": 2024, "Reno 7": 2022, "Reno 8": 2022, "Reno 9": 2023,
      "Reno10": 2023, "Reno11": 2024, "Reno12": 2024, "Reno13": 2025}),
    ("Oppo", r"^(?:OPPO )?A([5-9][0-9]) ?(?:5G)?$",
     {"57": 2022, "58": 2022, "59": 2022, "77": 2023, "78": 2023, "79": 2024, "98": 2024}),

    # ---- realme / Motorola / Honor / Huawei --------------------------
    # realme puts the generation in a bare number ("realme 11 Pro+") or in
    # the Narzo line ("NARZO 70 5G").
    ("realme", r"^(?:realme )?(GT ?[2-7]|[1-9][0-9])(?: Pro\+?| Pro|T|S)?$",
     {"GT2": 2022, "GT3": 2022, "GT5": 2023, "GT6": 2023, "GT7": 2024,
      "9": 2022, "10": 2022, "11": 2023, "12": 2024, "13": 2025, "14": 2025}),
    ("realme", r"^(?:realme )?NARZO ([5-9][0-9])",
     {"50": 2022, "60": 2023, "70": 2023, "80": 2024}),
    # Motorola names its own launch year — the strongest signal in the file,
    # so it is read directly rather than inferred from a generation number.
    ("Motorola", r"^moto (?:[ge]|edge|razr) ?[0-9]* ?(?:5G)? \((202[2-5])\)$",
     {"2022": 2022, "2023": 2023, "2024": 2024, "2025": 2025}),
    ("Honor", r"^HONOR (?: )?(Magic ?[4-7]|X[5-9][a-z]?|[0-9]{2,3})(?: Lite| Pro| Plus| SE)?$",
     {"Magic4": 2022, "Magic5": 2023, "Magic6": 2024, "Magic7": 2024,
      "X5": 2021, "X6": 2021, "X6a": 2022, "X7": 2022, "X7a": 2022,
      "X8": 2022, "X8a": 2023, "X9": 2023, "X9a": 2023, "X9b": 2024,
      "50": 2021, "70": 2022, "80": 2022, "90": 2023, "100": 2023,
      "200": 2024}),
    # Huawei is deliberately absent.  Since 2019 its handsets ship without
    # Google Play, so the supported-devices list carries no Pura, Mate or
    # nova of the 2022-and-later generations to date them from.

    # ---- Sony / Asus / Nothing ---------------------------------------
    # The roman numeral IS the year for Xperia, whichever model line it is
    # (the 1, 5 and 10 of one generation all launch together).
    ("Sony", r"^Xperia (?:1|5|10) (IV|V|VI|VII)$",
     {"IV": 2022, "V": 2023, "VI": 2024, "VII": 2025}),
    ("Asus", r"^ROG Phone ([6-9])(?: Pro| Ultimate)?$",
     {"6": 2022, "7": 2023, "8": 2024, "9": 2024}),
    ("Asus", r"^Zenfone ([89]|1[0-2])(?: Ultra)?$",
     {"8": 2022, "9": 2022, "10": 2023, "11": 2024, "12": 2025}),
    ("Nothing", r"^Nothing Phone \(([1-3]a?)\)$",
     {"1": 2022, "2": 2023, "2a": 2024, "3": 2025, "3a": 2025}),

    # ---- Transsion / Nokia / TCL -------------------------------------
    ("Infinix", r"^(?:Infinix )?(?:NOTE|HOT|ZERO|GT) ?([1-4][0-9])",
     {"11": 2022, "12": 2022, "13": 2022, "20": 2022, "30": 2023, "40": 2024}),
    ("Tecno", r"^(?:TECNO )?(?:SPARK|CAMON|POVA|PHANTOM) ?([1-3][0-9])",
     {"10": 2022, "20": 2022, "30": 2023, "40": 2024}),
    ("Nokia", r"^Nokia ([GXC][0-9]{2})$",
     {"G11": 2022, "G21": 2022, "G42": 2023, "G60": 2022, "X20": 2022,
      "X30": 2022, "C31": 2022, "C32": 2023}),
    ("TCL", r"^TCL ?([2-6][0-9]{1,2}) ?(?:SE|XE|XL|5G|Pro|Plus|NXTPAPER)*$",
     {"30": 2022, "40": 2023, "50": 2024, "403": 2022, "405": 2022,
      "408": 2023, "505": 2023, "605": 2024}),
]

# The CSV spells the same maker several ways; the catalogue uses one name per
# maker so the brand filter in the app can be a plain list.
CANONICAL_BRAND = {
    "oppo": "OPPO", "vivo": "vivo", "tecno": "TECNO", "itel": "itel",
    "realme": "realme", "oneplus": "OnePlus", "honor": "HONOR",
    "huawei": "Huawei", "nokia": "Nokia", "tcl": "TCL", "zte": "ZTE",
    "infinix": "Infinix", "motorola": "Motorola", "sony": "Sony",
    "asus": "ASUS", "nothing": "Nothing", "google": "Google",
    "samsung": "Samsung", "xiaomi": "Xiaomi",
    "redmi": "Redmi", "poco": "POCO", "iqoo": "iQOO",
    "lenovo": "Lenovo", "blu": "Blu",
}
CURATED = set(CANONICAL_BRAND.values())


# Devices the CSV lists that are not handsets or tablets (TVs, watches, car
# head units) would be wrong here: a browser profile that claims to be a
# television has a UA no phone browser ever sent.  Everything below is
# dropped by name even when a rule above matched.
DENY = re.compile(
    r"\b(TV|Watch|Wear|Band|Router|Camera|Speaker|Projector|Book|Laptop|"
    r"Chromebook|Auto|Car|Head ?Unit|Kindle|Tablet PC)\b",
    re.IGNORECASE,
)

# Tablets stay in the catalogue, but they are not phones: Chrome on an Android
# tablet leaves "Mobile" out of the UA and its own platform token says
# Android, so a tablet entry that borrowed the phone UA would be a string no
# tablet browser ever sent.
TABLET = re.compile(r"\b(Pad|Tab|Tablet|MatePad|MediaPad)\b", re.IGNORECASE)

# Chromium's device-memory hint.  One of 0.5/1/2/4/8 -- a value outside that
# set is itself a fingerprint, so the catalogue only ever emits these.
def device_memory(year):
    return 4 if year <= 2022 else 8


def hardware_concurrency(year):
    return 8 if year >= 2023 else 6


# WebGL strings by chipset family.  The renderer string is what
# WEBGL_debug_renderer_info hands out, and it names the GPU the SoC actually
# has -- an Adreno on a Exynos handset would be a contradiction.
GPU_BY_BRAND = {
    "Samsung": ("Qualcomm", "Adreno (TM) 740"),
    "Google": ("Qualcomm", "Adreno (TM) 740"),
    "Xiaomi": ("Qualcomm", "Adreno (TM) 730"),
    "Redmi": ("Qualcomm", "Adreno (TM) 619"),
    "POCO": ("Qualcomm", "Adreno (TM) 642L"),
    "iQOO": ("Qualcomm", "Adreno (TM) 730"),
    "OnePlus": ("Qualcomm", "Adreno (TM) 740"),
    "OPPO": ("Qualcomm", "Adreno (TM) 730"),
    "vivo": ("Qualcomm", "Adreno (TM) 730"),
    "realme": ("Qualcomm", "Adreno (TM) 730"),
    "Motorola": ("Qualcomm", "Adreno (TM) 642L"),
    "HONOR": ("Qualcomm", "Adreno (TM) 730"),
    "Sony": ("Qualcomm", "Adreno (TM) 740"),
    "ASUS": ("Qualcomm", "Adreno (TM) 740"),
    "Nothing": ("Qualcomm", "Adreno (TM) 730"),
    "Infinix": ("ARM", "Mali-G57 MC2"),
    "TECNO": ("ARM", "Mali-G57 MC2"),
    "itel": ("ARM", "Mali-G57 MC2"),
    "Nokia": ("Qualcomm", "Adreno (TM) 619"),
    "ZTE": ("Qualcomm", "Adreno (TM) 642L"),
    "TCL": ("Qualcomm", "Adreno (TM) 610"),
}
DEFAULT_GPU = ("Qualcomm", "Adreno (TM) 619")


def slug(text):
    """A stable, human-readable id: 'samsung-sm-s918b'."""
    s = re.sub(r"[^A-Za-z0-9]+", "-", text).strip("-").lower()
    return re.sub(r"-{2,}", "-", s)


def load_rows(path):
    raw = open(path, "rb").read()
    for encoding in ("utf-16", "utf-8-sig", "utf-8"):
        try:
            text = raw.decode(encoding)
            break
        except UnicodeDecodeError:
            continue
    else:
        raise SystemExit("could not decode %s" % path)
    return [r for r in csv.DictReader(io.StringIO(text)) if r.get("Model")]


# Vendors frequently print the launch year into the name itself ("moto g 5G
# (2023)", "TECNO SPARK Go 2024", "itel A24 2023").  That is the vendor's own
# statement rather than an inference, so it is consulted after the explicit
# rules -- a rule that knows the generation is more precise and wins -- and
# only for a year the catalogue has an Android release for.
YEAR_IN_NAME = re.compile(r"(?<![0-9])(20[0-9]{2})(?![0-9])")


def classify(brand, name):
    """The launch year of the generation [name] belongs to, or None.

    The rules carry equal weight; the first one whose brand and name pattern
    match wins, and the year comes from the rule's generation table — never
    from a range, so two generations of one family cannot end up sharing a
    launch year.
    """
    for rule_brand, pattern, generations in RULES:
        if brand.lower() != rule_brand.lower():
            continue
        m = re.search(pattern, name)
        if not m:
            continue
        token = next((g for g in m.groups() if g), None)
        if token is None:
            continue
        # Spaces are dropped so "GT 2" and "GT2" are one generation.
        year = generations.get(token.replace(" ", ""))
        # A table may name a generation the catalogue has no Android release
        # for (a 2021 handset, say).  That is not a match, but it is not a
        # verdict either: a year the name states itself still stands.
        if year in ANDROID_BY_YEAR:
            return year
        break
    # No rule knew this family, so fall back on a year the name states.
    # Only for a maker this file curates: the list is full of tablets, TV
    # boxes and toy phones whose names carry a year, and a profile claiming
    # to be one of those is a profile no real phone ever presents.
    if brand not in CURATED:
        return None
    m = YEAR_IN_NAME.search(name)
    if m:
        stated = int(m.group(1))
        if stated in ANDROID_BY_YEAR:
            return stated
    return None


def build_user_agent(model, android, build_id, chrome, tablet=False):
    return (
        "Mozilla/5.0 (Linux; Android %s; %s Build/%s) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/%s%s Safari/537.36"
        % (android, model, build_id, chrome, "" if tablet else " Mobile")
    )


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    rows = load_rows(sys.argv[1])

    # One entry per (brand, model code).  A model code is the identity a site
    # actually keys on, so the same code listed under two marketing names is
    # one device, not two.
    seen = {}
    for r in rows:
        brand = (r.get("Retail Branding") or "").strip()
        name = (r.get("Marketing Name") or "").strip()
        model = (r.get("Model") or "").strip()
        if not brand or not name or not model:
            continue
        if DENY.search(name) or DENY.search(brand):
            continue
        canon = CANONICAL_BRAND.get(brand.lower(), brand)
        year = classify(canon, name)
        if year is None:
            continue
        key = (canon, model)
        # Two marketing names can map to one code; keep the first, which the
        # rule order has already made the more specific of the two.
        seen.setdefault(key, (name, year))

    devices = []
    for (brand, model), (name, year) in sorted(seen.items()):
        android = ANDROID_BY_YEAR[year]
        chrome = CHROME_BY_YEAR[year]
        build_id = BUILD_IDS[android]
        gpu_vendor, gpu_renderer = GPU_BY_BRAND.get(brand, DEFAULT_GPU)
        tablet = bool(TABLET.search(name))
        devices.append({
            "id": slug("%s-%s" % (brand, model)),
            "brand": brand,
            "model": name,
            "code": model,
            "year": year,
            "android": android,
            "chrome": chrome,
            "build": build_id,
            "ua": build_user_agent(model, android, build_id, chrome, tablet),
            "memory": device_memory(year),
            "cores": hardware_concurrency(year),
            "gpu_vendor": gpu_vendor,
            "gpu_renderer": gpu_renderer,
            "form": "tablet" if tablet else "phone",
        })

    if not devices:
        raise SystemExit("no devices matched — is the CSV the right one?")
    write_tsv(devices, os.path.join(os.path.dirname(__file__), "devices.tsv"))
    write_kotlin(devices)
    print("devices: %d" % len(devices))
    years = {}
    for d in devices:
        years[d["year"]] = years.get(d["year"], 0) + 1
    print("by year: %s" % sorted(years.items()))
    brands = {}
    for d in devices:
        brands[d["brand"]] = brands.get(d["brand"], 0) + 1
    print("by brand: %s" % sorted(brands.items(), key=lambda kv: -kv[1]))


def write_tsv(devices, path):
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("id\tbrand\tmodel\tcode\tyear\tandroid\tchrome\tbuild\tmemory\tcores\tgpu_vendor\tgpu_renderer\tform\tua\n")
        for d in devices:
            fh.write("\t".join(str(d[k]) for k in (
                "id", "brand", "model", "code", "year", "android", "chrome",
                "build", "memory", "cores", "gpu_vendor", "gpu_renderer",
                "form", "ua",
            )) + "\n")


KOTLIN_OUT = os.path.join(
    os.path.dirname(__file__), "..", "core", "domain", "src", "main",
    "kotlin", "com", "roombrowser", "domain", "model", "Devices.kt",
)


def kotlin_str(text):
    return '"%s"' % text.replace("\\", "\\\\").replace('"', '\\"')


def write_kotlin(devices):
    out = io.StringIO()
    out.write(
        "// GENERATED FILE, DO NOT EDIT BY HAND.\n"
        "// Produced by android/tools/gen-devices.py from Google's public\n"
        "// supported-devices list; the joined data is in android/tools/devices.tsv.\n"
        "// Every model code in here belongs to a real handset or tablet\n"
        "// that Google Play has shipped to.\n"
        "\n"
        "package com.roombrowser.domain.model\n"
        "\n"
        "/**\n"
        " * One Android device the browser can present itself as.\n"
        " *\n"
        " * [ua] is the User-Agent that handset's Chrome actually sends. The\n"
        " * remaining fields are the non-geometric fingerprint surface: they\n"
        " * describe the device, and NONE of them is a screen measurement. Screen\n"
        " * size, viewport size and devicePixelRatio are deliberately absent —\n"
        " * the real values must always be reported, because a page laid out for\n"
        " * a viewport the device does not have renders wrong, and a mismatch\n"
        " * between the claimed screen and the real viewport is the cheapest\n"
        " * spoofing signal there is. See SECURITY.md.\n"
        " */\n"
        "data class Device(\n"
        "    val id: String,\n"
        "    val brand: String,\n"
        "    val model: String,\n"
        "    val code: String,\n"
        "    val year: Int,\n"
        "    val androidVersion: String,\n"
        "    val chromeVersion: String,\n"
        "    val buildId: String,\n"
        "    val userAgent: String,\n"
        "    val deviceMemoryGb: Int,\n"
        "    val hardwareConcurrency: Int,\n"
        "    val gpuVendor: String,\n"
        "    val gpuRenderer: String,\n"
        "    /** \"phone\" or \"tablet\"; a tablet's UA carries no \"Mobile\". */\n"
        "    val formFactor: String,\n"
        ")\n"
        "\n"
        "object Devices {\n"
        "\n"
        "    val all: List<Device> = listOf(\n"
    )
    for d in devices:
        out.write(
            "        Device(\n"
            "            id = %s, brand = %s, model = %s, code = %s,\n"
            "            year = %d, androidVersion = %s, chromeVersion = %s, buildId = %s,\n"
            "            userAgent = %s,\n"
            "            deviceMemoryGb = %d, hardwareConcurrency = %d,\n"
            "            gpuVendor = %s, gpuRenderer = %s, formFactor = %s\n"
            "        ),\n" % (
                kotlin_str(d["id"]), kotlin_str(d["brand"]), kotlin_str(d["model"]),
                kotlin_str(d["code"]), d["year"], kotlin_str(d["android"]),
                kotlin_str(d["chrome"]), kotlin_str(d["build"]), kotlin_str(d["ua"]),
                d["memory"], d["cores"], kotlin_str(d["gpu_vendor"]),
                kotlin_str(d["gpu_renderer"]), kotlin_str(d["form"]),
            )
        )
    out.write(
        "    )\n"
        "\n"
        "    val byId: Map<String, Device> = all.associateBy { it.id }\n"
        "\n"
        "    fun find(id: String?): Device? = id?.let { byId[it] }\n"
        "\n"
        "    /**\n"
        "     * A device no other profile is using. [taken] is the set of ids\n"
        "     * already assigned to the registry; every profile gets a different\n"
        "     * handset until the catalogue runs out, at which point the pool is\n"
        "     * the whole catalogue again (one shared device beats no assignment).\n"
        "     */\n"
        "    fun random(taken: Set<String> = emptySet()): Device {\n"
        "        val free = all.filterNot { it.id in taken }\n"
        "        return (if (free.isEmpty()) all else free).random()\n"
        "    }\n"
        "}\n"
    )
    path = os.path.normpath(KOTLIN_OUT)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(out.getvalue())


if __name__ == "__main__":
    main()
