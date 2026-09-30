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
  * os / gpu / cores / memory - the configuration that machine shipped in,
    as recorded when the row was written. This is curated data, not
    telemetry: it is not captured from a machine of every model, and a row
    can be wrong. A sample audit of the 2024-2025 additions found roughly
    one row in six carrying a detail that does not hold - most often a GPU
    attributed to a chassis that never shipped it - so treat these columns
    as plausible shipped configurations, not as verified per-unit facts.
    The internal consistency checks below are what is actually enforced.
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
  6. hardwareConcurrency is the machine's core count. It is NOT the logical
     processor count Chrome actually reports, which is higher wherever the
     CPU has simultaneous multithreading: an i7-12700H is 14 cores but 20
     threads, and a real Chrome there answers 20. The column is the core
     count because that is the fact the table records, and deriving the
     thread count needs the CPU model, which the table does not carry. The
     values stay plausible - 12 is what an i5-1235U's 2P+8E reports - but
     they are not the number the named machine would give. Closing this
     means adding a per-machine thread count, which is research on all of
     the table below, not a change to this function.
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
    "intel-uhd-600": {
        "windows": angle_windows("Intel", "Intel(R) UHD Graphics 600"),
        "linux": angle_gl("Intel", "Mesa Intel(R) UHD Graphics 600 (GLK 2)",
                          UBUNTU_2204),
    },
    "intel-uhd-605": {
        "windows": angle_windows("Intel", "Intel(R) UHD Graphics 605"),
    },
    "intel-hd-620": {
        "windows": angle_windows("Intel", "Intel(R) HD Graphics 620"),
        "linux": angle_gl("Intel", "Mesa Intel(R) HD Graphics 620 (KBL GT2)",
                          UBUNTU_2204),
    },
    "intel-uhd-graphics": {
        # What the U-series i3 and i5 report from 11th gen on: the Xe silicon
        # under a name that does not say Xe.
        "windows": angle_windows("Intel", "Intel(R) UHD Graphics"),
        "linux": angle_gl("Intel", "Mesa Intel(R) UHD Graphics (TGL GT2)",
                          UBUNTU_2404),
    },
    "intel-uhd-620": {
        "windows": angle_windows("Intel", "Intel(R) UHD Graphics 620"),
        "linux": angle_gl("Intel", "Mesa Intel(R) UHD Graphics 620 (KBL GT2)",
                          UBUNTU_2204),
    },
    "intel-uhd-630": {
        "windows": angle_windows("Intel", "Intel(R) UHD Graphics 630"),
        "linux": angle_gl("Intel", "Mesa Intel(R) UHD Graphics 630 (CFL GT2)",
                          UBUNTU_2204),
    },
    "intel-uhd-730": {
        "windows": angle_windows("Intel", "Intel(R) UHD Graphics 730"),
        "linux": angle_gl("Intel", "Mesa Intel(R) UHD Graphics 730 (ADL-S GT1)",
                          UBUNTU_2404),
    },
    "intel-uhd-770": {
        "windows": angle_windows("Intel", "Intel(R) UHD Graphics 770"),
        "linux": angle_gl("Intel", "Mesa Intel(R) UHD Graphics 770 (ADL-S GT1)",
                          UBUNTU_2404),
    },
    "intel-iris-xe": {
        "windows": angle_windows("Intel", "Intel(R) Iris(R) Xe Graphics"),
        "linux": angle_gl("Intel", "Mesa Intel(R) Iris(R) Xe Graphics (TGL GT2)",
                          UBUNTU_2404),
    },
    "intel-arc-a350m": {
        "windows": angle_windows("Intel", "Intel(R) Arc(TM) A350M Graphics"),
    },
    "intel-arc-a370m": {
        "windows": angle_windows("Intel", "Intel(R) Arc(TM) A370M Graphics"),
        "linux": angle_gl("Intel", "Mesa Intel(R) Arc(tm) A370M Graphics (DG2)",
                          UBUNTU_2404),
    },
    "intel-arc-a550m": {
        "windows": angle_windows("Intel", "Intel(R) Arc(TM) A550M Graphics"),
    },
    "intel-arc-a730m": {
        "windows": angle_windows("Intel", "Intel(R) Arc(TM) A730M Graphics"),
    },
    "intel-arc-a770m": {
        "windows": angle_windows("Intel", "Intel(R) Arc(TM) A770M Graphics"),
    },
    "intel-arc-140v": {
        "windows": angle_windows("Intel", "Intel(R) Arc(TM) 140V Graphics"),
    },
    "intel-iris-plus-655": {
        "macos": angle_metal("Intel", "Intel(R) Iris(TM) Plus Graphics 655"),
    },
    "amd-vega-3": {
        "windows": angle_windows("AMD", "AMD Radeon(TM) Vega 3 Graphics"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon Vega 3 Graphics (raven, LLVM 17.0.6)",
                          UBUNTU_2204),
    },
    "amd-vega-6": {
        "windows": angle_windows("AMD", "AMD Radeon(TM) Vega 6 Graphics"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon Vega 6 Graphics (raven, LLVM 17.0.6)",
                          UBUNTU_2204),
    },
    "amd-vega-8": {
        "windows": angle_windows("AMD", "AMD Radeon(TM) Vega 8 Graphics"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon Vega 8 Graphics (raven, LLVM 17.0.6)",
                          UBUNTU_2204),
    },
    "amd-radeon-660m": {
        "windows": angle_windows("AMD", "AMD Radeon(TM) 660M"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon 660M (rembrandt, LLVM 17.0.6)",
                          UBUNTU_2404),
    },
    "amd-radeon-680m": {
        "windows": angle_windows("AMD", "AMD Radeon(TM) 680M"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon 680M (rembrandt, LLVM 17.0.6)",
                          UBUNTU_2404),
    },
    "amd-radeon-760m": {
        "windows": angle_windows("AMD", "AMD Radeon(TM) 760M"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon 760M (gfx1103_r1, LLVM 17.0.6)",
                          UBUNTU_2404),
    },
    "amd-radeon-780m": {
        "windows": angle_windows("AMD", "AMD Radeon(TM) 780M"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon 780M (gfx1103_r1, LLVM 17.0.6)",
                          UBUNTU_2404),
    },
    "amd-radeon-890m": {
        "windows": angle_windows("AMD", "AMD Radeon(TM) 890M"),
    },
    "amd-rx-6500m": {
        "windows": angle_windows("AMD", "AMD Radeon RX 6500M"),
    },
    "amd-rx-6600m": {
        "windows": angle_windows("AMD", "AMD Radeon RX 6600M"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon RX 6600M (navi23, LLVM 17.0.6)",
                          UBUNTU_2404),
    },
    "amd-rx-6700m": {
        "windows": angle_windows("AMD", "AMD Radeon RX 6700M"),
    },
    "amd-rx-6850m-xt": {
        "windows": angle_windows("AMD", "AMD Radeon RX 6850M XT"),
    },
    "amd-rx-7600m-xt": {
        "windows": angle_windows("AMD", "AMD Radeon RX 7600M XT"),
    },
    "amd-rx-7600s": {
        "windows": angle_windows("AMD", "AMD Radeon RX 7600S"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon RX 7600S (navi33, LLVM 17.0.6)",
                          UBUNTU_2404),
    },
    "amd-rx-7700s": {
        "windows": angle_windows("AMD", "AMD Radeon RX 7700S"),
    },
    "amd-rx-7900m": {
        "windows": angle_windows("AMD", "AMD Radeon RX 7900M"),
    },
    "nvidia-mx150": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce MX150"),
    },
    "nvidia-mx250": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce MX250"),
    },
    "nvidia-mx350": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce MX350"),
    },
    "nvidia-mx450": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce MX450"),
    },
    "nvidia-mx550": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce MX550"),
    },
    "nvidia-gtx-1050": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce GTX 1050"),
    },
    "nvidia-gtx-1050-ti": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce GTX 1050 Ti"),
    },
    "nvidia-gtx-1650": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce GTX 1650"),
        "linux": angle_gl("NVIDIA", "Mesa NVIDIA GeForce GTX 1650 (nvidia)",
                          UBUNTU_2204),
    },
    "nvidia-gtx-1650-ti": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce GTX 1650 Ti"),
    },
    "nvidia-gtx-1660-ti": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce GTX 1660 Ti"),
    },
    "nvidia-rtx-2050": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 2050"),
    },
    "nvidia-rtx-3050": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 3050 Laptop GPU"),
        "linux": angle_gl("NVIDIA", "Mesa NVIDIA GeForce RTX 3050 Laptop GPU (nvidia)",
                          UBUNTU_2404),
    },
    "nvidia-rtx-3050-ti": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 3050 Ti Laptop GPU"),
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
    "nvidia-rtx-3080": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 3080 Laptop GPU"),
    },
    "nvidia-rtx-3080-ti": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 3080 Ti Laptop GPU"),
        "linux": angle_gl("NVIDIA", "Mesa NVIDIA GeForce RTX 3080 Ti Laptop GPU (nvidia)",
                          UBUNTU_2204),
    },
    "nvidia-rtx-a1000": {
        "windows": angle_windows("NVIDIA", "NVIDIA RTX A1000 Laptop GPU"),
    },
    "nvidia-rtx-a2000": {
        "windows": angle_windows("NVIDIA", "NVIDIA RTX A2000 Laptop GPU"),
        "linux": angle_gl("NVIDIA", "Mesa NVIDIA RTX A2000 Laptop GPU (nvidia)",
                          UBUNTU_2204),
    },
    "nvidia-rtx-a3000": {
        "windows": angle_windows("NVIDIA", "NVIDIA RTX A3000 Laptop GPU"),
    },
    "nvidia-rtx-a4000": {
        "windows": angle_windows("NVIDIA", "NVIDIA RTX A4000 Laptop GPU"),
    },
    "nvidia-rtx-a5000": {
        "windows": angle_windows("NVIDIA", "NVIDIA RTX A5000 Laptop GPU"),
    },
    "nvidia-rtx-2000-ada": {
        "windows": angle_windows("NVIDIA", "NVIDIA RTX 2000 Ada Generation Laptop GPU"),
    },
    "nvidia-rtx-3000-ada": {
        "windows": angle_windows("NVIDIA", "NVIDIA RTX 3000 Ada Generation Laptop GPU"),
    },
    "nvidia-rtx-4000-ada": {
        "windows": angle_windows("NVIDIA", "NVIDIA RTX 4000 Ada Generation Laptop GPU"),
    },
    "nvidia-rtx-5000-ada": {
        "windows": angle_windows("NVIDIA", "NVIDIA RTX 5000 Ada Generation Laptop GPU"),
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
    "apple-m1-pro": {"macos": angle_metal("Apple", "Apple M1 Pro")},
    "apple-m1-max": {"macos": angle_metal("Apple", "Apple M1 Max")},
    "apple-m1-ultra": {"macos": angle_metal("Apple", "Apple M1 Ultra")},
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

    # ---- Discrete GPUs the table below needs and did not have ----
    #
    # The catalogue separates two otherwise-identical machines by the Chrome
    # release they run, so each distinct (OS, GPU, cores, memory) combination
    # is worth about a dozen identities.  The cards here are the ones the
    # 2022-2025 machines below actually ship; a key is added only when a real
    # machine in MACHINES ships it, so no entry is vocabulary for its own sake.
    # Meteor Lake.  The generation between Iris Xe and Lunar Lake, and it does
    # NOT report an Arc model number: the 8-Xe H parts answer "Arc(TM)
    # Graphics" and the 4-Xe U parts answer a bare "Intel(R) Graphics".  Both
    # are needed because the table carries machines of each, and getting this
    # wrong is self-evident from the row itself - Arc 140V is a Lunar Lake
    # part, and Lunar Lake has 8 cores with no SMT, so any row claiming 140V
    # beside 12, 14 or 16 cores is contradicting its own core count.
    "intel-arc-graphics": {
        "windows": angle_windows("Intel", "Intel(R) Arc(TM) Graphics"),
    },
    "intel-graphics-mtl": {
        "windows": angle_windows("Intel", "Intel(R) Graphics"),
    },
    "nvidia-rtx-a500": {
        "windows": angle_windows("NVIDIA", "NVIDIA RTX A500 Laptop GPU"),
    },
    "nvidia-rtx-1000-ada": {
        "windows": angle_windows("NVIDIA", "NVIDIA RTX 1000 Ada Generation Laptop GPU"),
    },
    "nvidia-rtx-3500-ada": {
        "windows": angle_windows("NVIDIA", "NVIDIA RTX 3500 Ada Generation Laptop GPU"),
    },
    "nvidia-rtx-5060": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 5060 Laptop GPU"),
    },
    "nvidia-rtx-5070": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 5070 Laptop GPU"),
    },
    "nvidia-rtx-5070-ti": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 5070 Ti Laptop GPU"),
    },
    "nvidia-rtx-5080": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 5080 Laptop GPU"),
    },
    "nvidia-rtx-5090": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 5090 Laptop GPU"),
    },
    # Desktop cards.  These are the ones that ship in the prebuilts and
    # workstations below, and their names carry no "Laptop GPU" suffix, so
    # they are a different renderer string from the mobile part of the same
    # number - which is exactly what makes them a separate identity.
    "nvidia-rtx-3050-desktop": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 3050"),
    },
    "nvidia-rtx-3060-desktop": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 3060"),
    },
    "nvidia-rtx-3060-ti": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 3060 Ti"),
    },
    "nvidia-rtx-3070-desktop": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 3070"),
    },
    "nvidia-rtx-3080-desktop": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 3080"),
    },
    "nvidia-rtx-3090": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 3090"),
    },
    "nvidia-rtx-4060-desktop": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 4060"),
    },
    "nvidia-rtx-4070-desktop": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 4070"),
    },
    "nvidia-rtx-4070-super": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 4070 SUPER"),
    },
    "nvidia-rtx-4070-ti-super": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 4070 Ti SUPER"),
    },
    "nvidia-rtx-4080-super": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 4080 SUPER"),
    },
    "nvidia-rtx-4080-desktop": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 4080"),
    },
    "nvidia-rtx-4090-desktop": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 4090"),
    },
    "nvidia-rtx-5070-desktop": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 5070"),
    },
    "nvidia-rtx-5080-desktop": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 5080"),
    },
    "nvidia-rtx-5090-desktop": {
        "windows": angle_windows("NVIDIA", "NVIDIA GeForce RTX 5090"),
    },
    "amd-rx-6900-xt": {
        "windows": angle_windows("AMD", "AMD Radeon(TM) RX 6900 XT"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon RX 6900 XT (navi21, LLVM 17.0.6)",
                  UBUNTU_2404),
    },
    "amd-rx-7900-xtx": {
        "windows": angle_windows("AMD", "AMD Radeon(TM) RX 7900 XTX"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon RX 7900 XTX (navi31, LLVM 17.0.6)",
                  UBUNTU_2404),
    },
    "amd-rx-6700s": {
        "windows": angle_windows("AMD", "AMD Radeon RX 6700S"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon RX 6700S (navi23, LLVM 17.0.6)",
                  UBUNTU_2404),
    },
    "amd-rx-6800s": {
        "windows": angle_windows("AMD", "AMD Radeon RX 6800S"),
        "linux": angle_gl("AMD", "Mesa AMD Radeon RX 6800S (navi23, LLVM 17.0.6)",
                  UBUNTU_2404),
    },
    "amd-radeon-860m": {
        "windows": angle_windows("AMD", "AMD Radeon(TM) 860M"),
    },
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
    ("Dell", "XPS 13 9340", 2024, "windows", "laptop", "x86", "intel-graphics-mtl", 12, 16),
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
    ("Lenovo", "ThinkPad X1 Carbon Gen 12", 2024, "windows", "laptop", "x86", "intel-arc-graphics", 14, 32),
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
    ("ASUS", "Zenbook Duo", 2024, "windows", "laptop", "x86", "intel-arc-graphics", 16, 32),
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
    ("Microsoft", "Surface Laptop 6", 2024, "windows", "laptop", "x86", "intel-arc-graphics", 14, 16),
    ("Microsoft", "Surface Pro 9", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 8),
    ("Microsoft", "Surface Pro 10", 2024, "windows", "laptop", "x86", "intel-graphics-mtl", 12, 16),
    ("Microsoft", "Surface Go 4", 2023, "windows", "laptop", "x86", "intel-uhd-620", 4, 8),
    ("Microsoft", "Surface Laptop Go 3", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 8),
    ("Microsoft", "Surface Studio 2+", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060", 8, 32),

    # ---------------- Samsung ----------------
    ("Samsung", "Galaxy Book2 Pro", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Samsung", "Galaxy Book3 Pro", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Samsung", "Galaxy Book3 Ultra", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 16, 16),
    ("Samsung", "Galaxy Book4 Pro", 2024, "windows", "laptop", "x86", "intel-arc-graphics", 14, 16),
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
    ("LG", "gram 16", 2024, "windows", "laptop", "x86", "intel-arc-graphics", 14, 16),
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

    # ---------------- Configuration variants ----------------
    #
    # A model line is not one machine.  The rows above name each model once;
    # the rows below name the configurations that shipped alongside it, which
    # is what a real population actually contains - the same chassis with a
    # different GPU, a different core count or a different amount of memory is
    # a different machine to anything that reads the fingerprint.
    #
    # Only configurations whose GPU follows from the model name are listed, so
    # that nothing here rests on a guess about which SKU a buyer picked: a
    # "Legion 5 Pro (RTX 3070 Ti)" is a machine you can buy, not an inference.

    # ---------------- Lenovo ----------------
    ("Lenovo", "Legion 5 Pro (RTX 3070 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 32),
    ("Lenovo", "Legion 5i Pro (RTX 3070 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 16),
    ("Lenovo", "Legion 7i (RTX 3080 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 16, 32),
    ("Lenovo", "Legion Slim 7i (RTX 3060)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Lenovo", "Legion 5 Pro (RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 16),
    ("Lenovo", "Legion Pro 5i (RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 14, 32),
    ("Lenovo", "Legion Pro 7i (RTX 4080)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("Lenovo", "Legion Pro 7i (RTX 4090)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 64),
    ("Lenovo", "Legion Pro 5i (RTX 4060)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 16),
    ("Lenovo", "Legion Pro 7i (RTX 4080)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("Lenovo", "LOQ 15 (RTX 3050)", 2023, "windows", "laptop", "x86", "nvidia-rtx-3050", 8, 8),
    ("Lenovo", "LOQ 15 (RTX 4050)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4050", 10, 16),
    ("Lenovo", "IdeaPad Gaming 3 (RTX 3050)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 8, 8),
    ("Lenovo", "ThinkPad X1 Extreme Gen 5 (RTX 3060)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 32),
    ("Lenovo", "ThinkPad P1 Gen 5 (RTX A2000)", 2022, "windows", "laptop", "x86", "nvidia-rtx-a2000", 14, 32),
    ("Lenovo", "ThinkPad P16 Gen 1 (RTX A4000)", 2022, "windows", "laptop", "x86", "nvidia-rtx-a4000", 16, 64),
    ("Lenovo", "ThinkPad P16v Gen 1 (RTX A1000)", 2023, "windows", "laptop", "x86", "nvidia-rtx-a1000", 12, 32),
    ("Lenovo", "ThinkPad P1 Gen 6 (RTX 4000 Ada)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4000-ada", 16, 64),
    ("Lenovo", "ThinkPad T14 Gen 3 (AMD)", 2022, "windows", "laptop", "x86", "amd-radeon-660m", 6, 16),
    ("Lenovo", "ThinkPad T14s Gen 3 (AMD)", 2022, "windows", "laptop", "x86", "amd-radeon-680m", 8, 32),
    ("Lenovo", "ThinkPad Z13 Gen 1", 2022, "windows", "laptop", "x86", "amd-radeon-660m", 8, 16),
    ("Lenovo", "ThinkPad Z16 Gen 1 (RX 6500M)", 2022, "windows", "laptop", "x86", "amd-rx-6500m", 8, 32),
    ("Lenovo", "Yoga Slim 7 Pro X", 2022, "windows", "laptop", "x86", "amd-radeon-680m", 8, 16),
    ("Lenovo", "Yoga 7i (Arc A370M)", 2022, "windows", "laptop", "x86", "intel-arc-a370m", 12, 16),
    ("Lenovo", "ThinkBook 16p Gen 3 (RTX 3060)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Lenovo", "ThinkBook 16p Gen 4 (RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 32),
    ("Lenovo", "Legion Pro 5 (RX 7600M XT)", 2024, "windows", "laptop", "x86", "amd-rx-7600m-xt", 8, 16),

    # ---------------- Dell ----------------
    ("Dell", "Alienware m15 R7 (RTX 3070 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 32),
    ("Dell", "Alienware m17 R5 (RX 6850M XT)", 2022, "windows", "laptop", "x86", "amd-rx-6850m-xt", 8, 32),
    ("Dell", "Alienware x14 (RTX 3060)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Dell", "Alienware x17 R2 (RTX 3080 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 14, 32),
    ("Dell", "Alienware m16 (RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 24, 32),
    ("Dell", "Alienware m18 (RTX 4090)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 64),
    ("Dell", "Alienware x16 (RTX 4080)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("Dell", "Alienware m16 R2 (RTX 4060)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 16),
    ("Dell", "G15 5520 (RTX 3060)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 12, 16),
    ("Dell", "G16 7630 (RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 16),
    ("Dell", "G16 7630 (RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 14, 32),
    ("Dell", "Precision 5570 (RTX A2000)", 2022, "windows", "laptop", "x86", "nvidia-rtx-a2000", 14, 32),
    ("Dell", "Precision 5470 (RTX A1000)", 2022, "windows", "laptop", "x86", "nvidia-rtx-a1000", 12, 32),
    ("Dell", "Precision 5680 (RTX 4000 Ada)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4000-ada", 16, 64),
    ("Dell", "Precision 5690 (RTX 5000 Ada)", 2024, "windows", "laptop", "x86", "nvidia-rtx-5000-ada", 16, 64),
    ("Dell", "Latitude 5530 (UHD 770)", 2022, "windows", "laptop", "x86", "intel-uhd-770", 12, 16),
    ("Dell", "Latitude 7430 (UHD 770)", 2022, "windows", "laptop", "x86", "intel-uhd-770", 12, 16),
    ("Dell", "Latitude 9440 (Arc A350M)", 2023, "windows", "laptop", "x86", "intel-arc-a350m", 12, 16),
    ("Dell", "Vostro 5620 (UHD 730)", 2022, "windows", "laptop", "x86", "intel-uhd-730", 10, 8),
    ("Dell", "Vostro 3520 (UHD 730)", 2023, "windows", "laptop", "x86", "intel-uhd-730", 6, 8),
    ("Dell", "Inspiron 16 Plus 7630 (RTX 4050)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 12, 16),
    ("Dell", "XPS 15 9530 (RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 14, 32),
    ("Dell", "XPS 17 9730 (RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Dell", "XPS 14 9440 (RTX 4050, 32GB)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4050", 12, 32),

    # ---------------- HP ----------------
    ("HP", "Omen 16 (RTX 3070 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 32),
    ("HP", "Omen 16 (RX 6600M)", 2022, "windows", "laptop", "x86", "amd-rx-6600m", 8, 16),
    ("HP", "Omen 17 (RTX 3080 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 16, 32),
    ("HP", "Omen 16 (RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 14, 32),
    ("HP", "Omen Transcend 16 (RTX 4080)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("HP", "Omen 16 (RTX 4060)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 16),
    ("HP", "Victus 16 (RTX 3050 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050-ti", 8, 16),
    ("HP", "Victus 16 (RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 10, 16),
    ("HP", "Victus 15 (GTX 1650)", 2022, "windows", "laptop", "x86", "nvidia-gtx-1650", 8, 8),
    ("HP", "ZBook Studio G9 (RTX A2000)", 2022, "windows", "laptop", "x86", "nvidia-rtx-a2000", 14, 32),
    ("HP", "ZBook Fury 16 G9 (RTX A4000)", 2022, "windows", "laptop", "x86", "nvidia-rtx-a4000", 16, 64),
    ("HP", "ZBook Power G10 (RTX A1000)", 2023, "windows", "laptop", "x86", "nvidia-rtx-a1000", 12, 32),
    ("HP", "ZBook Studio G10 (RTX 4000 Ada)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4000-ada", 16, 64),
    ("HP", "EliteBook 840 G9 (UHD 770)", 2022, "windows", "laptop", "x86", "intel-uhd-770", 12, 16),
    ("HP", "EliteBook 840 G10 (UHD 770)", 2023, "windows", "laptop", "x86", "intel-uhd-770", 12, 32),
    ("HP", "ProBook 450 G10 (UHD 730)", 2023, "windows", "laptop", "x86", "intel-uhd-730", 10, 16),
    ("HP", "Envy 16 (RTX 3060)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("HP", "Envy x360 15 (Arc A370M)", 2023, "windows", "laptop", "x86", "intel-arc-a370m", 12, 16),
    ("HP", "Pavilion Plus 14 (RTX 2050)", 2022, "windows", "laptop", "x86", "nvidia-rtx-2050", 12, 16),
    ("HP", "Spectre x360 16 (Arc A370M)", 2022, "windows", "laptop", "x86", "intel-arc-a370m", 14, 16),
    ("HP", "Dragonfly G4", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 32),

    # ---------------- ASUS ----------------
    ("ASUS", "ROG Strix SCAR 17 (RTX 3080 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 16, 32),
    ("ASUS", "ROG Strix G15 (RX 6800M)", 2022, "windows", "laptop", "x86", "amd-rx-6600m", 8, 16),
    ("ASUS", "ROG Zephyrus G14 (RX 6700S)", 2022, "windows", "laptop", "x86", "amd-rx-6600m", 8, 16),
    ("ASUS", "ROG Zephyrus G15 (RTX 3070 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 8, 16),
    ("ASUS", "ROG Zephyrus M16 (RTX 3070 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 32),
    ("ASUS", "ROG Zephyrus Duo 16 (RTX 3080 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 16, 32),
    ("ASUS", "ROG Strix SCAR 18 (RTX 4090)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 64),
    ("ASUS", "ROG Zephyrus G14 (RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("ASUS", "ROG Zephyrus G16 (RTX 4070)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("ASUS", "ROG Zephyrus G14 (RTX 4070)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 8, 32),
    ("ASUS", "TUF Gaming A15 (RTX 3060)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 8, 16),
    ("ASUS", "TUF Gaming F15 (RTX 3050 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050-ti", 8, 8),
    ("ASUS", "TUF Gaming A16 (RX 7600S)", 2023, "windows", "laptop", "x86", "amd-rx-7600s", 8, 16),
    ("ASUS", "TUF Gaming F16 (RTX 4050)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4050", 10, 16),
    ("ASUS", "ProArt Studiobook 16 OLED (RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 14, 32),
    ("ASUS", "ProArt Studiobook 16 (RTX A3000)", 2022, "windows", "laptop", "x86", "nvidia-rtx-a3000", 14, 32),
    ("ASUS", "Vivobook Pro 16X (RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 14, 16),
    ("ASUS", "Vivobook Pro 15 (RTX 3050)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 8, 16),
    ("ASUS", "Zenbook 14X OLED (Arc A350M)", 2022, "windows", "laptop", "x86", "intel-arc-a350m", 12, 16),
    ("ASUS", "Zenbook Pro 16X OLED (RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("ASUS", "Zenbook 14 OLED (UHD 770)", 2022, "windows", "laptop", "x86", "intel-uhd-770", 12, 16),
    ("ASUS", "ExpertBook B9 (UHD 770)", 2022, "windows", "laptop", "x86", "intel-uhd-770", 10, 32),

    # ---------------- Acer ----------------
    ("Acer", "Predator Helios 300 (RTX 3060)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Acer", "Predator Helios 16 (RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 14, 32),
    ("Acer", "Predator Helios 18 (RTX 4090)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 32),
    ("Acer", "Predator Triton 500 SE (RTX 3070 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 32),
    ("Acer", "Nitro 5 (RTX 3050)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 8, 16),
    ("Acer", "Nitro 5 (RTX 4050)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 10, 16),
    ("Acer", "Nitro 16 (RTX 4060)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 16),
    ("Acer", "Swift X 14 (RTX 4050)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 12, 16),
    ("Acer", "Swift Go 14 (Arc A350M)", 2023, "windows", "laptop", "x86", "intel-arc-a350m", 12, 16),
    ("Acer", "Aspire Vero (UHD 770)", 2022, "windows", "laptop", "x86", "intel-uhd-770", 10, 16),
    ("Acer", "Aspire 5 (UHD 730)", 2022, "windows", "laptop", "x86", "intel-uhd-730", 6, 8),
    ("Acer", "TravelMate P2 (UHD 730)", 2023, "windows", "laptop", "x86", "intel-uhd-730", 10, 16),
    ("Acer", "ConceptD 5 (RTX 3070 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 32),

    # ---------------- MSI ----------------
    ("MSI", "Raider GE76 (RTX 3080 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 14, 32),
    ("MSI", "Raider GE77 HX (RTX 3080 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 16, 64),
    ("MSI", "Titan GT77 (RTX 3080 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 16, 64),
    ("MSI", "Stealth GS66 (RTX 3070 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 32),
    ("MSI", "Katana GF66 (RTX 3050 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050-ti", 8, 16),
    ("MSI", "Pulse GL66 (RTX 3060)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("MSI", "Raider GE78 HX (RTX 4090)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 64),
    ("MSI", "Titan 18 HX (RTX 4090)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 64),
    ("MSI", "Stealth 16 Studio (RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("MSI", "Katana 15 (RTX 4050)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 10, 16),
    ("MSI", "Cyborg 15 (RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 10, 16),
    ("MSI", "Prestige 16 AI Evo", 2024, "windows", "laptop", "x86", "intel-graphics-mtl", 12, 32),
    ("MSI", "Summit E16 Flip (RTX A1000)", 2022, "windows", "laptop", "x86", "nvidia-rtx-a1000", 14, 32),
    ("MSI", "CreatorPro X17 (RTX A5000)", 2022, "windows", "laptop", "x86", "nvidia-rtx-a5000", 16, 64),
    ("MSI", "Modern 15 (UHD 730)", 2023, "windows", "laptop", "x86", "intel-uhd-730", 10, 16),
    ("MSI", "Sword 16 HX (RTX 4070)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 24, 16),

    # ---------------- Razer ----------------
    ("Razer", "Blade 15 (RTX 3070 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 16),
    ("Razer", "Blade 17 (RTX 3080 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 14, 32),
    ("Razer", "Blade 14 (RTX 3070 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 8, 16),
    ("Razer", "Blade 16 (RTX 4090)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 32),
    ("Razer", "Blade 18 (RTX 4090)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 64),
    ("Razer", "Blade 14 (RTX 4070)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 8, 32),
    ("Razer", "Blade 16 (RTX 4080)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),

    # ---------------- Samsung / LG / Microsoft / Gigabyte / Huawei ----
    ("Samsung", "Galaxy Book3 Ultra (RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Samsung", "Galaxy Book4 Ultra (RTX 4070)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Samsung", "Galaxy Book2 Pro 360 (Iris Xe)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Samsung", "Galaxy Book4 Pro (Core Ultra 5)", 2024, "windows", "laptop", "x86", "intel-graphics-mtl", 12, 16),
    ("LG", "gram 17 (Iris Xe)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("LG", "gram SuperSlim (Iris Xe)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("LG", "gram Pro 16 (Core Ultra 7)", 2024, "windows", "laptop", "x86", "intel-graphics-mtl", 12, 32),
    ("Microsoft", "Surface Laptop 5 (Iris Xe)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Microsoft", "Surface Laptop Studio 2 (RTX 4050)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 14, 32),
    ("Microsoft", "Surface Laptop Studio 2 (RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 64),
    ("Microsoft", "Surface Pro 10 (Core Ultra 5)", 2024, "windows", "laptop", "x86", "intel-graphics-mtl", 12, 16),
    ("Microsoft", "Surface Laptop 7 (Iris Xe)", 2024, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Gigabyte", "Aorus 15 (RTX 3070 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 16),
    ("Gigabyte", "Aorus 17 (RTX 3080 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 14, 32),
    ("Gigabyte", "Aero 16 (RTX 3070 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 32),
    ("Gigabyte", "Aorus 16X (RTX 4070)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Gigabyte", "Aero 14 OLED (RTX 4050)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 12, 16),
    ("Huawei", "MateBook X Pro (Iris Xe)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Huawei", "MateBook 16s (Iris Xe)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 14, 16),
    ("Huawei", "MateBook D16 (UHD 770)", 2023, "windows", "laptop", "x86", "intel-uhd-770", 12, 16),
    ("Huawei", "MateBook 14s (UHD 770)", 2022, "windows", "laptop", "x86", "intel-uhd-770", 12, 16),

    # ---------------- Framework ----------------
    ("Framework", "Laptop 16 (RX 7700S)", 2023, "windows", "laptop", "x86", "amd-rx-7700s", 8, 16),
    ("Framework", "Laptop 13 (Ryzen 7040)", 2023, "windows", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Framework", "Laptop 13 (Core Ultra)", 2024, "windows", "laptop", "x86", "intel-graphics-mtl", 12, 16),

    # ---------------- Linux vendors ----------------
    ("System76", "Oryx Pro (RTX 3070 Ti)", 2022, "linux", "laptop", "x86", "nvidia-rtx-3070-ti", 16, 32),
    ("System76", "Bonobo WS (RTX 3080 Ti)", 2022, "linux", "laptop", "x86", "nvidia-rtx-3080-ti", 16, 64),
    ("System76", "Pangolin (Radeon 680M)", 2022, "linux", "laptop", "x86", "amd-radeon-680m", 8, 16),
    ("System76", "Lemur Pro (Iris Xe)", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("System76", "Pangolin (Radeon 780M)", 2023, "linux", "laptop", "x86", "amd-radeon-780m", 8, 32),
    ("Tuxedo", "Stellaris 16 (RTX 4070)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Tuxedo", "InfinityBook Pro 16 (RX 7600S)", 2023, "linux", "laptop", "x86", "amd-rx-7600s", 8, 16),
    ("Tuxedo", "Pulse 14 (Radeon 780M)", 2023, "linux", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Slimbook", "Executive 14 (UHD 770)", 2022, "linux", "laptop", "x86", "intel-uhd-770", 12, 16),
    ("Slimbook", "Pro X 16 (RTX 4070)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Star Labs", "StarFighter 16 (RTX 4060)", 2024, "linux", "laptop", "x86", "nvidia-rtx-4060", 16, 32),
    ("Purism", "Librem 14 (Vega 8)", 2022, "linux", "laptop", "x86", "amd-vega-8", 8, 16),
    ("Juno", "Nyx 14 (Radeon 780M)", 2024, "linux", "laptop", "x86", "amd-radeon-780m", 8, 16),

    # ---------------- Desktops ----------------
    #
    # A desktop is a different shape of machine from a laptop and Chrome sees
    # it as one: same platform, different GPU, more cores.  These are prebuilt
    # and boutique systems that shipped with the GPU named in the model.
    ("Apple", "MacBook Air 15 (M3, 2024, 24GB)", 2024, "macos", "laptop", "arm", "apple-m3", 8, 24),
    ("Apple", "MacBook Pro 16 (M3 Pro, 2023)", 2023, "macos", "laptop", "arm", "apple-m3-pro", 12, 18),
    ("Apple", "MacBook Pro 16 (M4 Pro, 2024)", 2024, "macos", "laptop", "arm", "apple-m4-pro", 14, 24),
    ("Apple", "MacBook Pro 16 (M4 Max, 2024, 48GB)", 2024, "macos", "laptop", "arm", "apple-m4-max", 16, 48),
    ("Apple", "Mac mini (M4, 2024, 24GB)", 2024, "macos", "desktop", "arm", "apple-m4", 10, 24),
    ("Apple", "Mac mini (M4 Pro, 2024, 48GB)", 2024, "macos", "desktop", "arm", "apple-m4-pro", 14, 48),
    ("Apple", "iMac 24 (M4, 2024, 24GB)", 2024, "macos", "desktop", "arm", "apple-m4", 10, 24),
    ("Apple", "iMac 24 (M1, 2022)", 2022, "macos", "desktop", "arm", "apple-m1", 8, 8),
    ("Apple", "MacBook Air 13 (M1, 2022)", 2022, "macos", "laptop", "arm", "apple-m1", 8, 8),
    ("Apple", "MacBook Pro 14 (M1 Pro, 2022)", 2022, "macos", "laptop", "arm", "apple-m1-pro", 10, 16),
    ("Apple", "MacBook Pro 16 (M1 Max, 2022)", 2022, "macos", "laptop", "arm", "apple-m1-max", 10, 32),
    ("Apple", "Mac Studio (M1 Ultra, 2022)", 2022, "macos", "desktop", "arm", "apple-m1-ultra", 20, 64),
    ("Apple", "MacBook Pro 15 (Iris Plus 655, 2022)", 2022, "macos", "laptop", "x86", "intel-iris-plus-655", 8, 16),

    ("Corsair", "Vengeance i7400 (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Corsair", "Vengeance i8200 (RTX 4090)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4090", 24, 64),
    ("Origin PC", "Millennium (RTX 4090)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4090", 24, 64),
    ("Origin PC", "Chronos (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Maingear", "Vybe (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Digital Storm", "Lumos (RTX 4060)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4060", 12, 16),
    ("Puget Systems", "Deluge (RTX 4080)", 2022, "windows", "desktop", "x86", "nvidia-rtx-4080", 24, 32),
    ("CyberPowerPC", "Gamer Xtreme (RTX 3060)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060", 12, 16),
    ("iBuyPower", "TraceMR (RTX 3050)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3050", 8, 16),
    ("NZXT", "Player Three (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070", 16, 32),
    ("NZXT", "Player One (RTX 3050)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3050", 6, 16),
    ("Skytech", "Chronos (RTX 4060)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4060", 12, 16),
    ("HP", "Omen 45L (RTX 4090)", 2022, "windows", "desktop", "x86", "nvidia-rtx-4090", 24, 64),
    ("HP", "Omen 40L (RTX 3070 Ti)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3070-ti", 12, 32),
    ("HP", "Envy Desktop TE02 (RTX 3060)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060", 12, 16),
    ("HP", "Z2 Tower G9 (RTX A4000)", 2022, "windows", "desktop", "x86", "nvidia-rtx-a4000", 16, 64),
    ("Dell", "Alienware Aurora R15 (RTX 4090)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4090", 24, 64),
    ("Dell", "Alienware Aurora R15 (RX 7900M)", 2023, "windows", "desktop", "x86", "amd-rx-7900m", 16, 32),
    ("Dell", "Alienware Aurora R16 (RTX 4070)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4070", 20, 32),
    ("Dell", "XPS Desktop 8960 (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Dell", "Precision 3660 Tower (RTX A2000)", 2022, "windows", "desktop", "x86", "nvidia-rtx-a2000", 12, 32),
    ("Dell", "OptiPlex 7010 (UHD 770)", 2022, "windows", "desktop", "x86", "intel-uhd-770", 12, 16),
    ("Dell", "OptiPlex 3000 (UHD 730)", 2022, "windows", "desktop", "x86", "intel-uhd-730", 8, 8),
    ("Lenovo", "Legion Tower 7i (RTX 4080)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4080", 24, 32),
    ("Lenovo", "Legion Tower 5 (RTX 3070)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3070", 12, 16),
    ("Lenovo", "ThinkCentre M90t (UHD 770)", 2022, "windows", "desktop", "x86", "intel-uhd-770", 12, 16),
    ("Lenovo", "ThinkStation P360 (RTX A2000)", 2022, "windows", "desktop", "x86", "nvidia-rtx-a2000", 12, 32),
    ("ASUS", "ROG Strix GT35 (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Acer", "Predator Orion 7000 (RTX 4090)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4090", 24, 32),
    ("Acer", "Aspire TC (UHD 730)", 2022, "windows", "desktop", "x86", "intel-uhd-730", 6, 8),
    ("MSI", "Aegis RS (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070", 16, 32),
    ("MSI", "Infinite RS (RTX 3060)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060", 12, 16),
    ("System76", "Thelio Mira (RX 7600S)", 2023, "linux", "desktop", "x86", "amd-rx-7600s", 8, 32),
    ("System76", "Thelio Major (RTX 4070)", 2023, "linux", "desktop", "x86", "nvidia-rtx-4070", 24, 64),
    ("System76", "Meerkat (Iris Xe)", 2022, "linux", "desktop", "x86", "intel-iris-xe", 8, 16),
    ("System76", "Thelio Spark (UHD 770)", 2023, "linux", "desktop", "x86", "intel-uhd-770", 12, 16),
    ("Tuxedo", "Pulse 15 (RTX 4050)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4050", 12, 16),
    ("Slimbook", "Hero (Radeon 780M)", 2023, "linux", "laptop", "x86", "amd-radeon-780m", 8, 32),
    ("Star Labs", "Byte Mk II (Vega 8)", 2022, "linux", "desktop", "x86", "amd-vega-8", 4, 8),
    ("Purism", "Librem 15 (Vega 6)", 2022, "linux", "laptop", "x86", "amd-vega-6", 4, 8),
    ("Juno", "Gemini 17 (RTX 4060)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4060", 16, 32),
    ("Kubuntu Focus", "Ir14 (Iris Xe)", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Kubuntu Focus", "Ir16 (Iris Xe)", 2023, "linux", "laptop", "x86", "intel-iris-xe", 12, 32),
    ("Kubuntu Focus", "M2 (UHD 770)", 2022, "linux", "desktop", "x86", "intel-uhd-770", 12, 16),
    ("Entroware", "Apollo (Radeon 780M)", 2023, "linux", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Entroware", "Proteus (RTX 4070)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Entroware", "Aura (UHD 770)", 2022, "linux", "desktop", "x86", "intel-uhd-770", 12, 16),
    ("Laptop with Linux", "Clevo NV41 (Iris Xe)", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Laptop with Linux", "Clevo NS50 (Radeon 660M)", 2023, "linux", "laptop", "x86", "amd-radeon-660m", 6, 16),

    # ---------------- Budget and mid-range ----------------
    #
    # The machines most people actually own, and the ones that widen the
    # catalogue most: a 4 GB machine reports 4 where a 16 GB one reports 8,
    # and the low end is where the GPU and core counts vary most.  Every row
    # names a configuration the model was sold in.
    ("Acer", "Aspire 3 (A315-59)", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("Acer", "Aspire 3 (A315-24P)", 2023, "windows", "laptop", "x86", "amd-vega-3", 4, 8),
    ("Acer", "Aspire 5 (A515-57)", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 16),
    ("Acer", "Aspire 5 (A515-45)", 2022, "windows", "laptop", "x86", "amd-vega-8", 6, 8),
    ("Acer", "Aspire 5 (A517-53)", 2023, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 16),
    ("Acer", "Aspire 7 (A715-76G)", 2023, "windows", "laptop", "x86", "nvidia-rtx-2050", 8, 16),
    ("Acer", "Aspire 7 (A715-43G)", 2022, "windows", "laptop", "x86", "nvidia-gtx-1650", 8, 16),
    ("Acer", "Extensa 15 (EX215-54)", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("Acer", "Swift 1 (SF114-34)", 2022, "windows", "laptop", "x86", "intel-uhd-605", 4, 4),
    ("Acer", "Swift 3 (SF314-512)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Acer", "Swift Go 16 (SFG16-71)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Acer", "TravelMate P2 (TMP214-54)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Acer", "TravelMate P4 (TMP414-53)", 2023, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 16),
    ("Acer", "Chromebook 315 (CB315-4H)", 2022, "windows", "laptop", "x86", "intel-uhd-600", 4, 8),

    ("Lenovo", "IdeaPad 1 (15ADA7)", 2022, "windows", "laptop", "x86", "amd-vega-3", 4, 8),
    ("Lenovo", "IdeaPad 1 (15AMN7)", 2023, "windows", "laptop", "x86", "amd-vega-3", 4, 8),
    ("Lenovo", "IdeaPad 3 (15IAU7)", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("Lenovo", "IdeaPad 3 (15ITL6)", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 4),
    ("Lenovo", "IdeaPad Slim 3 (15IRU8)", 2023, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("Lenovo", "IdeaPad Slim 5 (16IRL8)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Lenovo", "IdeaPad Flex 5 (14ABR8)", 2023, "windows", "laptop", "x86", "amd-radeon-660m", 6, 16),
    ("Lenovo", "IdeaPad Gaming 3 (15ARH7)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 8, 8),
    ("Lenovo", "V15 G2 ITL", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("Lenovo", "V15 G3 ABA", 2023, "windows", "laptop", "x86", "amd-vega-3", 4, 8),
    ("Lenovo", "ThinkBook 14 G4 IAP", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Lenovo", "ThinkBook 15 G4 ABA", 2022, "windows", "laptop", "x86", "amd-radeon-660m", 6, 16),
    ("Lenovo", "ThinkPad E14 Gen 5", 2023, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 16),
    ("Lenovo", "ThinkPad E15 Gen 4 (UHD Graphics)", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("Lenovo", "ThinkPad L14 Gen 3 (UHD Graphics)", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("Lenovo", "Yoga 6 (13ABR8)", 2023, "windows", "laptop", "x86", "amd-radeon-660m", 6, 16),
    ("Lenovo", "Yoga 7i (14IRL8)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Lenovo", "Yoga 9i (14IRP8)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "ThinkPad X13 Yoga Gen 3", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),

    ("HP", "Laptop 15 (15s-fq5)", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("HP", "Laptop 15 (15s-eq3)", 2022, "windows", "laptop", "x86", "amd-vega-6", 4, 8),
    ("HP", "Laptop 17 (17-cn2)", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("HP", "250 G9", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("HP", "255 G9", 2022, "windows", "laptop", "x86", "amd-vega-6", 4, 8),
    ("HP", "250 G10", 2023, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("HP", "Pavilion 15 (15-eg2)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "Pavilion x360 14 (14-ek1)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "ProBook 440 G9", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 16),
    ("HP", "ProBook 450 G9 (UHD Graphics)", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 16),
    ("HP", "ProBook 445 G9", 2022, "windows", "laptop", "x86", "amd-radeon-660m", 6, 16),
    ("HP", "ProBook 450 G10 (UHD Graphics)", 2023, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 16),
    ("HP", "EliteBook 630 G9", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 16),
    ("HP", "EliteBook 650 G9", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 16),
    ("HP", "EliteBook 840 G11", 2024, "windows", "laptop", "x86", "intel-graphics-mtl", 12, 16),

    ("Dell", "Inspiron 15 3520", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 8),
    ("Dell", "Inspiron 15 3530 (UHD Graphics)", 2023, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 16),
    ("Dell", "Inspiron 15 3525", 2022, "windows", "laptop", "x86", "amd-vega-6", 4, 8),
    ("Dell", "Inspiron 14 3420", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("Dell", "Inspiron 16 5620", 2022, "windows", "laptop", "x86", "nvidia-mx550", 10, 16),
    ("Dell", "Vostro 3420", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("Dell", "Vostro 3525", 2022, "windows", "laptop", "x86", "amd-vega-6", 4, 8),
    ("Dell", "Vostro 3530", 2023, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("Dell", "Latitude 3330", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("Dell", "Latitude 3440", 2023, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("Dell", "Latitude 3540", 2023, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 16),
    ("Dell", "G15 5511", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 8, 8),
    ("Dell", "G15 5530", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 12, 16),

    ("ASUS", "VivoBook 15 (X515)", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("ASUS", "VivoBook 15 (X1502)", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 8),
    ("ASUS", "VivoBook 16 (X1605)", 2023, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 16),
    ("ASUS", "VivoBook Go 15 (E1504)", 2023, "windows", "laptop", "x86", "amd-vega-3", 4, 8),
    ("ASUS", "VivoBook 14 (M1405)", 2023, "windows", "laptop", "x86", "amd-radeon-660m", 6, 8),
    ("ASUS", "Zenbook 14 (UX3402)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("ASUS", "ExpertBook P1 (P1512)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("ASUS", "ExpertBook B1 (B1500)", 2023, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 16),
    ("ASUS", "TUF Gaming A15 (FA506)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 8, 8),
    ("ASUS", "TUF Gaming F17 (FX706)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050-ti", 8, 16),
    ("ASUS", "ROG Strix G15 (G513)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 8, 16),

    ("MSI", "Modern 14 (B12M)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("MSI", "Modern 15 (B13M)", 2023, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 16),
    ("MSI", "Prestige 14 Evo (A12M)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("MSI", "GF63 Thin (12UC)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 8, 8),
    ("MSI", "Thin GF63 (12VE)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 10, 16),
    ("MSI", "Katana 17 (B13V)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 10, 16),
    ("MSI", "GP66 Leopard (12UG)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070", 14, 16),

    ("Samsung", "Galaxy Book2 (15.6)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 8),
    ("Samsung", "Galaxy Book2 360", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Samsung", "Galaxy Book3 (15.6)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 8),
    ("Samsung", "Galaxy Book3 360", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Samsung", "Galaxy Book4 (15.6)", 2024, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 8),
    ("Samsung", "Galaxy Book4 360", 2024, "windows", "laptop", "x86", "intel-graphics-mtl", 12, 16),

    ("LG", "gram 14 (14Z90Q)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("LG", "gram 15 (15Z90Q)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("LG", "gram 16 (16Z90R)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("LG", "gram Style 14", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),

    ("Huawei", "MateBook D14 (2022)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 8, 8),
    ("Huawei", "MateBook D15 (2022)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 8),
    ("Huawei", "MateBook D16 (2023)", 2023, "windows", "laptop", "x86", "intel-uhd-graphics", 10, 16),
    ("Huawei", "MateBook 14 (2024)", 2024, "windows", "laptop", "x86", "intel-graphics-mtl", 12, 16),

    ("Gigabyte", "G5 (KE)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 8, 16),
    ("Gigabyte", "G6 (KF)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Gigabyte", "G5 (MF)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 10, 16),
    ("Gigabyte", "G6X (9KG)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 10, 16),


    # ---- added by the catalogue expansion ----
    # ---- Dell ----
    ("Dell", "XPS 13 9315 2-in-1", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "XPS 15 9520 (RTX 3050 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050-ti", 14, 16),
    ("Dell", "XPS 17 9720 (RTX 3050)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 14, 16),
    ("Dell", "XPS 15 9530 (RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 32),
    ("Dell", "XPS 17 9730 (RTX 4050)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 14, 16),
    ("Dell", "XPS 14 9440 (RTX 4050, 16GB)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4050", 12, 16),
    ("Dell", "XPS 16 9640 (RTX 4070)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Dell", "XPS 13 9350", 2025, "windows", "laptop", "x86", "intel-arc-140v", 8, 16),
    ("Dell", "XPS 13 9350 (32GB)", 2025, "windows", "laptop", "x86", "intel-arc-140v", 8, 32),
    ("Dell", "XPS 14 9450 (RTX 4050)", 2025, "windows", "laptop", "x86", "nvidia-rtx-4050", 16, 32),
    ("Dell", "XPS 16 9650 (RTX 5060)", 2025, "windows", "laptop", "x86", "nvidia-rtx-5060", 16, 16),
    ("Dell", "XPS 16 9650 (RTX 5070)", 2025, "windows", "laptop", "x86", "nvidia-rtx-5070", 16, 32),
    ("Dell", "XPS Desktop 8950 (RTX 3060 Ti)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060-ti", 12, 16),
    ("Dell", "XPS Desktop 8950 (RTX 3090)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3090", 12, 32),
    ("Dell", "XPS Desktop 8950 (RTX 3070)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3070-desktop", 12, 16),
    ("Dell", "XPS Desktop 8960", 2023, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 16, 16),
    ("Dell", "XPS Desktop 8960 (RTX 4090)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4090-desktop", 24, 64),
    ("Dell", "XPS 17 9720 (RTX 3050)", 2022, "linux", "laptop", "x86", "nvidia-rtx-3050", 14, 16),
    ("Dell", "Latitude 3430", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("Dell", "Latitude 3530", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Latitude 3450", 2024, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Latitude 3550", 2024, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Latitude 5330", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Latitude 5330 2-in-1", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Latitude 5530", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Dell", "Latitude 5340", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Latitude 5540", 2023, "windows", "laptop", "x86", "intel-iris-xe", 14, 16),
    ("Dell", "Latitude 5450", 2024, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Latitude 5550", 2024, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Latitude 7330", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 32),
    ("Dell", "Latitude 7430 (32GB)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 32),
    ("Dell", "Latitude 7530", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Dell", "Latitude 7340", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Latitude 7440 (32GB)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 14, 32),
    ("Dell", "Latitude 7540", 2023, "windows", "laptop", "x86", "intel-iris-xe", 14, 16),
    ("Dell", "Latitude 7350", 2024, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Latitude 7450", 2024, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Latitude 9330", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Precision 3470", 2022, "windows", "laptop", "x86", "nvidia-rtx-a500", 12, 16),
    ("Dell", "Precision 3570", 2022, "windows", "laptop", "x86", "nvidia-rtx-a500", 12, 16),
    ("Dell", "Precision 3570 (RTX A2000)", 2022, "windows", "laptop", "x86", "nvidia-rtx-a2000", 12, 32),
    ("Dell", "Precision 3570 (RTX A2000)", 2022, "linux", "laptop", "x86", "nvidia-rtx-a2000", 12, 32),
    ("Dell", "Precision 3571", 2022, "windows", "laptop", "x86", "nvidia-rtx-a1000", 14, 32),
    ("Dell", "Precision 5770", 2022, "windows", "laptop", "x86", "nvidia-rtx-a3000", 14, 32),
    ("Dell", "Precision 3480", 2023, "windows", "laptop", "x86", "nvidia-rtx-a500", 12, 16),
    ("Dell", "Precision 3580", 2023, "windows", "laptop", "x86", "nvidia-rtx-a500", 12, 16),
    ("Dell", "Precision 3581", 2023, "windows", "laptop", "x86", "nvidia-rtx-a1000", 14, 32),
    ("Dell", "Precision 5480 (RTX 1000 Ada)", 2023, "windows", "laptop", "x86", "nvidia-rtx-1000-ada", 14, 32),
    ("Dell", "Precision 7680", 2023, "windows", "laptop", "x86", "nvidia-rtx-4000-ada", 24, 64),
    ("Dell", "Precision 7780", 2023, "windows", "laptop", "x86", "nvidia-rtx-5000-ada", 24, 64),
    ("Dell", "Precision 5490", 2024, "windows", "laptop", "x86", "nvidia-rtx-2000-ada", 16, 32),
    ("Dell", "Alienware x15 R2", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 16),
    ("Dell", "Alienware x17 R2", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 32),
    ("Dell", "Alienware x14 R2", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 10, 16),
    ("Dell", "Alienware m16 (RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 16),
    ("Dell", "Alienware m18 (RTX 4080)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("Dell", "Alienware x16 (RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 14, 16),
    ("Dell", "Alienware m16 R2 (RTX 4070)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 16),
    ("Dell", "Alienware m18 R2 (RTX 4080)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("Dell", "Alienware m18 R2 (RTX 4090)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 64),
    ("Dell", "Alienware x16 R2 (RTX 4080)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4080", 16, 32),
    ("Dell", "Alienware 16 Aurora", 2025, "windows", "laptop", "x86", "nvidia-rtx-5060", 10, 16),
    ("Dell", "Alienware 16X Aurora", 2025, "windows", "laptop", "x86", "nvidia-rtx-5070", 14, 32),
    ("Dell", "Alienware 16 Area-51", 2025, "windows", "laptop", "x86", "nvidia-rtx-5090", 24, 64),
    ("Dell", "Alienware 18 Area-51", 2025, "windows", "laptop", "x86", "nvidia-rtx-5090", 24, 32),
    ("Dell", "G15 5511 (RTX 3060)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 8, 16),
    ("Dell", "G15 5520 (RTX 3050 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050-ti", 14, 16),
    ("Dell", "G15 5525", 2022, "windows", "laptop", "x86", "amd-rx-6600m", 8, 16),
    ("Dell", "G16 7620", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 12, 16),
    ("Dell", "G15 5530 (RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 16),
    ("Dell", "G15 5535", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("Dell", "G16 7630 (RTX 4050)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 14, 16),
    ("Dell", "Inspiron 14 5420", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Dell", "Inspiron 14 5425", 2022, "windows", "laptop", "x86", "amd-vega-8", 8, 16),
    ("Dell", "Inspiron 14 Plus 7420", 2022, "windows", "laptop", "x86", "intel-iris-xe", 14, 16),
    ("Dell", "Inspiron 16 5625", 2022, "windows", "laptop", "x86", "amd-vega-8", 8, 16),
    ("Dell", "Inspiron 16 Plus 7620", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 14, 16),
    ("Dell", "Inspiron 16 2-in-1 7620", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Dell", "Inspiron 15 3535", 2023, "windows", "laptop", "x86", "amd-vega-8", 8, 16),
    ("Dell", "Inspiron 14 5435", 2023, "windows", "laptop", "x86", "amd-vega-8", 8, 16),
    ("Dell", "Inspiron 16 5635", 2023, "windows", "laptop", "x86", "amd-vega-8", 8, 16),
    ("Dell", "Inspiron 14 5440", 2024, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Inspiron 16 5640", 2024, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "Inspiron 14 Plus 7440", 2024, "windows", "laptop", "x86", "nvidia-rtx-4050", 16, 16),
    ("Dell", "Inspiron 16 Plus 7640", 2024, "windows", "laptop", "x86", "nvidia-rtx-4050", 16, 16),
    ("Dell", "Vostro 3520", 2022, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("Dell", "Vostro 5620", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Dell", "Vostro 7620", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050-ti", 14, 16),
    ("Dell", "Vostro 3430", 2023, "windows", "laptop", "x86", "intel-uhd-graphics", 6, 8),
    ("Dell", "Vostro 5630", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Dell", "OptiPlex 3000", 2022, "windows", "desktop", "x86", "intel-uhd-730", 4, 8),
    ("Dell", "OptiPlex 5000", 2022, "windows", "desktop", "x86", "intel-uhd-770", 6, 16),
    ("Dell", "OptiPlex 7000", 2022, "windows", "desktop", "x86", "intel-uhd-770", 12, 16),
    ("Dell", "OptiPlex 7000 (UHD 730)", 2022, "windows", "desktop", "x86", "intel-uhd-730", 4, 8),
    ("Dell", "OptiPlex 7410 All-in-One", 2022, "windows", "desktop", "x86", "intel-uhd-770", 12, 16),
    ("Dell", "Precision 3460 Small Form Factor", 2022, "windows", "desktop", "x86", "intel-uhd-770", 12, 16),
    ("Dell", "Precision 3260 Compact", 2022, "windows", "desktop", "x86", "intel-uhd-770", 12, 16),
    ("Dell", "OptiPlex 5010", 2023, "windows", "desktop", "x86", "intel-uhd-770", 14, 16),
    ("Dell", "OptiPlex 5020", 2024, "windows", "desktop", "x86", "intel-uhd-770", 14, 16),
    ("Dell", "OptiPlex 7020", 2024, "windows", "desktop", "x86", "intel-uhd-770", 20, 16),
    ("Dell", "OptiPlex 7020 (UHD 730)", 2024, "windows", "desktop", "x86", "intel-uhd-730", 4, 8),
    ("Dell", "Alienware Aurora R13 (RTX 3090)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3090", 16, 32),
    ("Dell", "Alienware Aurora R14 (RX 6900 XT)", 2022, "windows", "desktop", "x86", "amd-rx-6900-xt", 12, 32),
    ("Dell", "Alienware Aurora R16 (RTX 4070 Ti SUPER)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4070-ti-super", 20, 32),
    ("Dell", "Alienware Aurora R16 (RTX 4080 SUPER)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4080-super", 24, 32),
    ("Alienware", "Aurora R16 (RTX 4060)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 20, 16),

    # ---- HP ----
    ("HP", "Spectre x360 13.5 (2023)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "Spectre x360 16 (2023)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 14, 16),
    ("HP", "Spectre x360 16 (2023, RTX 4050)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 14, 16),
    ("HP", "Spectre x360 16 (2024)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4050", 16, 16),
    ("HP", "Spectre x360 16 (2024, 32GB)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4050", 16, 32),
    ("HP", "Spectre Foldable 17 (2023)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "Envy x360 13 (2022)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "Envy x360 13 (2023)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "Envy x360 15 (2022, Iris Xe)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("HP", "Envy x360 15 (2023)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "Envy 16 (2022, Iris Xe)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("HP", "Envy 17 (2022)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "Envy 17 (2023)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "Envy Move 23.8 (2023)", 2023, "windows", "desktop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "Envy Desktop TE02 (2023, RTX 4060)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 16, 16),
    ("HP", "Pavilion Aero 13 (2022)", 2022, "windows", "laptop", "x86", "amd-vega-8", 8, 16),
    ("HP", "Pavilion Aero 13 (2023)", 2023, "windows", "laptop", "x86", "amd-radeon-680m", 8, 16),
    ("HP", "Pavilion Aero 13 (2023, Ryzen 5)", 2023, "windows", "laptop", "x86", "amd-radeon-660m", 6, 16),
    ("HP", "Pavilion Plus 14 (2022, Iris Xe)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "Pavilion 15 (2023)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "Pavilion x360 14 (2023)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 8),
    ("HP", "OMEN 16 (2023)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 16),
    ("HP", "OMEN 16 (2023, RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("HP", "OMEN 16 (2024)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 16),
    ("HP", "OMEN 16 (2025)", 2025, "windows", "laptop", "x86", "nvidia-rtx-5070", 24, 32),
    ("HP", "OMEN 16 (2025, RTX 5060)", 2025, "windows", "laptop", "x86", "nvidia-rtx-5060", 24, 16),
    ("HP", "OMEN Max 16 (2025)", 2025, "windows", "laptop", "x86", "nvidia-rtx-5070", 24, 32),
    ("HP", "OMEN Max 16 (2025, RTX 5080)", 2025, "windows", "laptop", "x86", "nvidia-rtx-5080", 24, 32),
    ("HP", "OMEN Transcend 14 (2024)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 16),
    ("HP", "OMEN Transcend 14 (2024, RTX 4070)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("HP", "OMEN Transcend 16 (2023, RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 16),
    ("HP", "OMEN Transcend 16 (2024)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("HP", "OMEN 17 (2023, RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 16),
    ("HP", "OMEN 17 (2023, RTX 4080)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 16, 32),
    ("HP", "OMEN 25L (2022)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060-desktop", 6, 16),
    ("HP", "OMEN 40L (2023, RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 16),
    ("HP", "OMEN 45L (2023, RTX 4080)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4080-desktop", 24, 32),
    ("HP", "OMEN 45L (2024, RTX 4090)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4090-desktop", 24, 64),
    ("HP", "Victus 15 (2022, RTX 3050)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 8, 8),
    ("HP", "Victus 15 (2023)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 8, 8),
    ("HP", "Victus 16 (2022)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("HP", "Victus 16 (2023, RTX 3050)", 2023, "windows", "laptop", "x86", "nvidia-rtx-3050", 12, 16),
    ("HP", "Victus 16 (2024)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 16),
    ("HP", "Victus 16 (2024, RTX 4050)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4050", 16, 16),
    ("HP", "EliteBook 830 G9", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "EliteBook 830 G10", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "EliteBook 830 G10", 2023, "linux", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "EliteBook 860 G9", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "EliteBook 860 G10", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 32),
    ("HP", "EliteBook 860 G10", 2023, "linux", "laptop", "x86", "intel-iris-xe", 12, 32),
    ("HP", "EliteBook x360 830 G10", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "EliteBook 1040 G10", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 32),
    ("HP", "EliteBook 640 G9", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "EliteBook 640 G10", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "EliteBook 640 G10", 2023, "linux", "laptop", "x86", "intel-iris-xe", 10, 32),
    ("HP", "EliteBook 660 G9", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "EliteBook 660 G10", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "EliteBook 650 G10", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "EliteBook 630 G10", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 8),
    ("HP", "ProBook 430 G9", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "ProBook 455 G9", 2022, "windows", "laptop", "x86", "amd-vega-8", 8, 16),
    ("HP", "ProBook 430 G10", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "ProBook 440 G10", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "ProBook 440 G10", 2023, "linux", "laptop", "x86", "intel-iris-xe", 10, 32),
    ("HP", "ZBook Firefly 14 G9", 2022, "windows", "laptop", "x86", "nvidia-rtx-a500", 10, 16),
    ("HP", "ZBook Firefly 14 G9 (Iris Xe)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "ZBook Firefly 16 G9", 2022, "windows", "laptop", "x86", "nvidia-rtx-a500", 10, 32),
    ("HP", "ZBook Firefly 14 G10", 2023, "windows", "laptop", "x86", "nvidia-rtx-a500", 10, 32),
    ("HP", "ZBook Firefly 14 G10 (Iris Xe)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "ZBook Firefly 16 G10", 2023, "windows", "laptop", "x86", "nvidia-rtx-a1000", 12, 32),
    ("HP", "ZBook Power G9", 2022, "windows", "laptop", "x86", "nvidia-rtx-a1000", 14, 32),
    ("HP", "ZBook Power G9", 2022, "linux", "laptop", "x86", "nvidia-rtx-a2000", 14, 32),
    ("HP", "ZBook Power 16 G11", 2024, "windows", "laptop", "x86", "nvidia-rtx-2000-ada", 16, 32),
    ("HP", "ZBook Power 16 G11 (RTX 3000 Ada)", 2024, "windows", "laptop", "x86", "nvidia-rtx-3000-ada", 16, 64),
    ("HP", "ZBook Power 16 G11 A (Radeon 780M)", 2024, "windows", "laptop", "x86", "amd-radeon-780m", 8, 32),
    ("HP", "ZBook Studio G9 (RTX A3000)", 2022, "windows", "laptop", "x86", "nvidia-rtx-a3000", 14, 32),
    ("HP", "ZBook Studio G11", 2024, "windows", "laptop", "x86", "nvidia-rtx-2000-ada", 16, 32),
    ("HP", "ZBook Fury 16 G9 (RTX A3000)", 2022, "windows", "laptop", "x86", "nvidia-rtx-a3000", 16, 32),
    ("HP", "ZBook Fury 16 G10", 2023, "windows", "laptop", "x86", "nvidia-rtx-4000-ada", 24, 64),
    ("HP", "ZBook Fury 16 G10 (RTX 5000 Ada)", 2023, "windows", "laptop", "x86", "nvidia-rtx-5000-ada", 24, 64),
    ("HP", "ZBook Fury 16 G11", 2024, "windows", "laptop", "x86", "nvidia-rtx-5000-ada", 24, 64),
    ("HP", "ZBook Fury 16 G11 (RTX 3500 Ada)", 2024, "windows", "laptop", "x86", "nvidia-rtx-3500-ada", 24, 32),
    ("HP", "EliteDesk 800 G9 (UHD 770)", 2022, "windows", "desktop", "x86", "intel-uhd-770", 12, 16),
    ("HP", "EliteDesk 800 G9 (32GB)", 2022, "windows", "desktop", "x86", "intel-uhd-770", 16, 32),
    ("HP", "EliteDesk 800 G9 Mini", 2022, "windows", "desktop", "x86", "intel-uhd-770", 12, 16),
    ("HP", "EliteDesk 600 G9", 2022, "windows", "desktop", "x86", "intel-uhd-770", 12, 16),
    ("HP", "EliteDesk 600 G9 (UHD 730)", 2022, "windows", "desktop", "x86", "intel-uhd-730", 6, 8),
    ("HP", "ProDesk 400 G9 (UHD 770)", 2022, "windows", "desktop", "x86", "intel-uhd-770", 6, 16),
    ("HP", "ProDesk 400 G9 (UHD 730)", 2022, "windows", "desktop", "x86", "intel-uhd-730", 6, 8),
    ("HP", "ProDesk 400 G9 Mini", 2022, "windows", "desktop", "x86", "intel-uhd-770", 6, 16),
    ("HP", "ProDesk 600 G9", 2022, "windows", "desktop", "x86", "intel-uhd-770", 6, 16),
    ("HP", "Z1 G9 Tower", 2022, "windows", "desktop", "x86", "nvidia-rtx-a2000", 16, 32),
    ("HP", "Z2 Mini G9", 2022, "windows", "desktop", "x86", "nvidia-rtx-a2000", 12, 32),
    ("HP", "Z2 Tower G9 (RTX A5000)", 2022, "windows", "desktop", "x86", "nvidia-rtx-a5000", 16, 64),
    ("HP", "Z4 G5", 2023, "windows", "desktop", "x86", "nvidia-rtx-a4000", 12, 64),

    # ---- Lenovo ----
    ("Lenovo", "ThinkPad X1 Carbon Gen 13", 2025, "windows", "laptop", "x86", "intel-arc-140v", 8, 16),
    ("Lenovo", "ThinkPad X1 2-in-1 Gen 10", 2025, "windows", "laptop", "x86", "intel-arc-140v", 8, 32),
    ("Lenovo", "ThinkPad X1 Fold 16 Gen 1", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Lenovo", "ThinkPad X1 Nano Gen 2", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 32),
    ("Lenovo", "ThinkPad X1 Nano Gen 3", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "ThinkPad X1 Nano Gen 2", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "ThinkPad X13 Gen 3", 2022, "windows", "laptop", "x86", "intel-iris-xe", 14, 16),
    ("Lenovo", "ThinkPad X13 Gen 3 (AMD)", 2022, "windows", "laptop", "x86", "amd-radeon-680m", 8, 16),
    ("Lenovo", "ThinkPad X13 Gen 4", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 32),
    ("Lenovo", "ThinkPad X13 Gen 4 (AMD)", 2023, "windows", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Lenovo", "ThinkPad X13 Gen 5 (AMD)", 2024, "windows", "laptop", "x86", "amd-radeon-780m", 8, 32),
    ("Lenovo", "ThinkPad X13 Yoga Gen 4", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Lenovo", "ThinkPad X13 Gen 3", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "ThinkPad X13 Gen 4 (AMD)", 2023, "linux", "laptop", "x86", "amd-radeon-780m", 8, 32),
    ("Lenovo", "ThinkPad Z16 Gen 1", 2022, "windows", "laptop", "x86", "amd-radeon-680m", 8, 32),
    ("Lenovo", "ThinkPad T14s Gen 3", 2022, "windows", "laptop", "x86", "intel-iris-xe", 14, 16),
    ("Lenovo", "ThinkPad T14s Gen 3", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 32),
    ("Lenovo", "ThinkPad T14 Gen 4 (AMD)", 2023, "windows", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Lenovo", "ThinkPad T14 Gen 5 (AMD)", 2024, "windows", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Lenovo", "ThinkPad T14 Gen 6 (AMD)", 2025, "windows", "laptop", "x86", "amd-radeon-860m", 8, 16),
    ("Lenovo", "ThinkPad T14s Gen 5 (AMD)", 2024, "windows", "laptop", "x86", "amd-radeon-780m", 8, 32),
    ("Lenovo", "ThinkPad T14s Gen 6 (AMD)", 2025, "windows", "laptop", "x86", "amd-radeon-860m", 8, 32),
    ("Lenovo", "ThinkPad T16 Gen 1", 2022, "windows", "laptop", "x86", "intel-iris-xe", 14, 32),
    ("Lenovo", "ThinkPad T16 Gen 2 (AMD)", 2023, "windows", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Lenovo", "ThinkPad T16 Gen 1", 2022, "linux", "laptop", "x86", "intel-iris-xe", 14, 32),
    ("Lenovo", "ThinkPad T14 Gen 4 (AMD)", 2023, "linux", "laptop", "x86", "amd-radeon-780m", 8, 32),
    ("Lenovo", "ThinkPad T14 Gen 5 (AMD)", 2024, "linux", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Lenovo", "ThinkPad P14s Gen 3", 2022, "windows", "laptop", "x86", "intel-iris-xe", 14, 32),
    ("Lenovo", "ThinkPad P14s Gen 4", 2023, "windows", "laptop", "x86", "intel-iris-xe", 14, 32),
    ("Lenovo", "ThinkPad P14s Gen 4 (AMD)", 2023, "windows", "laptop", "x86", "amd-radeon-780m", 8, 64),
    ("Lenovo", "ThinkPad P14s Gen 4 (RTX A500)", 2023, "windows", "laptop", "x86", "nvidia-rtx-a500", 12, 64),
    ("Lenovo", "ThinkPad P14s Gen 5 (AMD)", 2024, "windows", "laptop", "x86", "amd-radeon-780m", 8, 64),
    ("Lenovo", "ThinkPad P16s Gen 1", 2022, "windows", "laptop", "x86", "intel-iris-xe", 14, 32),
    ("Lenovo", "ThinkPad P16s Gen 2", 2023, "windows", "laptop", "x86", "intel-iris-xe", 14, 16),
    ("Lenovo", "ThinkPad P16s Gen 2 (RTX A500)", 2023, "windows", "laptop", "x86", "nvidia-rtx-a500", 12, 32),
    ("Lenovo", "ThinkPad P16s Gen 3 (AMD)", 2024, "windows", "laptop", "x86", "amd-radeon-780m", 8, 32),
    ("Lenovo", "ThinkPad P15v Gen 3", 2022, "windows", "laptop", "x86", "nvidia-rtx-a2000", 14, 32),
    ("Lenovo", "ThinkPad P16 Gen 2", 2023, "windows", "laptop", "x86", "nvidia-rtx-5000-ada", 24, 64),
    ("Lenovo", "ThinkPad P16 Gen 2 (RTX 2000 Ada)", 2023, "windows", "laptop", "x86", "nvidia-rtx-2000-ada", 24, 32),
    ("Lenovo", "ThinkPad P1 Gen 6", 2023, "windows", "laptop", "x86", "nvidia-rtx-2000-ada", 16, 32),
    ("Lenovo", "ThinkPad P1 Gen 6 (RTX 4080)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 16, 64),
    ("Lenovo", "ThinkPad P1 Gen 7", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Lenovo", "ThinkPad P16v Gen 2", 2024, "windows", "laptop", "x86", "nvidia-rtx-2000-ada", 16, 64),
    ("Lenovo", "ThinkPad P16v Gen 2 (RTX 1000 Ada)", 2024, "windows", "laptop", "x86", "nvidia-rtx-1000-ada", 16, 32),
    ("Lenovo", "ThinkPad P14s Gen 3", 2022, "linux", "laptop", "x86", "intel-iris-xe", 14, 16),
    ("Lenovo", "ThinkPad P15v Gen 3", 2022, "linux", "laptop", "x86", "nvidia-rtx-a2000", 14, 64),
    ("Lenovo", "ThinkPad P16s Gen 2", 2023, "linux", "laptop", "x86", "intel-iris-xe", 12, 32),
    ("Lenovo", "ThinkPad L13 Gen 3", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 8),
    ("Lenovo", "ThinkPad L13 Yoga Gen 3", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Lenovo", "ThinkPad L13 Gen 4", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Lenovo", "ThinkPad L13 Yoga Gen 4", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 32),
    ("Lenovo", "ThinkPad L14 Gen 4", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 8),
    ("Lenovo", "ThinkPad L15 Gen 4", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Lenovo", "ThinkPad E16 Gen 1", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 8),
    ("Lenovo", "ThinkPad L14 Gen 4", 2023, "linux", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Lenovo", "ThinkPad E16 Gen 1", 2023, "linux", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Lenovo", "ThinkBook 13s Gen 4", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 8),
    ("Lenovo", "ThinkBook Plus Gen 3", 2022, "windows", "laptop", "x86", "intel-iris-xe", 14, 16),
    ("Lenovo", "ThinkBook 16p Gen 4", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 14, 16),
    ("Lenovo", "ThinkBook 16p Gen 5", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 32),
    ("Lenovo", "IdeaPad Slim 5 14IRL8", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Lenovo", "IdeaPad Slim 5 16IRH8", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 8),
    ("Lenovo", "IdeaPad Pro 5 16IRH8", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 14, 16),
    ("Lenovo", "IdeaPad Pro 5 14APH8", 2023, "windows", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Lenovo", "IdeaPad Pro 5 16APH8", 2023, "windows", "laptop", "x86", "amd-radeon-780m", 8, 32),
    ("Lenovo", "IdeaPad Slim 5 16AHP9", 2024, "windows", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Lenovo", "IdeaPad Gaming 3 15IAH7", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 8, 8),
    ("Lenovo", "Yoga Book 9i", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Lenovo", "Yoga Pro 7", 2023, "windows", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Lenovo", "Yoga Pro 7i", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 14, 16),
    ("Lenovo", "Yoga Pro 9i", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 32),
    ("Lenovo", "Yoga Pro 9i (RTX 4050)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 14, 32),
    ("Lenovo", "Yoga Slim 7i Aura Edition", 2025, "windows", "laptop", "x86", "intel-arc-140v", 8, 16),
    ("Lenovo", "Legion 5i Gen 7", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Lenovo", "Legion 5 Gen 7", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 8, 16),
    ("Lenovo", "Legion 7 Gen 7", 2022, "windows", "laptop", "x86", "amd-rx-6850m-xt", 8, 32),
    ("Lenovo", "Legion Slim 7 Gen 8", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("Lenovo", "Legion Slim 5 Gen 8", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 8, 32),
    ("Lenovo", "Legion Slim 5i Gen 8", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 14, 16),
    ("Lenovo", "Legion Pro 5 Gen 8", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("Lenovo", "Legion Pro 7 Gen 8", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 16, 32),
    ("Lenovo", "Legion 9i Gen 8", 2023, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 64),
    ("Lenovo", "Legion 9i Gen 8 (RTX 4080)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("Lenovo", "Legion Pro 5i Gen 9", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 16),
    ("Lenovo", "Legion Pro 5i Gen 9 (RTX 4060)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 16),
    ("Lenovo", "Legion Pro 7i Gen 9", 2024, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 32),
    ("Lenovo", "Legion Pro 7i Gen 9 (RTX 4080)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("Lenovo", "Legion Pro 5i Gen 10", 2025, "windows", "laptop", "x86", "nvidia-rtx-5070", 24, 32),
    ("Lenovo", "Legion Pro 7i Gen 10", 2025, "windows", "laptop", "x86", "nvidia-rtx-5090", 24, 32),
    ("Lenovo", "LOQ 15IRH8", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 10, 16),
    ("Lenovo", "LOQ 15APH8", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("Lenovo", "LOQ 16IRH8", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 10, 8),
    ("Lenovo", "LOQ 15AHP9", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("Lenovo", "LOQ 15IAX9", 2024, "windows", "laptop", "x86", "nvidia-rtx-4050", 8, 16),
    ("Lenovo", "Legion Tower 5i Gen 7", 2022, "windows", "desktop", "x86", "nvidia-rtx-3070-desktop", 12, 16),
    ("Lenovo", "Legion Tower 7i Gen 7", 2022, "windows", "desktop", "x86", "nvidia-rtx-3080-desktop", 16, 32),
    ("Lenovo", "Legion Tower 5 Gen 8", 2023, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 8, 16),
    ("Lenovo", "Legion Tower 5 Gen 8 (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 8, 16),
    ("Lenovo", "Legion Tower 5i Gen 8", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 16),
    ("Lenovo", "Legion Tower 5i Gen 8 (RTX 4060)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 16, 16),
    ("Lenovo", "Legion Tower 7i Gen 8", 2023, "windows", "desktop", "x86", "nvidia-rtx-4090-desktop", 24, 32),
    ("Lenovo", "LOQ Tower 17IRB8", 2023, "windows", "desktop", "x86", "nvidia-rtx-3060-desktop", 10, 16),
    ("Lenovo", "IdeaCentre Gaming 5 17IAB7", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060-desktop", 6, 16),
    ("Lenovo", "ThinkCentre M70q Gen 4", 2023, "windows", "desktop", "x86", "intel-uhd-730", 10, 16),
    ("Lenovo", "ThinkCentre M90q Gen 4", 2023, "windows", "desktop", "x86", "intel-uhd-770", 14, 32),
    ("Lenovo", "ThinkCentre Neo 50s Gen 3", 2022, "windows", "desktop", "x86", "intel-uhd-730", 6, 8),
    ("Lenovo", "ThinkCentre Neo 50t Gen 3", 2022, "windows", "desktop", "x86", "intel-uhd-730", 6, 16),
    ("Lenovo", "ThinkStation P3 Tower", 2023, "windows", "desktop", "x86", "nvidia-rtx-a2000", 16, 32),
    ("Lenovo", "ThinkStation P360 Ultra", 2022, "windows", "desktop", "x86", "nvidia-rtx-a2000", 12, 32),
    ("Lenovo", "ThinkCentre M70q Gen 4", 2023, "linux", "desktop", "x86", "intel-uhd-730", 10, 16),
    ("Lenovo", "ThinkStation P3 Tower", 2023, "linux", "desktop", "x86", "nvidia-rtx-a2000", 16, 64),

    # ---- ASUS and Acer ----
    ("ASUS", "Zenbook 14 OLED (UX3402)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("ASUS", "Zenbook 14 OLED (UX3402)", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("ASUS", "Zenbook 14X OLED (UX5400)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 14, 16),
    ("ASUS", "Zenbook S 13 Flip OLED (UP5302)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("ASUS", "Zenbook 17 Fold OLED (UX9702)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("ASUS", "Zenbook Pro 14 Duo OLED (UX8402)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050-ti", 14, 16),
    ("ASUS", "Zenbook Pro 16X OLED (UX7602)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 32),
    ("ASUS", "Zenbook S 13 OLED (UX5304)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("ASUS", "Zenbook S 13 OLED (UX5304)", 2023, "linux", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("ASUS", "Zenbook 14X OLED (UX3404)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 14, 16),
    ("ASUS", "Zenbook 14 Flip OLED (UP3404)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("ASUS", "Zenbook 15 OLED (UM3504)", 2023, "windows", "laptop", "x86", "amd-radeon-680m", 8, 16),
    ("ASUS", "Zenbook Pro 14 OLED (UX6404)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 32),
    ("ASUS", "Zenbook Pro 14 OLED (UX6404, RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 14, 32),
    ("ASUS", "Zenbook 14 OLED (UM3406)", 2024, "windows", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("ASUS", "Zenbook 14 OLED (UM3406)", 2024, "linux", "laptop", "x86", "amd-radeon-780m", 8, 32),
    ("ASUS", "Zenbook S 14 (UX5406)", 2024, "windows", "laptop", "x86", "intel-arc-140v", 8, 16),
    ("ASUS", "Zenbook S 16 (UM5606)", 2024, "windows", "laptop", "x86", "amd-radeon-890m", 12, 24),
    ("ASUS", "VivoBook S 14 OLED (S3402)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 14, 16),
    ("ASUS", "VivoBook S 14X OLED (S5402)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 14, 16),
    ("ASUS", "VivoBook Pro 15 OLED (RTX 3060)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("ASUS", "VivoBook Pro 16 (K6602)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 14, 16),
    ("ASUS", "VivoBook Pro 14 OLED (K6400)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 12, 16),
    ("ASUS", "VivoBook Pro 16 OLED (K6604, RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 16),
    ("ASUS", "VivoBook 15 (X1504)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 8),
    ("ASUS", "VivoBook 15 (X1504)", 2023, "linux", "laptop", "x86", "intel-iris-xe", 10, 8),
    ("ASUS", "VivoBook 14 (X1404)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("ASUS", "ProArt Studiobook 16 OLED (H7604, RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 24, 32),
    ("ASUS", "ProArt P16 (RTX 4060)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 12, 32),
    ("ASUS", "ProArt PX13 (HN7306)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 12, 32),
    ("ASUS", "ProArt P16 (RTX 4070)", 2024, "linux", "laptop", "x86", "nvidia-rtx-4070", 12, 32),
    ("ASUS", "ROG Zephyrus G14 (GA402RK, RX 6800S)", 2022, "windows", "laptop", "x86", "amd-rx-6800s", 8, 16),
    ("ASUS", "ROG Zephyrus G14 (GA402RJ, RX 6700S)", 2022, "windows", "laptop", "x86", "amd-rx-6700s", 8, 16),
    ("ASUS", "ROG Flow X16 (GV601)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 8, 16),
    ("ASUS", "ROG Strix G15 (G513RM)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 8, 16),
    ("ASUS", "ROG Zephyrus Duo 16 (2023, RTX 4090)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4090", 16, 32),
    ("ASUS", "ROG Zephyrus M16 (GU604)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 14, 32),
    ("ASUS", "ROG Strix G16 (G614)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 16),
    ("ASUS", "ROG Strix G16 (G614)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4060", 14, 32),
    ("ASUS", "ROG Strix G16 (2023, RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 24, 16),
    ("ASUS", "ROG Strix G18 (G814)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("ASUS", "ROG Strix G17 (G713PI)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 16),
    ("ASUS", "ROG Strix SCAR 16 (G634)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 32),
    ("ASUS", "ROG Strix SCAR 17 (G733PZ)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 16, 32),
    ("ASUS", "ROG Flow X13 (GV302)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("ASUS", "ROG Flow Z13 (2023, RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 16),
    ("ASUS", "ROG Zephyrus G14 (2024, RTX 4060)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("ASUS", "ROG Zephyrus G16 (2024, RTX 4080)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4080", 16, 32),
    ("ASUS", "ROG Strix SCAR 16 (2024, RTX 4090)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 32),
    ("ASUS", "ROG Ally X (2024)", 2024, "windows", "laptop", "x86", "amd-radeon-780m", 8, 24),
    ("ASUS", "ROG Zephyrus G14 (2025, RTX 5080)", 2025, "windows", "laptop", "x86", "nvidia-rtx-5080", 12, 32),
    ("ASUS", "ROG Zephyrus G16 (2025, RTX 5090)", 2025, "windows", "laptop", "x86", "nvidia-rtx-5090", 16, 32),
    ("ASUS", "ROG Strix G16 (2025, RTX 5070 Ti)", 2025, "windows", "laptop", "x86", "nvidia-rtx-5070-ti", 24, 16),
    ("ASUS", "ROG Strix SCAR 18 (2025, RTX 5090)", 2025, "windows", "laptop", "x86", "nvidia-rtx-5090", 24, 32),
    ("ASUS", "TUF Gaming A15 (FA507)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("ASUS", "TUF Gaming A15 (FA507)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("ASUS", "TUF Gaming A15 (2023, RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 8, 16),
    ("ASUS", "TUF Gaming F15 (FX507)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 10, 16),
    ("ASUS", "TUF Gaming A16 (FA617, RX 7700S)", 2023, "windows", "laptop", "x86", "amd-rx-7700s", 8, 16),
    ("ASUS", "TUF Gaming A17 (FA707)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 8, 16),
    ("ASUS", "TUF Dash F15 (FX517)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 10, 16),
    ("ASUS", "TUF Dash F15 (FX517)", 2022, "linux", "laptop", "x86", "nvidia-rtx-3060", 10, 16),
    ("ASUS", "TUF Gaming A14 (FA401)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 12, 32),
    ("ASUS", "ExpertBook B9 OLED (B9403)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 32),
    ("ASUS", "ExpertBook B9 OLED (B9403)", 2023, "linux", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("ASUS", "ExpertBook B5 (B5602)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("ASUS", "ROG Strix G13CH (RTX 4060)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 16, 16),
    ("ASUS", "ROG Strix G16CH (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 32),
    ("ASUS", "ROG Strix GT15 (RTX 3060)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060-desktop", 6, 16),
    ("Acer", "Swift 5 (SF514-56T)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Acer", "Swift 5 (SF514-56T)", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Acer", "Swift X 16 (SFX16-61G)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 12, 16),
    ("Acer", "Swift X 14 (SFX14-71G)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4050", 12, 16),
    ("Acer", "Swift Edge 16 (SFE16-43)", 2023, "windows", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Acer", "Swift Edge 16 (SFE16-43)", 2023, "linux", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Acer", "Swift X 16 (SFX16-61G)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4050", 12, 16),
    ("Acer", "Swift 14 AI (SF14-51)", 2024, "windows", "laptop", "x86", "intel-arc-140v", 8, 32),
    ("Acer", "Swift 16 AI (SF16-51)", 2024, "windows", "laptop", "x86", "intel-arc-140v", 8, 16),
    ("Acer", "Swift X 14 (SFX14-72G)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("Acer", "Aspire Vero (AV15-53P)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Acer", "Aspire Vero (AV15-53P)", 2023, "linux", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Acer", "Aspire 5 (A515-58P)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Acer", "Aspire 5 (A515-58P)", 2023, "linux", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Acer", "Aspire Go 15 (AG15-31P)", 2024, "windows", "laptop", "x86", "intel-uhd-graphics", 8, 8),
    ("Acer", "Nitro 5 (AN515-58)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 12, 16),
    ("Acer", "Nitro 5 (AN515-58)", 2022, "linux", "laptop", "x86", "nvidia-rtx-3060", 12, 16),
    ("Acer", "Nitro 5 (AN515-46)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 8, 16),
    ("Acer", "Nitro 5 (AN515-58, RTX 3070 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 16),
    ("Acer", "Nitro 5 (AN517-55)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Acer", "Nitro 16 (AN16-41)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("Acer", "Nitro 16 (AN16-41)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("Acer", "Nitro 17 (AN17-41)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("Acer", "Nitro 17 (AN17-51)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 16),
    ("Acer", "Nitro 17 (AN17-51)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4060", 14, 16),
    ("Acer", "Nitro V 15 (ANV15-41)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4050", 6, 16),
    ("Acer", "Nitro V 15 (ANV15-41, RTX 2050)", 2024, "windows", "laptop", "x86", "nvidia-rtx-2050", 6, 8),
    ("Acer", "Nitro V 15 (ANV15-51, RTX 4060)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("Acer", "Nitro V 16 (ANV16-41)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("Acer", "Nitro V 16 (ANV16-41)", 2024, "linux", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("Acer", "Predator Helios Neo 16 (PHN16-71)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 16),
    ("Acer", "Predator Helios Neo 16 (PHN16-71)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4060", 16, 16),
    ("Acer", "Predator Helios Neo 16 (PHN16-71, RTX 4050)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 14, 16),
    ("Acer", "Predator Helios 16 (PH16-71, RTX 4080)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("Acer", "Predator Helios 18 (PH18-71, RTX 4080)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("Acer", "Predator Triton 17X (PTX17-71)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 32),
    ("Acer", "Predator Triton 500 SE (RTX 3080 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 14, 32),
    ("Acer", "Predator Helios Neo 16 (PHN16-72)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 24, 16),
    ("Acer", "Predator Helios Neo 16 (PHN16-72)", 2024, "linux", "laptop", "x86", "nvidia-rtx-4070", 24, 16),
    ("Acer", "Predator Helios 16 (PH16-72)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("Acer", "Predator Helios 18 (PH18-72)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 32),
    ("Acer", "TravelMate P2 (TMP215-54)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Acer", "TravelMate P2 (TMP215-54)", 2022, "linux", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Acer", "TravelMate P6 (TMP614-53)", 2023, "linux", "laptop", "x86", "intel-iris-xe", 10, 32),
    ("Acer", "TravelMate P4 (TMP414-52)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Acer", "TravelMate P4 Spin (TMP414RN-53)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Acer", "TravelMate P6 (TMP614-53)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Acer", "Chromebook Spin 714 (CP714-1WN)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 8),
    ("Acer", "Chromebook Spin 714 (CP714-2W)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 8),
    ("Acer", "Chromebook 516 GE (CBG516-1H)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 8),
    ("Acer", "Chromebook Vero 514 (CBV514-1H)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 8),
    ("Acer", "Predator Orion 7000 (PO7-640, RTX 3080)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3080-desktop", 12, 32),
    ("Acer", "Predator Orion 7000 (PO7-650, RTX 4080)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4080-desktop", 24, 32),
    ("Acer", "Predator Orion 5000 (PO5-650)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 32),
    ("Acer", "Predator Orion 3000 (PO3-650)", 2023, "windows", "desktop", "x86", "nvidia-rtx-3060-desktop", 10, 16),
    ("Acer", "Nitro 50 (N50-640)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060-desktop", 6, 16),
    ("Acer", "Nitro 50 (N50-650)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 10, 16),

    # ---- MSI, Razer, Samsung, LG, Microsoft, Framework ----
    ("MSI", "Modern 14 C13M", 2023, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("MSI", "Modern 15 B12M", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("MSI", "Prestige 13 Evo A13M", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("MSI", "Prestige 16 Evo A13M", 2023, "windows", "laptop", "x86", "intel-iris-xe", 14, 32),
    ("MSI", "Prestige 15 A12SC", 2022, "windows", "laptop", "x86", "nvidia-gtx-1650", 14, 16),
    ("MSI", "Prestige 14 AI Evo C1MG", 2024, "windows", "laptop", "x86", "intel-arc-graphics", 16, 32),
    ("MSI", "Prestige 16 AI Studio B1VG", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("MSI", "Summit E13 Flip Evo A12MT", 2022, "windows", "laptop", "x86", "intel-iris-xe", 14, 16),
    ("MSI", "Summit E14 Evo A12M", 2022, "windows", "laptop", "x86", "intel-iris-xe", 14, 16),
    ("MSI", "Summit E16 Flip Evo A13M", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("MSI", "Katana GF76 12UD", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050-ti", 10, 16),
    ("MSI", "Sword 15 A12UE", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 10, 16),
    ("MSI", "Sword 15 A12UC", 2022, "windows", "laptop", "x86", "nvidia-rtx-3050", 8, 8),
    ("MSI", "Sword 17 A12VE", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 10, 16),
    ("MSI", "Pulse GL76 12UEK", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("MSI", "Pulse 15 B13V", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 10, 16),
    ("MSI", "Pulse 16 AI", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 16),
    ("MSI", "Crosshair 15 B12UGSZ", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 16),
    ("MSI", "Cyborg 15 A12VE", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 10, 16),
    ("MSI", "Cyborg 14 A13VF", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 10, 16),
    ("MSI", "Thin GF63 12UCX", 2022, "windows", "laptop", "x86", "nvidia-rtx-2050", 8, 8),
    ("MSI", "Thin 15 B12UC", 2023, "windows", "laptop", "x86", "nvidia-rtx-3050", 8, 8),
    ("MSI", "Stealth 15 A13VF", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 10, 16),
    ("MSI", "Stealth 17 Studio A13VH", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("MSI", "Stealth 14 AI Studio", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 16, 16),
    ("MSI", "Stealth 16 AI Studio", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("MSI", "Stealth 18 AI Studio", 2024, "windows", "laptop", "x86", "nvidia-rtx-4080", 16, 32),
    ("MSI", "Vector GP66 12UGS", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 16),
    ("MSI", "Vector GP76 12UHS", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 14, 32),
    ("MSI", "GP76 Leopard 12UGS", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 16),
    ("MSI", "Vector GP68 HX 13V", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("MSI", "Vector GP78 HX 13V", 2023, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 32),
    ("MSI", "Vector 16 HX A14V", 2024, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("MSI", "Vector 16 HX AI", 2025, "windows", "laptop", "x86", "nvidia-rtx-5080", 24, 32),
    ("MSI", "Raider GE67 HX 12UGS", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 16, 32),
    ("MSI", "Raider GE78 HX 13V", 2023, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("MSI", "Raider GE78 HX 14V", 2024, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 32),
    ("MSI", "Raider 18 HX A14V", 2024, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 64),
    ("MSI", "Raider 18 HX AI", 2025, "windows", "laptop", "x86", "nvidia-rtx-5090", 24, 64),
    ("MSI", "Titan GT77 HX 13V", 2023, "windows", "laptop", "x86", "nvidia-rtx-4090", 24, 64),
    ("MSI", "Titan 18 HX AI", 2025, "windows", "laptop", "x86", "nvidia-rtx-5090", 24, 64),
    ("MSI", "Creator Z16 HX Studio B13V", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 24, 32),
    ("MSI", "Creator Z17 A12UGST", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 32),
    ("MSI", "CreatorPro X17 A12UKS", 2022, "windows", "laptop", "x86", "nvidia-rtx-a3000", 14, 64),
    ("MSI", "CreatorPro Z17 HX Studio A13V", 2023, "windows", "laptop", "x86", "nvidia-rtx-4000-ada", 24, 64),
    ("MSI", "Creator M16 B13VE", 2023, "windows", "laptop", "x86", "nvidia-rtx-4050", 14, 16),
    ("MSI", "Claw A1M", 2024, "windows", "laptop", "x86", "intel-arc-graphics", 16, 16),
    ("MSI", "Claw 8 AI+", 2025, "windows", "laptop", "x86", "intel-arc-140v", 8, 32),
    ("MSI", "MPG Trident 3 12th", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060-desktop", 12, 16),
    ("MSI", "MPG Trident AS 12th", 2022, "windows", "desktop", "x86", "nvidia-rtx-3070-desktop", 12, 32),
    ("MSI", "MPG Trident AS 13th", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 32),
    ("MSI", "MPG Infinite X2 13F", 2023, "windows", "desktop", "x86", "nvidia-rtx-4090-desktop", 24, 64),
    ("MSI", "MAG Infinite S3 12th", 2022, "windows", "desktop", "x86", "nvidia-rtx-3050-desktop", 6, 16),
    ("MSI", "MAG Codex 5 12th", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060-desktop", 8, 16),
    ("MSI", "MAG Infinite S3 13th", 2023, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 10, 16),
    ("Razer", "Blade Stealth 13", 2022, "windows", "laptop", "x86", "nvidia-gtx-1650-ti", 4, 16),
    ("Razer", "Blade 14 (RTX 3080 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 8, 16),
    ("Razer", "Blade 15 (RTX 3060)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Razer", "Blade 15 (RTX 3080 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080-ti", 14, 32),
    ("Razer", "Blade 17 (RTX 3070 Ti)", 2022, "windows", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 32),
    ("Razer", "Blade 14 (RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("Razer", "Blade 15 (RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 16),
    ("Razer", "Blade 15 (RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 14, 16),
    ("Razer", "Blade 16 (RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 24, 16),
    ("Razer", "Blade 16 (RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 24, 32),
    ("Razer", "Blade 18 (RTX 4070)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4070", 24, 32),
    ("Razer", "Blade 14 (RTX 4060)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4060", 8, 16),
    ("Razer", "Blade 16 (RTX 4070)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 24, 32),
    ("Razer", "Blade 18 (RTX 4070)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4070", 24, 16),
    ("Razer", "Blade 18 (RTX 4080)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4080", 24, 32),
    ("Razer", "Blade 14 (RTX 5070)", 2025, "windows", "laptop", "x86", "nvidia-rtx-5070", 10, 32),
    ("Razer", "Blade 16 (RTX 5090)", 2025, "windows", "laptop", "x86", "nvidia-rtx-5090", 12, 32),
    ("Razer", "Blade 18 (RTX 5080)", 2025, "windows", "laptop", "x86", "nvidia-rtx-5080", 24, 32),
    ("Samsung", "Galaxy Book2 Pro 13", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Samsung", "Galaxy Book2 Pro 360 15.6", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Samsung", "Galaxy Book3 Pro 14", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Samsung", "Galaxy Book3 Pro 16", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 32),
    ("Samsung", "Galaxy Book3 Pro 360", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Samsung", "Galaxy Book3 Ultra (RTX 4060)", 2023, "windows", "laptop", "x86", "nvidia-rtx-4060", 14, 16),
    ("Samsung", "Galaxy Book4 Pro 14", 2024, "windows", "laptop", "x86", "intel-arc-graphics", 16, 32),
    ("Samsung", "Galaxy Book4 Pro 16", 2024, "windows", "laptop", "x86", "intel-arc-graphics", 16, 16),
    ("Samsung", "Galaxy Book4 Pro 360", 2024, "windows", "laptop", "x86", "intel-arc-graphics", 16, 16),
    ("Samsung", "Galaxy Book4 Ultra (RTX 4050)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4050", 16, 16),
    ("Samsung", "Galaxy Book4 Ultra (RTX 4080)", 2024, "windows", "laptop", "x86", "nvidia-rtx-4080", 16, 32),
    ("Samsung", "Galaxy Book5 Pro 14", 2025, "windows", "laptop", "x86", "intel-arc-140v", 8, 16),
    ("Samsung", "Galaxy Book5 Pro 16", 2025, "windows", "laptop", "x86", "intel-arc-140v", 8, 16),
    ("Samsung", "Galaxy Book5 Pro 360", 2025, "windows", "laptop", "x86", "intel-arc-140v", 8, 32),
    ("LG", "gram 17 (17Z90Q)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("LG", "gram 16 2-in-1 (16T90Q)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("LG", "UltraPC 17 (17U70Q)", 2022, "windows", "laptop", "x86", "amd-vega-8", 8, 16),
    ("LG", "UltraGear 17G90Q", 2022, "windows", "laptop", "x86", "nvidia-rtx-3080", 8, 16),
    ("LG", "gram 14 (14Z90RS)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("LG", "gram 17 (17Z90R)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("LG", "gram 16 2-in-1 (16T90R)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("LG", "gram Style 16 (16Z90RS)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("LG", "gram Pro 17 (17Z90SP)", 2024, "windows", "laptop", "x86", "intel-arc-graphics", 16, 32),
    ("LG", "gram Pro 16 2-in-1 (16T90SP)", 2024, "windows", "laptop", "x86", "intel-arc-graphics", 16, 16),
    ("LG", "gram 17 (17Z90S)", 2024, "windows", "laptop", "x86", "intel-arc-graphics", 16, 16),
    ("Microsoft", "Surface Laptop 5 (15 inch)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Microsoft", "Surface Laptop Go 2", 2022, "windows", "laptop", "x86", "intel-iris-xe", 4, 8),
    ("Microsoft", "Surface Laptop SE", 2022, "windows", "laptop", "x86", "intel-uhd-600", 2, 4),
    ("Microsoft", "Surface Pro 9 (i7)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Microsoft", "Surface Laptop 6 (15 inch)", 2024, "windows", "laptop", "x86", "intel-arc-graphics", 16, 32),
    ("Microsoft", "Surface Laptop 7 (13.8 inch)", 2024, "windows", "laptop", "x86", "intel-arc-140v", 8, 16),
    ("Microsoft", "Surface Laptop 7 (15 inch)", 2024, "windows", "laptop", "x86", "intel-arc-140v", 8, 32),
    ("Framework", "Laptop 13 (12th Gen, i5-1240P)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Framework", "Laptop 13 (12th Gen, i7-1280P)", 2022, "windows", "laptop", "x86", "intel-iris-xe", 14, 16),
    ("Framework", "Laptop 13 (13th Gen, i5-1340P)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Framework", "Laptop 13 (13th Gen, i7-1370P)", 2023, "windows", "laptop", "x86", "intel-iris-xe", 14, 32),
    ("Framework", "Laptop 13 (Ryzen 5 7640U)", 2023, "windows", "laptop", "x86", "amd-radeon-760m", 6, 16),
    ("Framework", "Laptop 13 (Ryzen 5 7640U)", 2023, "linux", "laptop", "x86", "amd-radeon-760m", 6, 16),
    ("Framework", "Laptop 13 (Ryzen 7 7840U)", 2023, "windows", "laptop", "x86", "amd-radeon-780m", 8, 32),
    ("Framework", "Laptop 13 (Ryzen 7 7840U)", 2023, "linux", "laptop", "x86", "amd-radeon-780m", 8, 32),
    ("Framework", "Laptop 13 (Core Ultra 5 125H)", 2024, "windows", "laptop", "x86", "intel-arc-graphics", 14, 16),
    ("Framework", "Laptop 13 (Core Ultra 7 155H)", 2024, "windows", "laptop", "x86", "intel-arc-graphics", 16, 32),
    ("Framework", "Laptop 16 (Ryzen 9 7940HS)", 2024, "windows", "laptop", "x86", "amd-radeon-780m", 8, 32),
    ("Framework", "Laptop 16 (Ryzen 9 7940HS)", 2024, "linux", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Framework", "Laptop 13 (Ryzen AI 300)", 2025, "windows", "laptop", "x86", "amd-radeon-890m", 12, 32),

    # ---- Linux-first vendors, and prebuilt desktops ----
    ("System76", "Lemur Pro (lemp11)", 2022, "linux", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("System76", "Lemur Pro (lemp12)", 2023, "linux", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("System76", "Darter Pro (darp8)", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("System76", "Darter Pro (darp9)", 2023, "linux", "laptop", "x86", "intel-iris-xe", 12, 32),
    ("System76", "Galago Pro (galp6)", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("System76", "Galago Pro (galp7)", 2023, "linux", "laptop", "x86", "intel-iris-xe", 12, 32),
    ("System76", "Gazelle (gaze17-3050)", 2022, "linux", "laptop", "x86", "nvidia-rtx-3050", 12, 16),
    ("System76", "Gazelle (gaze17-3060)", 2022, "linux", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("System76", "Gazelle (gaze18)", 2023, "linux", "laptop", "x86", "nvidia-rtx-3050", 14, 32),
    ("System76", "Oryx Pro (oryp9)", 2022, "linux", "laptop", "x86", "nvidia-rtx-3070-ti", 14, 32),
    ("System76", "Oryx Pro (oryp10)", 2022, "linux", "laptop", "x86", "nvidia-rtx-3080-ti", 16, 64),
    ("System76", "Oryx Pro (oryp11)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4070", 16, 32),
    ("System76", "Oryx Pro (RTX 4060)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4060", 14, 32),
    ("System76", "Adder WS (addw3)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4060", 16, 32),
    ("System76", "Adder WS (RTX 4070)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4070", 16, 64),
    ("System76", "Bonobo WS (bonw15)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4080", 24, 64),
    ("System76", "Bonobo WS (RTX 4090)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4090", 24, 96),
    ("System76", "Serval WS (serw13)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4070", 24, 32),
    ("System76", "Serval WS (RTX 4080)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4080", 16, 64),
    ("System76", "Meerkat", 2023, "linux", "desktop", "x86", "intel-iris-xe", 8, 16),
    ("System76", "Thelio Spark", 2024, "linux", "desktop", "x86", "intel-uhd-770", 16, 32),
    ("System76", "Thelio Major", 2024, "linux", "desktop", "x86", "nvidia-rtx-4090", 24, 64),
    ("System76", "Thelio Mega", 2023, "linux", "desktop", "x86", "amd-rx-7900-xtx", 32, 128),
    ("Tuxedo", "InfinityBook Pro 14 Gen 7", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Tuxedo", "InfinityBook Pro 14 Gen 8", 2023, "linux", "laptop", "x86", "nvidia-rtx-3050", 12, 32),
    ("Tuxedo", "InfinityBook Pro 14 Gen 9", 2024, "linux", "laptop", "x86", "amd-radeon-780m", 8, 32),
    ("Tuxedo", "InfinityBook Pro 16 Gen 8", 2023, "linux", "laptop", "x86", "nvidia-rtx-4060", 16, 32),
    ("Tuxedo", "Pulse 14 Gen 3", 2023, "linux", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("Tuxedo", "Pulse 15 Gen 2", 2022, "linux", "laptop", "x86", "amd-radeon-680m", 8, 32),
    ("Tuxedo", "Pulse 15 Gen 3", 2024, "linux", "laptop", "x86", "amd-radeon-780m", 8, 32),
    ("Tuxedo", "Polaris 15 Gen 2", 2022, "linux", "laptop", "x86", "amd-radeon-680m", 8, 16),
    ("Tuxedo", "Aura 15 Gen 2", 2022, "linux", "laptop", "x86", "amd-vega-8", 8, 16),
    ("Tuxedo", "Stellaris 15 Gen 4", 2022, "linux", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Tuxedo", "Stellaris 16 Gen 5", 2023, "linux", "laptop", "x86", "nvidia-rtx-4070", 24, 32),
    ("Tuxedo", "Stellaris 17 Gen 5", 2023, "linux", "laptop", "x86", "nvidia-rtx-4060", 24, 32),
    ("Star Labs", "StarBook Mk VI (AMD)", 2023, "linux", "laptop", "x86", "amd-vega-8", 8, 16),
    ("Star Labs", "StarLite Mk V", 2023, "linux", "laptop", "x86", "intel-uhd-graphics", 4, 16),
    ("Star Labs", "Byte (Ryzen 7 5800U)", 2022, "linux", "desktop", "x86", "amd-vega-8", 8, 16),
    ("Slimbook", "Essential 15", 2022, "linux", "laptop", "x86", "intel-iris-xe", 4, 8),
    ("Slimbook", "Essential 14", 2023, "linux", "laptop", "x86", "intel-iris-xe", 6, 16),
    ("Kubuntu Focus", "XE Gen 2", 2023, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Kubuntu Focus", "M2 Gen 5 (RTX 4060)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4060", 24, 32),
    ("Kubuntu Focus", "M2 Gen 5 (RTX 4070)", 2023, "linux", "laptop", "x86", "nvidia-rtx-4070", 24, 64),
    ("Framework", "Laptop 12 (i5-1334U)", 2025, "linux", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("Dell", "XPS 13 Plus 9320 (Developer Edition)", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Lenovo", "ThinkPad X13 Gen 4", 2023, "linux", "laptop", "x86", "intel-iris-xe", 10, 16),
    ("HP", "EliteBook 845 G9", 2022, "linux", "laptop", "x86", "amd-radeon-680m", 8, 16),
    ("Laptop with Linux", "Clevo NS70", 2023, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Laptop with Linux", "Clevo PD50", 2023, "linux", "laptop", "x86", "nvidia-rtx-3060", 14, 16),
    ("Entroware", "Athena", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 16),
    ("Entroware", "Aether", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 32),
    ("Entroware", "Kratos", 2022, "linux", "laptop", "x86", "nvidia-rtx-3060", 16, 32),
    ("Entroware", "Zeus", 2023, "linux", "laptop", "x86", "nvidia-rtx-4060", 16, 32),
    ("Juno", "Gemini 15", 2022, "linux", "laptop", "x86", "intel-iris-xe", 12, 32),
    ("Juno", "Gemini 17", 2022, "linux", "laptop", "x86", "intel-iris-xe", 14, 32),
    ("Juno", "Gemini 15 (Radeon 780M)", 2024, "linux", "laptop", "x86", "amd-radeon-780m", 8, 16),
    ("CyberPowerPC", "Gamer Master (Ryzen 5 5600X)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060-desktop", 6, 16),
    ("CyberPowerPC", "Gamer Master (Ryzen 7 5700X)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 8, 16),
    ("CyberPowerPC", "Gamer Master (RTX 5070)", 2025, "windows", "desktop", "x86", "nvidia-rtx-5070-desktop", 16, 32),
    ("CyberPowerPC", "Gamer Xtreme (RTX 3070)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3070-desktop", 12, 16),
    ("CyberPowerPC", "Gamer Xtreme (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 32),
    ("iBuyPower", "Element (RTX 3060)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060-desktop", 12, 16),
    ("iBuyPower", "Element (RTX 4060)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 16, 16),
    ("iBuyPower", "Element (RTX 5070)", 2025, "windows", "desktop", "x86", "nvidia-rtx-5070-desktop", 16, 32),
    ("iBuyPower", "Trace (RTX 3070)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3070-desktop", 12, 16),
    ("iBuyPower", "Trace (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 32),
    ("iBuyPower", "Slate (RTX 4060)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 12, 16),
    ("NZXT", "Player (RTX 3060)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060-desktop", 12, 16),
    ("NZXT", "Player (RTX 4060)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 12, 16),
    ("NZXT", "Player Two (RTX 3070)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3070-desktop", 12, 16),
    ("NZXT", "Player Two (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 32),
    ("NZXT", "Player Three (RTX 4080)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4080-desktop", 24, 32),
    ("NZXT", "Player Three (RTX 5080)", 2025, "windows", "desktop", "x86", "nvidia-rtx-5080-desktop", 24, 32),
    ("NZXT", "Creator (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 32),
    ("NZXT", "H1 (RTX 3060)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060-desktop", 8, 16),
    ("Corsair", "Vengeance i7400 (RTX 4080)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4080-desktop", 16, 32),
    ("Corsair", "Vengeance i7500 (RTX 4070)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 32),
    ("Corsair", "Vengeance i7500 (RTX 4070 SUPER)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4070-super", 20, 32),
    ("Corsair", "Vengeance i7500 (RTX 5070)", 2025, "windows", "desktop", "x86", "nvidia-rtx-5070-desktop", 16, 32),
    ("Corsair", "One i300 (RTX 3080)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3080-desktop", 16, 32),
    ("Corsair", "One i500 (RTX 4090)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4090-desktop", 24, 64),
    ("Origin PC", "Millennium (RTX 4080)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4080-desktop", 24, 64),
    ("Origin PC", "Millennium (RTX 5090)", 2025, "windows", "desktop", "x86", "nvidia-rtx-5090-desktop", 24, 64),
    ("Origin PC", "Neuron (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 32),
    ("Origin PC", "Neuron (RTX 5070)", 2025, "windows", "desktop", "x86", "nvidia-rtx-5070-desktop", 16, 32),
    ("Origin PC", "Chronos (RTX 4080)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4080-desktop", 16, 32),
    ("Origin PC", "Genesis (RTX 4090)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4090-desktop", 24, 64),
    ("Maingear", "Vybe (RTX 3060)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060-desktop", 12, 16),
    ("Maingear", "Vybe (RTX 4060)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 12, 16),
    ("Maingear", "Vybe (RTX 5070)", 2025, "windows", "desktop", "x86", "nvidia-rtx-5070-desktop", 16, 32),
    ("Maingear", "Rush (RTX 4080)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4080-desktop", 24, 32),
    ("Maingear", "Rush (RTX 4090)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4090-desktop", 24, 64),
    ("Maingear", "North (RTX 4070)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 32),
    ("Falcon Northwest", "Talon (RTX 4080)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4080-desktop", 24, 32),
    ("Falcon Northwest", "Talon (RTX 4090)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4090-desktop", 24, 64),
    ("Falcon Northwest", "FragBox (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 32),
    ("Falcon Northwest", "FragBox (RTX 4060)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 12, 16),
    ("Velocity Micro", "Raptor Signature Edition (RTX 5090)", 2025, "windows", "desktop", "x86", "nvidia-rtx-5090-desktop", 16, 64),
    ("Digital Storm", "Lynx (RTX 4060)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 12, 16),
    ("Digital Storm", "Lynx (RTX 4070)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 32),
    ("Digital Storm", "Slade (RTX 4080)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4080-desktop", 24, 32),
    ("Digital Storm", "Slade (RTX 5070)", 2025, "windows", "desktop", "x86", "nvidia-rtx-5070-desktop", 16, 32),
    ("Digital Storm", "Velox (RTX 4090)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4090-desktop", 24, 64),
    ("Puget Systems", "Deluge (RTX 4090)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4090-desktop", 24, 64),
    ("Puget Systems", "Deluge (RTX 4080 SUPER)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4080-super", 24, 64),
    ("Puget Systems", "Serenity (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 32),
    ("Puget Systems", "Serenity (RTX 4080)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4080-desktop", 24, 64),
    ("Puget Systems", "Spirit (RTX 4060)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 16, 32),
    ("Starforge", "Voyager (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 32),
    ("Starforge", "Voyager (RTX 4080)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4080-desktop", 24, 32),
    ("Starforge", "Navigator (RTX 4060)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 12, 16),
    ("Starforge", "Navigator (RTX 5070)", 2025, "windows", "desktop", "x86", "nvidia-rtx-5070-desktop", 16, 32),
    ("Alienware", "Aurora R15 (RTX 4080)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4080-desktop", 24, 32),
    ("Alienware", "Aurora R16 (RTX 4070 SUPER)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4070-super", 20, 32),
    ("Alienware", "Aurora R16 (RTX 4090)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4090-desktop", 24, 64),
    ("Alienware", "Aurora R16 (RTX 5070)", 2025, "windows", "desktop", "x86", "nvidia-rtx-5070-desktop", 20, 32),
    ("HP", "OMEN 45L (RTX 3080)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3080-desktop", 16, 32),
    ("HP", "OMEN 45L (RTX 4080)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4080-desktop", 24, 32),
    ("HP", "OMEN 45L (RTX 5070)", 2025, "windows", "desktop", "x86", "nvidia-rtx-5070-desktop", 20, 32),
    ("HP", "OMEN 25L (RTX 3060)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060-desktop", 12, 16),
    ("HP", "OMEN 40L (RTX 4070)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 32),
    ("HP", "OMEN 35L (RTX 4060)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 12, 16),
    ("Lenovo", "Legion Tower 5i (RTX 4060)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4060-desktop", 12, 16),
    ("Lenovo", "Legion Tower 5i (RTX 4070)", 2024, "windows", "desktop", "x86", "nvidia-rtx-4070-desktop", 16, 32),
    ("Lenovo", "Legion Tower 7i (RTX 4090)", 2023, "windows", "desktop", "x86", "nvidia-rtx-4090-desktop", 24, 64),
    ("Lenovo", "Legion Tower 7i (RTX 5070)", 2025, "windows", "desktop", "x86", "nvidia-rtx-5070-desktop", 20, 32),
    ("Lenovo", "IdeaCentre Gaming 5i (RTX 3060)", 2022, "windows", "desktop", "x86", "nvidia-rtx-3060-desktop", 8, 16),
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
    w(" * Provenance, because it decides what may be claimed: the machines, their")
    w(" * model names and their release years are real - they are the names the")
    w(" * vendors use.  What a page READS OFF those machines is curated, not")
    w(" * captured, and the distinction matters to anyone auditing this table:")
    w(" *")
    w(" *   - the User-Agent is CONSTRUCTED, in the reduced form Chrome has sent")
    w(" *     since 2022: the platform token that OS reports with")
    w(" *     Chrome/<major>.0.0.0 and no build number, which lives in the")
    w(" *     client hints instead;")
    w(" *   - the WebGL strings are CONSTRUCTED in Chrome's ANGLE format for each")
    w(" *     platform - Direct3D11 on Windows, Metal on macOS, OpenGL over Mesa")
    w(" *     on Linux - so they are shaped like what Chrome emits rather than")
    w(" *     read off a machine of that model.  Where a driver appends to them,")
    w(" *     as Intel's Arc iGPUs do with a memory size, this table does not")
    w(" *     model that;")
    w(" *   - gpu, cores and memory are the configuration the machine SHIPPED IN,")
    w(" *     as recorded when the row was written.  They are not telemetry, and")
    w(" *     a row can be wrong - as can any given trim being sold with that GPU")
    w(" *     at all;")
    w(" *   - cores is the physical core count, NOT the thread count Chrome")
    w(" *     reports (rule 6 in scripts/gen-devices.py), so a machine with SMT")
    w(" *     answers a higher hardwareConcurrency than this table gives it.")
    w(" *")
    w(" * What IS enforced, and checked by desktop/tests/test_core.c, is internal")
    w(" * consistency: ids are unique, every entry is a DIFFERENT identity - no")
    w(" * two share a fingerprint, so a machine that could not be told apart from")
    w(" * one already here was left out, which is why the curated table is longer")
    w(" * than this catalogue - memory stays within Chromium's cap, and no")
    w(" * User-Agent carries a \"Mobile\" token.  See scripts/gen-devices.py for")
    w(" * the full provenance note and the rules this file is generated under.")
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
