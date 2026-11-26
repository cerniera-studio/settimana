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

monday() { adb shell run-as $PKG cat files/layout.json 2>/dev/null | python3 -c 'import sys, json; print(json.load(sys.stdin).get("monday", ""))' 2>/dev/null; }

turned() {  # name monday-before days-expected
  python3 - "$1" "$2" "$(monday)" "$3" <<'PY' | tee -a shapes.txt
import sys, datetime
name, a, b, want = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
try: moved = (datetime.date.fromisoformat(b) - datetime.date.fromisoformat(a)).days
except Exception: print(f"::error::{name}: no week recorded ({a!r} to {b!r})"); sys.exit(0)
print(("::notice::" if moved == want else "::error::") + f"Turn, {name}: week of {a} to week of {b} (wanted {want:+d} days)")
PY
}

walk() {  # name WxH dpi rotation
  adb shell wm density "$3"; adb shell wm size "$2"; adb shell settings put system user_rotation "$4"; sleep 3
  adb shell run-as $PKG rm -f files/layout.json
  adb shell am start -S -n $PKG/.MainActivity --es demo week; sleep 5
  shot "$1-week"; check "Week, $1"
  # Concentrato: halfway through a sideways drag, the words give way to bars (spreads only)
  W=${2%x*}; H=${2#*x}; [ "$4" = 1 ] && { t=$W; W=$H; H=$t; }
  w0=$(monday)
  adb shell input swipe $((W*7/10)) $((H/2)) $((W*3/10)) $((H/2)) 900 &
  sleep 0.7; shot "$1-dragging"; wait; sleep 2
  shot "$1-next-week"
  if [ "$1" = cover-landscape ]; then
    # Turned, the cover scrolls sideways: sideways moves through the days, and pulling on past
    # Sunday turns the week. Up and down turn it straight away.
    for i in 1 2 3; do adb shell input swipe $((W*8/10)) $((H/2)) $((W*2/10)) $((H/2)) 120; done; sleep 2
    w0=$(monday); adb shell input swipe $((W*8/10)) $((H/2)) $((W*2/10)) $((H/2)) 1200; sleep 0.2; shot "$1-pull"; sleep 2
    turned "$1, pulled past Sunday" "$w0" 7
  else
    turned "$1, swiped left" "$w0" 7
    w0=$(monday); adb shell input swipe $((W*3/10)) $((H/2)) $((W*8/10)) $((H/2)) 250; sleep 2
    turned "$1, swiped right" "$w0" -7
  fi
  if [ "$1" = cover-portrait ]; then
    # Upright, the cover scrolls up and down: fling to Sunday, then pull on past it for the week after
    for i in 1 2 3; do adb shell input swipe $((W/2)) $((H*8/10)) $((W/2)) $((H*2/10)) 120; done; sleep 2
    w0=$(monday); adb shell input swipe $((W/2)) $((H*8/10)) $((W/2)) $((H*2/10)) 1200; sleep 0.2; shot "$1-pull"; sleep 2
    turned "$1, pulled past Sunday" "$w0" 7
  else
    w0=$(monday); adb shell input swipe $((W/2)) $((H*8/10)) $((W/2)) $((H*2/10)) 250; sleep 2
    turned "$1, swiped up" "$w0" 7
    w0=$(monday); adb shell input swipe $((W/2)) $((H*2/10)) $((W/2)) $((H*8/10)) 250; sleep 2
    turned "$1, swiped down" "$w0" -7
  fi
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

# The week widget (25 Nov 2026), drawn by its own painter: the family's 13-size matrix, the picker's
# preview, its states, portrait and landscape pairs, and a sweep from the smallest resize to the main screen
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity --ez matrix true --es demo week >/dev/null; sleep 8
for f in $(adb shell run-as $PKG ls files | tr -d '\r' | grep -E '^(matrix-|state-|preview_|pairs|sweep).*\.png$'); do
  adb exec-out run-as $PKG cat "files/$f" > "shot-widget-$f"
done
ls -la shot-widget-* || echo "::error::no widget pictures were drawn"
[ -s shot-widget-sweep.png ] || echo "::error::the widget's resize sweep was not drawn"

# The widget's real path: Settimana opened from a day's tap, on that day
adb shell am start -S -n $PKG/.MainActivity --es demo week --es date "$(date -d 'next sunday' +%F 2>/dev/null || date +%F)"; sleep 5; shot widget-day-tap

adb logcat -d > app-log.txt
if crashed; then echo "::error::Settimana crashed"; grep -A20 "FATAL EXCEPTION" app-log.txt | head -40; exit 1; fi
echo "No crash"
