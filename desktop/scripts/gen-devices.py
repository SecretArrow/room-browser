#!/usr/bin/env python3
"""gen-devices.py - build the desktop device catalogue for Room Browser.

The Android edition draws its identities from Google's public list of
supported devices (1,021 handsets, see android/tools/gen-devices.py). There
is no equivalent public list for desktops, and far fewer models matter, so
this table is curated by hand: real, shipping machines with the OS each one
actually runs, the GPU it actually ships, and a Chrome release from its
release year.

Provenance, stated plainly, because it decides what may be claimed:

  * brand / model / year - real machines. The names are the ones the
    vendors use.
  * os / gpu / cores / memory - the configuration that machine shipped in.
  * ua - CONSTRUCTED, not captured, from the platform token that OS reports
    and the machine's Chrome major version, in the REDUCED form Chrome has
    sent since 2022: "Chrome/131.0.0.0", never the build number, which lives
    in the client hints instead. No "Mobile" token anywhere - a desktop
    browser introducing itself as a phone would be handed mobile layouts.
  * gpu_vendor / gpu_renderer - CONSTRUCTED in Chrome's ANGLE format for
    each OS: Direct3D11 on Windows, Metal on macOS, OpenGL over Mesa on
    Linux.

So: the machines are real; the strings are shaped like the ones Chrome
produces rather than being captured from a machine of every model. That is
the honest description of this data, and the header it generates says so.

Desktop-device rules this file enforces (the mirror of the Android
edition's phone/tablet rules):
  1. No "Mobile" token in any UA; every machine is a desktop or a laptop.
  2. navigator.platform follows the OS: Win32 / MacIntel / Linux x86_64.
  3. userAgentData.platform follows the OS: Windows / macOS / Linux.
  4. architecture/bitness follow the CPU: Apple Silicon reports arm/64.
  5. deviceMemory never exceeds 8 - Chromium caps the value there, so a
     64 GB workstation honestly reports 8, exactly like a real one.
  6. hardwareConcurrency is the machine's real logical processor count.
  7. No two entries present the same fingerprint. Desktops repeat
     themselves far more than phones do, so the Chrome major varies
     between machines of the same year - which is also what a real
     population of machines looks like.
  8. A machine that cannot be told apart from one already in the table is
     LEFT OUT and named in the output, not shipped as a second name for
     the same identity. Two profiles given such a pair would present the
     same machine, which is the thing the catalogue exists to prevent.
     This is why the table below holds more machines than the catalogue
     does.

Usage:
    desktop/scripts/gen-devices.py desktop/src/core/rb_devices.c

Re-run whenever the table below changes.
"""

import collections
import sys

# Real Chrome stable releases, one per release of each year, in order. A
# machine of a given year may honestly be running any of them, which is what
# keeps two otherwise-identical machines from being the same fingerprint.
# The major line is Chrome's real release cadence; the build number is
# representative of that release rather than a captured string.
CHROME_BY_YEAR = {
    2022: ["97.0.4692.99", "98.0.4758.102", "99.0.4844.84", "100.0.4896.127",
           "101.0.4951.67", "102.0.5005.115", "103.0.5060.134",
           "104.0.5112.102", "105.0.5195.127", "106.0.5249.119",
           "107.0.5304.122", "108.0.5359.128"],
    2023: ["109.0.5414.120", "110.0.5481.178", "111.0.5563.147",
           "112.0.5615.138", "113.0.5672.127", "114.0.5735.199",
           "115.0.5790.171", "116.0.5845.187", "117.0.5938.132",
           "118.0.5993.118", "119.0.6045.160", "120.0.6099.144"],
    2024: ["121.0.6167.160", "122.0.6261.129", "123.0.6312.124",
           "124.0.6367.208", "125.0.6422.142", "126.0.6478.182",
           "127.0.6533.120", "128.0.6613.138", "129.0.6668.101",
           "130.0.6723.117", "131.0.6778.135"],
    2025: ["132.0.6834.160", "133.0.6943.142", "134.0.6998.117",
           "135.0.7049.115", "136.0.7103.114", "137.0.7151.120",
           "138.0.7204.157", "139.0.7258.128", "140.0.7339.127",
           "141.0.7390.122", "142.0.7444.134"],
}


def angle_windows(vendor, gpu):
    """What Chrome reports on Windows: ANGLE over Direct3D 11."""
    return ("Google Inc. (%s)" % vendor,
            "ANGLE (%s, %s Direct3D11 vs_5_0 ps_5_0, D3D11)" % (vendor, gpu))


def angle_metal(vendor, gpu):
    """What Chrome reports on macOS: ANGLE over Metal."""
    return ("Google Inc. (%s)" % vendor,
            "ANGLE (%s, ANGLE Metal Renderer: %s, Unspecified Version)"
            % (vendor, gpu))


def angle_gl(vendor, gpu, mesa):
    """What Chrome reports on Linux: ANGLE over the Mesa OpenGL driver."""
    return ("Google Inc. (%s)" % vendor,
            "ANGLE (%s, %s, OpenGL 4.6 (Core Profile) Mesa %s)"
            % (vendor, gpu, mesa))


UBUNTU_2204 = "23.2.1-1ubuntu3.1"
UBUNTU_2404 = "24.0.9-0ubuntu0.2"

