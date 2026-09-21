#!/bin/bash
# NavMaster - automatic preview on an Android emulator (runs inside android-emulator-runner)
# Usage: bash android/navmaster/preview.sh <apk> <out_dir>
set -x
APK="$1"; OUT="$2"; mkdir -p "$OUT"
PKG=net.osmand.dev
ACT=net.osmand.plus.activities.MapActivity
INFO="$OUT/preview_info.txt"
shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1" >> "$INFO"; }
view() { adb shell am start -a android.intent.action.VIEW -d "\"$1\"" $PKG; }
dump_ui() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > ui.xml 2>/dev/null; }
# tap the first on-screen element whose text or content-desc contains $1 (case-insensitive)
tap_text() {
  dump_ui
  python3 - "$1" <<'PY' | tee -a "$INFO"
import re, sys, subprocess
t = sys.argv[1].lower()
try:
    x = open('ui.xml', encoding='utf-8', errors='ignore').read()
except Exception:
    print('no ui dump'); sys.exit(0)
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    vals = [v.lower() for v in re.findall(r' (?:text|content-desc)="([^"]*)"', n)]
    if any(t in v for v in vals if v):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        x1, y1, x2, y2 = map(int, b.groups())
        subprocess.run(['adb', 'shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2)])
        print('tapped:', t); sys.exit(0)
print('not found:', t)
PY
}
texts() { dump_ui; echo "== $1 ==" >> "$INFO"; grep -o ' text="[^"]\+"' ui.xml | head -60 >> "$INFO"; }

adb wait-for-device
adb install -r -g "$APK"
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow || true

# small offline map (San Marino) so the preview shows real streets
if curl -fsSL -o sm.zip "https://download.osmand.net/download?standard=yes&file=San-marino_europe_2.obf.zip"; then
  unzip -o sm.zip
  adb shell mkdir -p /sdcard/Android/data/$PKG/files
  adb push San-marino_europe_2.obf /sdcard/Android/data/$PKG/files/ >> "$INFO" 2>&1
  adb shell ls -la /sdcard/Android/data/$PKG/files/ >> "$INFO" 2>&1
fi
START_LAT=43.9670; START_LON=12.4790; DEST_LAT=43.9360; DEST_LON=12.4460
adb emu geo fix $START_LON $START_LAT

# 1) splash + welcome wizard
adb shell am start -n $PKG/$ACT
sleep 1.5; shot 01_avvio
sleep 25; shot 02_benvenuto; texts benvenuto
tap_text "skip download"; sleep 4
tap_text "skip"; sleep 3
shot 03_dopo_benvenuto; texts dopo_benvenuto

# 2) map on San Marino
adb emu geo fix $START_LON $START_LAT
view "geo:$START_LAT,$START_LON?z=16"; sleep 12
adb shell input keyevent 4; sleep 2   # close the context menu opened by geo: intent
shot 04_mappa; texts mappa

# 3) search screen
tap_text "search"; sleep 6; shot 05_ricerca; texts ricerca
adb shell input keyevent 4; sleep 3

# 4) truck navigation
view "osmand.api://navigate?start_lat=$START_LAT&start_lon=$START_LON&dest_lat=$DEST_LAT&dest_lon=$DEST_LON&dest_name=San%20Marino&profile=truck&force=true"
sleep 8
tap_text "keep active"; sleep 3
tap_text "start"; sleep 15
shot 06_navigazione; texts navigazione
for i in 1 2 3 4 5 6; do adb emu geo fix 12.47$((9-i)) 43.96$((7-i)); sleep 2; done
sleep 4; shot 07_navigazione_in_movimento
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1; sleep 8; shot 08_navigazione_orizzontale
adb shell settings put system user_rotation 0; sleep 3

# 5) launcher icon
adb shell input keyevent 3; sleep 2
adb shell input swipe 540 1800 540 400 300; sleep 3; shot 09_icona_app
adb logcat -d -t 3000 > "$OUT/logcat.txt" || true
cp "$INFO" "$OUT/preview_info.txt" 2>/dev/null || true
ls -la "$OUT"
exit 0
