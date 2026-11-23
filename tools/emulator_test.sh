#!/usr/bin/env bash
# The emulator walk. A fictitious week (debug builds: --es demo week) in the four shapes of the
# Fold, checked from files/layout.json: every day inside the view on the spreads, and the days clear
# of the crease. Then the real path: no calendar permission, the prompt, and an empty week.
set -x
PKG=com.cerniera.settimana
source tools/emu.sh

adb install -r Settimana.apk
adb logcat -c
adb shell settings put system accelerometer_rotation 0

check() {  # name
  for try in 1 2 3 4 5 6; do json=$(adb shell run-as $PKG cat files/layout.json 2>/dev/null); [ -n "$json" ] && break; sleep 1; done
  python3 - "$1" "$json" <<'PY' | tee -a shapes.txt
import sys, json
name = sys.argv[1]
try: d = json.loads(sys.argv[2])
except Exception: print(f"::error::{name}: no layout recorded"); sys.exit(0)
l, t, r, b = d["view"]; vw, vh = r - l, b - t
bad = []
days = d["days"]
if len(days) != 7: bad.append(f"{len(days)} days laid out")
spread = d["mode"].startswith("SPREAD")
c = d["crease"]
for day, x0, y0, x1, y1 in days:
    if spread and (x0 < 0 or y0 < 0 or x1 > vw or y1 > vh): bad.append(f"day {day} at {[x0, y0, x1, y1]} leaves the view {vw}x{vh}")
    if spread and c > 0:
        a, z = (x0, x1) if d["mode"] == "SPREAD_V" else (y0, y1)
        if a < c < z: bad.append(f"day {day} crosses the crease at {c:.0f}")
print(("::error::" if bad else "::notice::") + f"{name} ({d['mode']}): " + ("; ".join(bad) if bad else f"7 days laid out in {vw}x{vh}" + (f", clear of the crease at {c:.0f}" if spread else "")))
PY
}

walk() {  # name WxH dpi rotation
  adb shell wm density "$3"; adb shell wm size "$2"; adb shell settings put system user_rotation "$4"; sleep 3
  adb shell run-as $PKG rm -f files/layout.json
  adb shell am start -S -n $PKG/.MainActivity --es demo week; sleep 5
  shot "$1-week"; check "Week, $1"
  # Concentrato: halfway through a sideways drag, the words give way to bars (spreads only)
  adb shell input swipe 900 900 500 900 900 &
  sleep 0.7; shot "$1-dragging"; wait; sleep 2
  shot "$1-next-week"
  adb shell am start -S -n $PKG/.MainActivity --es demo week; sleep 4
  tap_text "Settimana" >/dev/null; sleep 1
  adb shell am force-stop $PKG
}

# The Fold's glass in dp, from its own screenshots: cover 411 x 960, main 859 x 896
walk cover-portrait 823x1920 320 0
walk cover-landscape 823x1920 320 1
walk main-portrait 1503x1568 280 0
walk main-landscape 1503x1568 280 1
adb shell settings put system user_rotation 0; adb shell wm size reset; adb shell wm density reset; sleep 3

# The event sheet and the day sheet, on the main screen's spread
adb shell wm density 280; adb shell wm size 1503x1568; sleep 3
adb shell am start -S -n $PKG/.MainActivity --es demo week; sleep 5
tap_text "Interview: AML lead" ; sleep 2; shot event-sheet
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell wm size reset; adb shell wm density reset; sleep 3

# Without demo: no calendar permission yet, so the prompt, then refused, the card
adb shell am force-stop $PKG
adb shell pm revoke $PKG android.permission.READ_CALENDAR 2>/dev/null
adb shell am start -n $PKG/.MainActivity; sleep 4; shot permission-prompt
adb shell input keyevent KEYCODE_BACK; sleep 2; shot permission-refused
adb shell pm grant $PKG android.permission.READ_CALENDAR
adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity; sleep 4; shot empty-week

adb logcat -d > app-log.txt
if crashed; then echo "::error::Settimana crashed"; grep -A20 "FATAL EXCEPTION" app-log.txt | head -40; exit 1; fi
echo "No crash"