# One entry per GPU a machine below can ship. The three OS columns are the
# same silicon described in each platform's own idiom, which is why they are
# not one string reused three times. None means the pair is not real: no
# Apple GPU runs Windows, no NVIDIA GPU ships in a Mac in this window.
GPUS = {
    "intel-uhd-620": {
        "windows": angle_windows("Intel", "Intel(R) UHD Graphics 620"),
        "linux": angle_gl("Intel", "Mesa Intel(R) UHD Graphics 620 (KBL GT2)",
                          UBUNTU_2204),
    },
    "intel-iris-xe": {
        "windows": angle_windows("Intel", "Intel(R) Iris(R) Xe Graphics"),
        "linux": angle_gl("Intel", "Mesa Intel(R) Iris(R) Xe Graphics (TGL GT2)",
                          UBUNTU_2404),
    },
    "intel-arc-a370m": {
        "windows": angle_windows("Intel", "Intel(R) Arc(TM) A370M Graphics"),
        "linux": angle_gl("Intel", "Mesa Intel(R) Arc(tm) A370M Graphics (DG2)",
                          UBUNTU_2404),
    },
    "intel-iris-plus-655": {
        "macos": angle_metal("Intel", "Intel(R) Iris(TM) Plus Graphics 655"),
    },
    "amd-vega-8": {
        "windows": angle_windows("AMD", "AMD Radeon(TM) Vega 8 Graphics"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon Vega 8 Graphics (raven, LLVM 17.0.6)",
                          UBUNTU_2204),
    },
    "amd-radeon-680m": {
        "windows": angle_windows("AMD", "AMD Radeon(TM) 680M"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon 680M (rembrandt, LLVM 17.0.6)",
                          UBUNTU_2404),
    },
    "amd-radeon-780m": {
        "windows": angle_windows("AMD", "AMD Radeon(TM) 780M"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon 780M (gfx1103_r1, LLVM 17.0.6)",
                          UBUNTU_2404),
    },
    "amd-rx-6600m": {
        "windows": angle_windows("AMD", "AMD Radeon RX 6600M"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon RX 6600M (navi23, LLVM 17.0.6)",
                          UBUNTU_2404),
    },
    "amd-rx-7600s": {
        "windows": angle_windows("AMD", "AMD Radeon RX 7600S"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon RX 7600S (navi33, LLVM 17.0.6)",
                          UBUNTU_2404),
    },
    "nvidia-gtx-1650": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce GTX 1650"),
        "linux": angle_gl("NVIDIA", "Mesa NVIDIA GeForce GTX 1650 (nvidia)",
                          UBUNTU_2204),
    },
    "nvidia-rtx-3050": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 3050 Laptop GPU"),
        "linux": angle_gl("NVIDIA", "Mesa NVIDIA GeForce RTX 3050 Laptop GPU (nvidia)",
                          UBUNTU_2404),
    },
    "nvidia-rtx-3060": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 3060 Laptop GPU"),
        "linux": angle_gl("NVIDIA", "Mesa NVIDIA GeForce RTX 3060 Laptop GPU (nvidia)",
                          UBUNTU_2204),
    },
    "nvidia-rtx-3070": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 3070 Laptop GPU"),
    },
    "nvidia-rtx-3070-ti": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 3070 Ti Laptop GPU"),
        "linux": angle_gl("NVIDIA", "Mesa NVIDIA GeForce RTX 3070 Ti Laptop GPU (nvidia)",
                          UBUNTU_2204),
    },
    "nvidia-rtx-3080-ti": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 3080 Ti Laptop GPU"),
    },
    "nvidia-rtx-a2000": {
        "windows": angle_windows("NVIDIA", "NVIDIA RTX A2000 Laptop GPU"),
        "linux": angle_gl("NVIDIA", "Mesa NVIDIA RTX A2000 Laptop GPU (nvidia)",
                          UBUNTU_2204),
    },
    "nvidia-rtx-4050": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 4050 Laptop GPU"),
        "linux": angle_gl("NVIDIA", "Mesa NVIDIA GeForce RTX 4050 Laptop GPU (nvidia)",
                          UBUNTU_2404),
    },
    "nvidia-rtx-4060": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 4060 Laptop GPU"),
        "linux": angle_gl("NVIDIA", "Mesa NVIDIA GeForce RTX 4060 Laptop GPU (nvidia)",
                          UBUNTU_2404),
    },
    "nvidia-rtx-4070": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 4070 Laptop GPU"),
        "linux": angle_gl("NVIDIA", "Mesa NVIDIA GeForce RTX 4070 Laptop GPU (nvidia)",
                          UBUNTU_2404),
    },
    "nvidia-rtx-4080": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 4080 Laptop GPU"),
        "linux": angle_gl("NVIDIA", "Mesa NVIDIA GeForce RTX 4080 Laptop GPU (nvidia)",
                          UBUNTU_2404),
    },
    "nvidia-rtx-4090": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 4090 Laptop GPU"),
        "linux": angle_gl("NVIDIA", "Mesa NVIDIA GeForce RTX 4090 Laptop GPU (nvidia)",
                          UBUNTU_2404),
    },
    "apple-m1": {"macos": angle_metal("Apple", "Apple M1")},
    "apple-m2": {"macos": angle_metal("Apple", "Apple M2")},
    "apple-m2-pro": {"macos": angle_metal("Apple", "Apple M2 Pro")},
    "apple-m2-max": {"macos": angle_metal("Apple", "Apple M2 Max")},
    "apple-m2-ultra": {"macos": angle_metal("Apple", "Apple M2 Ultra")},
    "apple-m3": {"macos": angle_metal("Apple", "Apple M3")},
    "apple-m3-pro": {"macos": angle_metal("Apple", "Apple M3 Pro")},
    "apple-m3-max": {"macos": angle_metal("Apple", "Apple M3 Max")},
    "apple-m3-ultra": {"macos": angle_metal("Apple", "Apple M3 Ultra")},
    "apple-m4": {"macos": angle_metal("Apple", "Apple M4")},
    "apple-m4-pro": {"macos": angle_metal("Apple", "Apple M4 Pro")},
    "apple-m4-max": {"macos": angle_metal("Apple", "Apple M4 Max")},
}

# brand, model, year, os, form, arch, gpu, cores, memory_gb
#
# memory_gb is the RAM the machine shipped with, recorded honestly; the
# value REPORTED is min(that, 8), because Chromium caps deviceMemory at 8.
MACHINES = [
    # ---------------- Apple ----------------
    ("Apple", "MacBook Air (M2, 2022)", 2022, "macos", "laptop", "arm", "apple-m2", 8, 8),
    ("Apple", "MacBook Air 15 (M2, 2023)", 2023, "macos", "laptop", "arm", "apple-m2", 8, 8),
    ("Apple", "MacBook Air 13 (M3, 2024)", 2024, "macos", "laptop", "arm", "apple-m3", 8, 8),
    ("Apple", "MacBook Air 15 (M3, 2024)", 2024, "macos", "laptop", "arm", "apple-m3", 8, 16),
    ("Apple", "MacBook Air 13 (M4, 2025)", 2025, "macos", "laptop", "arm", "apple-m4", 10, 16),
    ("Apple", "MacBook Air 15 (M4, 2025)", 2025, "macos", "laptop", "arm", "apple-m4", 10, 16),
    ("Apple", "MacBook Pro 13 (M2, 2022)", 2022, "macos", "laptop", "arm", "apple-m2", 8, 8),
    ("Apple", "MacBook Pro 14 (M2 Pro, 2023)", 2023, "macos", "laptop", "arm", "apple-m2-pro", 10, 16),
    ("Apple", "MacBook Pro 16 (M2 Max, 2023)", 2023, "macos", "laptop", "arm", "apple-m2-max", 12, 32),
    ("Apple", "MacBook Pro 14 (M3, 2023)", 2023, "macos", "laptop", "arm", "apple-m3", 8, 8),
    ("Apple", "MacBook Pro 14 (M3 Pro, 2023)", 2023, "macos", "laptop", "arm", "apple-m3-pro", 11, 18),
    ("Apple", "MacBook Pro 16 (M3 Max, 2023)", 2023, "macos", "laptop", "arm", "apple-m3-max", 14, 36),
    ("Apple", "MacBook Pro 14 (M4, 2024)", 2024, "macos", "laptop", "arm", "apple-m4", 10, 16),
    ("Apple", "MacBook Pro 14 (M4 Pro, 2024)", 2024, "macos", "laptop", "arm", "apple-m4-pro", 12, 24),
    ("Apple", "MacBook Pro 16 (M4 Max, 2024)", 2024, "macos", "laptop", "arm", "apple-m4-max", 16, 48),
    ("Apple", "iMac 24 (M3, 2023)", 2023, "macos", "desktop", "arm", "apple-m3", 8, 8),
    ("Apple", "iMac 24 (M4, 2024)", 2024, "macos", "desktop", "arm", "apple-m4", 10, 16),
    ("Apple", "Mac mini (M2, 2023)", 2023, "macos", "desktop", "arm", "apple-m2", 8, 8),
    ("Apple", "Mac mini (M2 Pro, 2023)", 2023, "macos", "desktop", "arm", "apple-m2-pro", 10, 16),
    ("Apple", "Mac mini (M4, 2024)", 2024, "macos", "desktop", "arm", "apple-m4", 10, 16),
    ("Apple", "Mac mini (M4 Pro, 2024)", 2024, "macos", "desktop", "arm", "apple-m4-pro", 12, 24),
    ("Apple", "Mac Studio (M2 Max, 2023)", 2023, "macos", "desktop", "arm", "apple-m2-max", 12, 32),
    ("Apple", "Mac Studio (M2 Ultra, 2023)", 2023, "macos", "desktop", "arm", "apple-m2-ultra", 24, 64),
    ("Apple", "Mac Studio (M3 Ultra, 2025)", 2025, "macos", "desktop", "arm", "apple-m3-ultra", 28, 96),
    ("Apple", "Mac Pro (M2 Ultra, 2023)", 2023, "macos", "desktop", "arm", "apple-m2-ultra", 24, 64),

    # ---------------- Dell ----------------
    ("Dell", "XPS 13 Plus 9320", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Dell", "XPS 13 9315", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "XPS 13 9340", 2024, "windows", "laptop", "x86", "intel-arc-a370m", 12, 16),
    ("Dell", "XPS 15 9520", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 14, 16),
    ("Dell", "XPS 15 9530", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 14, 16),
    ("Dell", "XPS 17 9720", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Dell", "XPS 17 9730", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 32),
    ("Dell", "XPS 14 9440", 2024, "windows", "laptop", "x86", "nvidia-rtx-4050", 12, 16),
    ("Dell", "XPS 16 9640", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 32),
    ("Dell", "Inspiron 14 5430", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Inspiron 15 3530", 2023, "windows", "laptop", "x86", "intel-uhd-620", 6, 8),
    ("Dell", "Inspiron 16 5630", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Latitude 5430", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Latitude 7430", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Dell", "Latitude 9430", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Dell", "Latitude 5440", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Latitude 7440", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Dell", "Latitude 9440", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 32),
    ("Dell", "Precision 5570", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 32),
    ("Dell", "Precision 5680", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Dell", "Precision 7770", 2022, "windows", "laptop", "x86", "nvidia-rtx-4080", 16, 64),
    ("Dell", "Alienware m15 R7", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Dell", "Alienware m16", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Dell", "Alienware m18", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("Dell", "Alienware x14", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Dell", "Alienware x16", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 16, 32),
    ("Dell", "G15 5520", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 12, 16),
    ("Dell", "G16 7630", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 16),
    ("Dell", "OptiPlex 7010", 2022, "windows", "desktop", "x86", "intel-uhd-620", 8, 16),
    ("Dell", "Precision 3660 Tower", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060", 12, 32),
    ("Dell", "XPS 13 9315", 2022, "linux", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "XPS 15 9530", 2023, "linux", "laptop", "x86", "nvidia-rtx-4050", 14, 16),
    ("Dell", "Latitude 7440", 2023, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Dell", "Precision 5570", 2022, "linux", "laptop", "x86", "nvidia-rtx-3060", 14, 32),

    # ---------------- HP ----------------
    ("HP", "Spectre x360 13.5", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "Spectre x360 14", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("HP", "Spectre x360 16", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 14, 16),
    ("HP", "Envy 13", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "Envy x360 15", 2022, "windows", "laptop", "x86", "amd-vega-8", 8, 16),
    ("HP", "Envy 16", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 16),
    ("HP", "Pavilion 15", 2022, "windows", "laptop", "x86", "intel-uhd-620", 6, 8),
    ("HP", "Pavilion Plus 14", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "EliteBook 840 G9", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("HP", "EliteBook 840 G10", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("HP", "EliteBook x360 1040 G9", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("HP", "ProBook 450 G9", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "ProBook 450 G10", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "OMEN 16", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("HP", "OMEN 17", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070", 16, 16),
    ("HP", "OMEN Transcend 16", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 16),
    ("HP", "Victus 15", 2022, "windows", "laptop", "x86", "nvidia-gtx-1650", 8, 8),
    ("HP", "Victus 16", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 12, 16),
    ("HP", "ZBook Studio G9", 2022, "windows", "laptop", "x86", "nvidia-rtx-a2000", 14, 32),
    ("HP", "ZBook Fury 16 G9", 2022, "windows", "laptop", "x86", "nvidia-rtx-4080", 16, 64),
    ("HP", "EliteDesk 800 G9", 2022, "windows", "desktop", "x86", "intel-uhd-620", 12, 16),
    ("HP", "ProDesk 400 G9", 2022, "windows", "desktop", "x86", "intel-uhd-620", 8, 8),
    ("HP", "EliteBook 840 G9", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("HP", "Dev One", 2022, "linux", "laptop", "x86", "amd-radeon-680m", 8, 16),
    ("HP", "ZBook Studio G9", 2022, "linux", "laptop", "x86", "nvidia-rtx-a2000", 14, 32),

    # ---------------- Lenovo ----------------
    ("Lenovo", "ThinkPad X1 Carbon Gen 10", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "ThinkPad X1 Carbon Gen 11", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "ThinkPad X1 Carbon Gen 12", 2024, "windows", "laptop", "x86", "intel-arc-a370m", 14, 32),
    ("Lenovo", "ThinkPad X1 Yoga Gen 7", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "ThinkPad X1 Yoga Gen 8", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "ThinkPad X1 Extreme Gen 5", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 32),
    ("Lenovo", "ThinkPad T14 Gen 3", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "ThinkPad T14 Gen 4", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "ThinkPad T14s Gen 4", 2023, "windows", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Lenovo", "ThinkPad T16 Gen 2", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "ThinkPad L14 Gen 3", 2022, "windows", "laptop", "x86", "intel-uhd-620", 8, 8),
    ("Lenovo", "ThinkPad L15 Gen 3", 2022, "windows", "laptop", "x86", "intel-uhd-620", 8, 8),
    ("Lenovo", "ThinkPad P16 Gen 1", 2022, "windows", "laptop", "x86", "nvidia-rtx-4080", 16, 64),
    ("Lenovo", "ThinkPad P1 Gen 5", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 32),
    ("Lenovo", "ThinkPad E14 Gen 4", 2022, "windows", "laptop", "x86", "intel-iris-xe", 8, 8),
    ("Lenovo", "ThinkPad E15 Gen 4", 2022, "windows", "laptop", "x86", "intel-iris-xe", 8, 16),
    ("Lenovo", "ThinkBook 14 G4+", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "ThinkBook 16p G3", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Lenovo", "IdeaPad Slim 5", 2023, "windows", "laptop", "x86", "amd-radeon-680m", 8, 16),
    ("Lenovo", "IdeaPad Flex 5", 2022, "windows", "laptop", "x86", "amd-vega-8", 6, 8),
    ("Lenovo", "IdeaPad Gaming 3", 2022, "windows", "laptop", "x86", "nvidia-gtx-1650", 8, 8),
    ("Lenovo", "Yoga 7i", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "Yoga 9i", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "Yoga Slim 7i Pro", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "Legion 5 Pro", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 16, 16),
    ("Lenovo", "Legion 5i Pro Gen 7", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 16, 16),
    ("Lenovo", "Legion 7i Gen 7", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 16, 32),
    ("Lenovo", "Legion Slim 5", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 16),
    ("Lenovo", "Legion Pro 5i Gen 8", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 32),
    ("Lenovo", "Legion Pro 7i Gen 8", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("Lenovo", "LOQ 15", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 12, 16),
    ("Lenovo", "ThinkCentre M70q Gen 3", 2022, "windows", "desktop", "x86", "intel-uhd-620", 8, 16),
    ("Lenovo", "ThinkCentre M90t Gen 3", 2022, "windows", "desktop", "x86", "intel-uhd-620", 12, 16),
    ("Lenovo", "ThinkPad X1 Carbon Gen 10", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "ThinkPad X1 Carbon Gen 11", 2023, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "ThinkPad T14 Gen 3", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "ThinkPad T14s Gen 4", 2023, "linux", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Lenovo", "ThinkPad P1 Gen 5", 2022, "linux", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 32),
    ("Lenovo", "ThinkPad X1 Yoga Gen 7", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),

    # ---------------- ASUS ----------------
    ("ASUS", "Zenbook 14 OLED", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("ASUS", "Zenbook 14X OLED", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("ASUS", "Zenbook S 13 OLED", 2022, "windows", "laptop", "x86", "amd-radeon-680m", 8, 16),
    ("ASUS", "Zenbook 14 OLED", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("ASUS", "Zenbook Duo", 2024, "windows", "laptop", "x86", "intel-arc-a370m", 16, 32),
    ("ASUS", "Vivobook 15", 2022, "windows", "laptop", "x86", "intel-uhd-620", 8, 8),
    ("ASUS", "Vivobook S 15 OLED", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("ASUS", "Vivobook 16", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("ASUS", "Vivobook Pro 15", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 14, 16),
    ("ASUS", "ROG Zephyrus G14", 2022, "windows", "laptop", "x86", "amd-rx-6600m", 16, 16),
    ("ASUS", "ROG Zephyrus G15", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 16, 16),
    ("ASUS", "ROG Zephyrus G16", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 16),
    ("ASUS", "ROG Zephyrus M16", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 16, 16),
    ("ASUS", "ROG Strix G15", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 16, 16),
    ("ASUS", "ROG Strix G17", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070", 16, 16),
    ("ASUS", "ROG Strix Scar 15", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 16, 32),
    ("ASUS", "ROG Flow X13", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 16, 16),
    ("ASUS", "ROG Flow Z13", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 14, 16),
    ("ASUS", "ROG Ally", 2023, "windows", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("ASUS", "TUF Gaming A15", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 16, 16),
    ("ASUS", "TUF Gaming F15", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 12, 16),
    ("ASUS", "TUF Gaming A16", 2023, "windows", "laptop", "x86", "amd-rx-7600s", 16, 16),
    ("ASUS", "TUF Gaming F17", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 16),
    ("ASUS", "ProArt Studiobook 16 OLED", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 16, 32),
    ("ASUS", "ProArt P16", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("ASUS", "ExpertBook B9", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("ASUS", "Zenbook 14 OLED", 2023, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("ASUS", "ROG Zephyrus G14", 2022, "linux", "laptop", "x86", "amd-rx-6600m", 16, 16),
    ("ASUS", "Vivobook 15", 2022, "linux", "laptop", "x86", "intel-uhd-620", 8, 8),

    # ---------------- Acer ----------------
    ("Acer", "Aspire 5", 2022, "windows", "laptop", "x86", "intel-uhd-620", 8, 8),
    ("Acer", "Aspire 7", 2022, "windows", "laptop", "x86", "nvidia-gtx-1650", 12, 16),
    ("Acer", "Aspire Vero", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Acer", "Swift 3", 2022, "windows", "laptop", "x86", "intel-iris-xe", 8, 16),
    ("Acer", "Swift Go 14", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Acer", "Swift X 14", 2023, "windows", "laptop", "x86", "nvidia-rtx-3050", 12, 16),
    ("Acer", "Nitro 5", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 12, 16),
    ("Acer", "Nitro 16", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 14, 16),
    ("Acer", "Nitro V 15", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 10, 16),
    ("Acer", "Predator Helios 300", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Acer", "Predator Helios 16", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Acer", "Predator Helios 18", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("Acer", "Predator Triton 300 SE", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Acer", "TravelMate P2", 2022, "windows", "laptop", "x86", "intel-uhd-620", 8, 8),
    ("Acer", "TravelMate P4", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Acer", "Swift 3", 2022, "linux", "laptop", "x86", "intel-iris-xe", 8, 16),
    ("Acer", "Aspire 5", 2022, "linux", "laptop", "x86", "intel-uhd-620", 8, 8),

    # ---------------- MSI ----------------
    ("MSI", "Modern 14", 2022, "windows", "laptop", "x86", "intel-iris-xe", 8, 16),
    ("MSI", "Modern 15", 2022, "windows", "laptop", "x86", "intel-iris-xe", 8, 16),
    ("MSI", "Prestige 14 Evo", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("MSI", "Prestige 16 Studio", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 32),
    ("MSI", "Katana GF66", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 12, 16),
    ("MSI", "Katana 15", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 12, 16),
    ("MSI", "Sword 15", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 12, 16),
    ("MSI", "Pulse GL66", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("MSI", "Crosshair 15", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("MSI", "Crosshair 16", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 16),
    ("MSI", "Stealth 15M", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("MSI", "Stealth 16 Studio", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("MSI", "Stealth 14 Studio", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 16),
    ("MSI", "Raider GE66", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 16, 32),
    ("MSI", "Raider GE68 HX", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("MSI", "Raider GE78 HX", 2023, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 64),
    ("MSI", "Titan GT77", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 24, 64),
    ("MSI", "Titan 18 HX", 2024, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 128),
    ("MSI", "Creator Z16", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 16, 32),
    ("MSI", "CreatorPro X17", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 16, 64),
    ("MSI", "Summit E14", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("MSI", "Modern 14", 2022, "linux", "laptop", "x86", "intel-iris-xe", 8, 16),
    ("MSI", "Prestige 14 Evo", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),

    # ---------------- Microsoft ----------------
    ("Microsoft", "Surface Laptop 5", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Microsoft", "Surface Laptop Studio 2", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 14, 16),
    ("Microsoft", "Surface Laptop 6", 2024, "windows", "laptop", "x86", "intel-arc-a370m", 14, 16),
    ("Microsoft", "Surface Pro 9", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 8),
    ("Microsoft", "Surface Pro 10", 2024, "windows", "laptop", "x86", "intel-arc-a370m", 12, 16),
    ("Microsoft", "Surface Go 4", 2023, "windows", "laptop", "x86", "intel-uhd-620", 4, 8),
    ("Microsoft", "Surface Laptop Go 3", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 8),
    ("Microsoft", "Surface Studio 2+", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060", 8, 32),

    # ---------------- Samsung ----------------
    ("Samsung", "Galaxy Book2 Pro", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Samsung", "Galaxy Book3 Pro", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Samsung", "Galaxy Book3 Ultra", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 16, 16),
    ("Samsung", "Galaxy Book4 Pro", 2024, "windows", "laptop", "x86", "intel-arc-a370m", 14, 16),
    ("Samsung", "Galaxy Book4 Ultra", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Samsung", "Galaxy Book2 Pro", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),

    # ---------------- Razer ----------------
    ("Razer", "Blade 14", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Razer", "Blade 15", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 16),
    ("Razer", "Blade 17", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 14, 32),
    ("Razer", "Blade 16", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("Razer", "Blade 18", 2023, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 32),
    ("Razer", "Blade 14", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Razer", "Blade 16", 2024, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("Razer", "Book 13", 2022, "windows", "laptop", "x86", "intel-iris-xe", 8, 16),
    ("Razer", "Blade 15", 2022, "linux", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 16),

    # ---------------- LG ----------------
    ("LG", "gram 16", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("LG", "gram 17", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("LG", "gram SuperSlim", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("LG", "gram 16", 2024, "windows", "laptop", "x86", "intel-arc-a370m", 14, 16),
    ("LG", "gram Pro 16", 2024, "windows", "laptop", "x86", "nvidia-rtx-4050", 16, 16),
    ("LG", "gram 16", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),

    # ---------------- Gigabyte / Aorus ----------------
    ("Gigabyte", "Aorus 15", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Gigabyte", "Aorus 17", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 16, 16),
    ("Gigabyte", "Aero 16", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Gigabyte", "G5", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 12, 16),
    ("Gigabyte", "G6", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 16),
    ("Gigabyte", "Aorus 16X", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 16),

    # ---------------- Huawei ----------------
    ("Huawei", "MateBook X Pro", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Huawei", "MateBook 14s", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Huawei", "MateBook D16", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Huawei", "MateBook 16s", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),

    # ---------------- Framework ----------------
    ("Framework", "Laptop 13 (12th Gen)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Framework", "Laptop 13 (13th Gen)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Framework", "Laptop 16", 2024, "windows", "laptop", "x86", "amd-rx-7600s", 16, 16),
    ("Framework", "Laptop 13 (AMD)", 2024, "windows", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Framework", "Laptop 13 (12th Gen)", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Framework", "Laptop 13 (13th Gen)", 2023, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Framework", "Laptop 16", 2024, "linux", "laptop", "x86", "amd-rx-7600s", 16, 16),
    ("Framework", "Laptop 13 (AMD)", 2024, "linux", "laptop", "x86", "amd-radeon-780m", 8, 16),

    # ---------------- Linux-first vendors ----------------
    ("System76", "Lemur Pro", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("System76", "Darter Pro", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("System76", "Oryx Pro", 2022, "linux", "laptop", "x86", "nvidia-rtx-3060", 16, 32),
    ("System76", "Pangolin", 2022, "linux", "laptop", "x86", "amd-radeon-680m", 16, 16),
    ("System76", "Gazelle", 2023, "linux", "laptop", "x86", "nvidia-rtx-3050", 12, 16),
    ("System76", "Adder WS", 2023, "linux", "laptop", "x86", "nvidia-rtx-4060", 16, 32),
    ("System76", "Serval WS", 2023, "linux", "laptop", "x86", "nvidia-rtx-4080", 24, 64),
    ("System76", "Thelio Mira", 2022, "linux", "desktop", "x86", "amd-rx-6600m", 12, 32),
    ("Purism", "Librem 14", 2022, "linux", "laptop", "x86", "intel-uhd-620", 12, 16),
    ("Tuxedo", "InfinityBook Pro 14", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Tuxedo", "Pulse 15", 2022, "linux", "laptop", "x86", "amd-radeon-680m", 16, 16),
    ("Tuxedo", "Stellaris 17", 2023, "linux", "laptop", "x86", "nvidia-rtx-4060", 16, 32),
    ("Star Labs", "StarBook Mk VI", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Star Labs", "StarFighter 16", 2023, "linux", "laptop", "x86", "intel-iris-xe", 12, 32),
    ("Slimbook", "Pro X 14", 2022, "linux", "laptop", "x86", "amd-radeon-680m", 16, 16),
    ("Slimbook", "Executive 14", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Juno", "Nyx 14", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Juno", "Gemini 14", 2023, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
]

# The platform token each OS reports, and what the client hints say.
OS_PROFILE = {
    "windows": {
        "ua_token": "Windows NT 10.0; Win64; x64",
        "platform": "Win32",
        "ua_platform": "Windows",
        "platform_version": "15.0.0",   # Windows 11, which every machine here ships
    },
    "macos": {
        # Chrome reports the frozen Intel token even on Apple Silicon; the
        # architecture reaches the page through the client hints instead,
        # which is exactly why the shim has to set them.
        "ua_token": "Macintosh; Intel Mac OS X 10_15_7",
        "platform": "MacIntel",
        "ua_platform": "macOS",
        "platform_version": "14.5.0",
    },
    "linux": {
        "ua_token": "X11; Linux x86_64",
        "platform": "Linux x86_64",
        "ua_platform": "Linux",
        "platform_version": "6.8.0",
    },
}


def slug(value):
    out = []
    for ch in value.lower():
        if ch.isalnum():
            out.append(ch)
        elif out and out[-1] != "-":
            out.append("-")
    return "".join(out).strip("-")


def build_ua(ua_token, chrome):
    """The User-Agent string a desktop Chrome of this version sends.

    Chrome reduced the desktop UA in 2022: the string carries the MAJOR
    version with the rest zeroed (`Chrome/131.0.0.0`), and the build number
    moved to the `uaFullVersion` / `fullVersionList` client hints, which the
    shim answers. A 2024 machine sending a full build number in the UA string
    is a string no real Chrome emits, so this follows the reduced form. The
    platform token is the frozen one Chrome still sends - `Windows NT 10.0`
    on Windows 11, `Intel Mac OS X 10_15_7` on Apple Silicon.
    """
    major = chrome.split(".")[0]
    return ("Mozilla/5.0 (%s) AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/%s.0.0.0 Safari/537.36" % (ua_token, major))


def pick_chrome(dev_id, year, offset=0):
    """A Chrome release from the machine's year, stable per machine.

    `offset` lets build_rows walk the year's releases when a machine would
    otherwise land on a fingerprint already in the table. Two genuinely
    identical machines running the same Chrome ARE one fingerprint, so the
    catalogue separates them on the axis a real population varies on: which
    copy has updated. The choice stays deterministic for a given id.
    """
    versions = CHROME_BY_YEAR[year]
    return versions[(sum(ord(c) for c in dev_id) + offset) % len(versions)]


def build_rows():
    rows = []
    skipped = []
    seen_ids = set()
    seen_fingerprints = {}

    # A few names cover two different machines - ASUS shipped a "Zenbook 14
    # OLED" in both 2022 and 2023 - so those ids carry the year. Ids are
    # persisted in a profile's preferences, so they must be unique and must
    # not move once shipped.
    name_counts = collections.Counter(
        (brand, model, os_name) for brand, model, _y, os_name, _f, _a, _g, _c, _m
        in MACHINES)

    for brand, model, year, os_name, form, arch, gpu_key, cores, memory in MACHINES:
        profile = OS_PROFILE[os_name]
        if year not in CHROME_BY_YEAR:
            raise SystemExit("no Chrome releases for %d (%s %s)"
                             % (year, brand, model))
        if gpu_key not in GPUS:
            raise SystemExit("unknown GPU key %r (%s %s)" % (gpu_key, brand, model))
        pair = GPUS[gpu_key].get(os_name)
        if pair is None:
            raise SystemExit("%s %s cannot run %s" % (brand, model, os_name))

        gpu_vendor, gpu_renderer = pair
        if name_counts[(brand, model, os_name)] > 1:
            dev_id = "%s-%s-%d-%s" % (slug(brand), slug(model), year, os_name)
        else:
            dev_id = "%s-%s-%s" % (slug(brand), slug(model), os_name)
        if dev_id in seen_ids:
            raise SystemExit("duplicate id %s" % dev_id)
        seen_ids.add(dev_id)

        reported_memory = min(memory, 8)
        if reported_memory < 1:
            raise SystemExit("%s %s: implausible memory" % (brand, model))
        if cores < 2:
            raise SystemExit("%s %s: implausible core count" % (brand, model))

        row = {
            "id": dev_id,
            "brand": brand,
            "model": model,
            "year": year,
            "os": os_name,
            "form": form,
            "arch": arch,
            "gpu_vendor": gpu_vendor,
            "gpu_renderer": gpu_renderer,
            "cores": cores,
            "memory": memory,
            # Chromium caps deviceMemory at 8; a bigger machine reports 8.
            "reported_memory": reported_memory,
            "platform": profile["platform"],
            "ua_platform": profile["ua_platform"],
            "platform_version": profile["platform_version"],
        }

        # A duplicate fingerprint is a duplicate identity, which defeats the
        # point of the catalogue - two profiles would present the same
        # machine. Machines that agree on everything else (same GPU, cores,
        # memory, OS) are separated by the Chrome release they run.
        def fingerprint(ua):
            return (ua, arch, profile["platform"], profile["ua_platform"],
                    profile["platform_version"], gpu_vendor, gpu_renderer,
                    cores, reported_memory)

        fp = None
        for offset in range(len(CHROME_BY_YEAR[year])):
            chrome = pick_chrome(dev_id, year, offset)
            ua = build_ua(profile["ua_token"], chrome)
            if fingerprint(ua) not in seen_fingerprints:
                row["chrome"], row["ua"], fp = chrome, ua, fingerprint(ua)
                break
        if fp is None:
            # Every Chrome release of this machine's year is taken by another
            # machine that is otherwise identical to it. Such a machine cannot
            # be told apart from one already in the catalogue, so it is left
            # out rather than shipped as a second name for the same identity -
            # two profiles would present the same machine. Reported by main().
            skipped.append("%s %s (%s %d)" % (brand, model, os_name, year))
            continue
        seen_fingerprints[fp] = dev_id
        rows.append(row)

    return rows, skipped


def c_string(value):
    out = []
    for ch in value:
        if ch == '"':
            out.append('\\"')
        elif ch == "\\":
            out.append("\\\\")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ord(ch) < 32 or ord(ch) > 126:
            out.append("\\x%02x" % ord(ch))
        else:
            out.append(ch)
    return '"' + "".join(out) + '"'


def write_c(path, rows):
    out = []
    w = out.append

    w("/*")
    w(" * rb_devices.c - GENERATED FILE, DO NOT EDIT BY HAND.")
    w(" *")
    w(" * Regenerate with:")
    w(" *     desktop/scripts/gen-devices.py desktop/src/core/rb_devices.c")
    w(" *")
    w(" * %d real desktop and laptop machines, 2022-2025." % len(rows))
    w(" *")
    w(" * Provenance, because it decides what may be claimed: the machines,")
    w(" * their operating systems, their GPUs, their core counts and their")
    w(" * memory are real. The User-Agent strings are CONSTRUCTED rather than")
    w(" * captured from a machine of every model, in the reduced form Chrome")
    w(" * has sent since 2022: the platform token that OS reports with")
    w(" * Chrome/<major>.0.0.0 and no build number, which lives in the client")
    w(" * hints instead. The WebGL strings are in Chrome's ANGLE format for")
    w(" * each platform: Direct3D11 on Windows, Metal on macOS, OpenGL over")
    w(" * Mesa on Linux. Every entry is a DIFFERENT identity - a machine that")
    w(" * could not be told apart from one already here was left out, so the")
    w(" * curated table is longer than this catalogue. See")
    w(" * scripts/gen-devices.py for the full provenance note.")
    w(" */")
    w("")
    w('#include "rb_devices.h"')
    w("")
    w("#include <stddef.h> /* NULL */")
    w("#include <string.h>")
    w("")
    w("static const rb_device RB_DEVICES[] = {")
    for r in rows:
        w("    {")
        w("        %s," % c_string(r["id"]))
        w("        %s, %s," % (c_string(r["brand"]), c_string(r["model"])))
        w("        %d, %s, %s," % (r["year"], c_string(r["os"]), c_string(r["form"])))
        w("        %s, %s," % (c_string(r["arch"]), c_string(r["chrome"])))
        w("        %s," % c_string(r["gpu_vendor"]))
        w("        %s," % c_string(r["gpu_renderer"]))
        w("        %d, %d," % (r["cores"], r["reported_memory"]))
        w("        %s," % c_string(r["platform"]))
        w("        %s, %s," % (c_string(r["ua_platform"]),
                              c_string(r["platform_version"])))
        w("        %s" % c_string(r["ua"]))
        w("    },")
    w("};")
    w("")
    w("#define RB_DEVICES_N ((int)(sizeof(RB_DEVICES) / sizeof(RB_DEVICES[0])))")
    w("")
    w("int rb_device_count(void)")
    w("{")
    w("    return RB_DEVICES_N;")
    w("}")
    w("")
    w("const rb_device *rb_device_at(int index)")
    w("{")
    w("    if (index < 0 || index >= RB_DEVICES_N) {")
    w("        return NULL;")
    w("    }")
    w("    return &RB_DEVICES[index];")
    w("}")
    w("")
    w("const rb_device *rb_device_by_id(const char *id)")
    w("{")
    w("    int i;")
    w("")
    w("    if (id == NULL || id[0] == '\\0') {")
    w("        return NULL;")
    w("    }")
    w("    for (i = 0; i < RB_DEVICES_N; i++) {")
    w("        if (strcmp(RB_DEVICES[i].id, id) == 0) {")
    w("            return &RB_DEVICES[i];")
    w("        }")
    w("    }")
    w("    return NULL;")
    w("}")
    w("")

    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(out))


def main(argv):
    if len(argv) != 2:
        raise SystemExit("usage: gen-devices.py <out rb_devices.c>")
    rows, skipped = build_rows()
    write_c(argv[1], rows)

    counts = {}
    for r in rows:
        counts[r["os"]] = counts.get(r["os"], 0) + 1
    print("wrote %s: %d devices (%s)" % (
        argv[1], len(rows),
        ", ".join("%s %d" % (k, v) for k, v in sorted(counts.items()))))
    if skipped:
        print("%d candidate machine(s) left out - indistinguishable from a "
              "machine already in the catalogue:" % len(skipped))
        for name in skipped:
            print("  %s" % name)


if __name__ == "__main__":
    main(sys.argv)
