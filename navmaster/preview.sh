#!/bin/bash
# NavMaster - automatic preview on an Android emulator (runs inside android-emulator-runner)
# Usage: bash android/navmaster/preview.sh <apk> <out_dir>
set -x
APK="$1"; OUT="$2"; mkdir -p "$OUT"
PKG=net.osmand.dev
ACT=net.osmand.plus.activities.MapActivity
INFO="$OUT/preview_info.txt"
shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1" >> "$INFO"; }
# taps the NavMaster round buttons wherever the layout put them (logged as "NMBTN report x y poi x y")
btn_xy() { adb logcat -d -s NavMasterDL:I 2>/dev/null | grep NMBTN | tail -1 | awk -v k="$1" '{for (i = 1; i <= NF; i++) if ($i == k) print $(i + 1), $(i + 2)}'; }
tap_btn() { local xy; xy=$(btn_xy "$1"); if [ -n "$xy" ]; then set -- $xy; echo "tap_btn $1 $2" >> "$INFO"; tap "$1" "$2"; else echo "tap_btn $1 NOT FOUND" >> "$INFO"; fi; }
view() { adb shell am start -a android.intent.action.VIEW -d "\"$1\"" $PKG; }
dump_ui() { rm -f ui.xml; adb shell rm -f /sdcard/ui.xml; timeout 20 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > ui.xml 2>/dev/null; grep -q "<node" ui.xml || rm -f ui.xml; }
# tap_text "label" X Y  -> taps the element with that label, or X,Y if the UI tree is not readable
tap() { adb shell input tap "$1" "$2"; echo "tap $1,$2" >> "$INFO"; }
# tap the first on-screen element whose text or content-desc contains $1 (case-insensitive)
tap_text() {
  dump_ui
  python3 - "$1" <<'PY' | tee -a "$INFO"
import re, sys, subprocess
labels = [l.strip() for l in sys.argv[1].lower().split('|')]
try:
    x = open('ui.xml', encoding='utf-8', errors='ignore').read()
except Exception:
    print('no ui dump'); sys.exit(1)
nodes = re.findall(r'<node [^>]*>', x)
for exact in (True, False):   # exact label first (buttons), then "contains"
    for n in nodes:
        vals = [v.strip().lower() for v in re.findall(r' (?:text|content-desc)="([^"]*)"', n)]
        t = next((l for l in labels for v in vals if v and ((v == l) if exact else (l in v))), None)
        if t:
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
            x1, y1, x2, y2 = map(int, b.groups())
            subprocess.run(['adb', 'shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2)])
            print('tapped:', t, 'exact' if exact else 'contains', (x1 + x2) // 2, (y1 + y2) // 2); sys.exit(0)
print('not found:', labels); sys.exit(1)
PY
  [ "${PIPESTATUS[0]}" -eq 0 ] || { [ -n "$2" ] && tap "$2" "$3"; }
}
texts() { dump_ui; echo "== $1 ==" >> "$INFO"; grep -o ' text="[^"]\+"' ui.xml | head -60 >> "$INFO"; }

adb wait-for-device
# Italian system language (km, Italian labels) - restarts the Android framework
adb root; sleep 3; adb wait-for-device
adb shell "setprop persist.sys.locale it-IT; stop; sleep 2; start"
sleep 5; adb wait-for-device
sleep 40
for i in $(seq 1 30); do adb shell pm path android 2>/dev/null | grep -q package: && break; sleep 3; done
sleep 10; adb shell getprop persist.sys.locale >> "$INFO"
# daytime (12:00) so the preview shows the day colours
adb shell settings put global auto_time 0
# daytime (10:00) of tomorrow: keeps the day map style and valid HTTPS certificates
adb shell date "$(date -u -d '+1 day' +%m%d)1000$(date -u -d '+1 day' +%Y).00" >> "$INFO" 2>&1
for i in 1 2 3 4 5; do adb install -r -g "$APK" && break; sleep 10; done
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow || true

# small offline map (San Marino) so the preview shows real streets
if curl -fsSL -o sm.zip "https://download.osmand.net/download?standard=yes&file=San-marino_europe_2.obf.zip"; then
  unzip -o sm.zip
fi
START_LAT=43.9670; START_LON=12.4790; DEST_LAT=43.9360; DEST_LON=12.4460
adb emu geo fix $START_LON $START_LAT

# 1) splash + welcome wizard (first launch also creates the app storage folder)
adb shell am start -n $PKG/$ACT
sleep 1.5; shot 01_avvio
sleep 25; shot 02_benvenuto; texts benvenuto
tap_text "salta il download|skip download" 850 2050; sleep 4

# 2) install the offline map now that the storage folder exists, then restart the app
adb shell am force-stop $PKG; sleep 2
adb push San-marino_europe_2.obf /sdcard/Android/data/$PKG/files/ >> "$INFO" 2>&1
adb shell ls -la /sdcard/Android/data/$PKG/files/ >> "$INFO" 2>&1
adb emu geo fix $START_LON $START_LAT
adb shell am start -n $PKG/$ACT; sleep 20
shot 03_riavvio; texts riavvio
tap_text "salta il download|skip download" 850 2050; sleep 3

# 3) map on San Marino
view "geo:$START_LAT,$START_LON?z=16"; sleep 12
tap_text "chiudi|close" 280 375; sleep 2
shot 04_mappa; texts mappa

# 4) search screen
tap_text "cerca|search" 228 157; sleep 6; shot 05_ricerca; texts ricerca
adb shell input keyevent 4; sleep 2; adb shell input keyevent 4; sleep 3

# 4b) main menu (essential items only)
tap_text "menu" 90 2130; sleep 3; shot 05b_menu; texts menu
tap 400 150; sleep 3; shot 05c_profili; texts profili   # profile list (Camion, Auto, Camper, Bus)
adb shell input keyevent 4; sleep 2; adb shell input keyevent 4; sleep 2

# 5) truck navigation
view "osmand.api://navigate?start_lat=$START_LAT&start_lon=$START_LON&dest_lat=$DEST_LAT&dest_lon=$DEST_LON&dest_name=San%20Marino&profile=truck&force=true"
sleep 8
tap_text "mantieni attivo|keep active" 797 2064; sleep 3
tap_text "mantieni attivo|keep active"; sleep 3
shot 06_navigazione; texts navigazione
for i in 1 2 3 4 5 6; do adb emu geo fix 12.47$((9-i)) 43.96$((7-i)); sleep 2; done
sleep 4; shot 07_navigazione_in_movimento
tap 816 1659; sleep 5; adb emu geo fix 12.4730 43.9610; sleep 4; shot 07b_vista_3d   # 2D/3D button
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1; sleep 6; adb emu geo fix 12.4740 43.9630; sleep 6; shot 08_navigazione_orizzontale
adb shell settings put system user_rotation 0; sleep 3

# 5b) junction view demo (sample exit data; the real one appears near motorway exits)
adb shell touch /sdcard/Android/data/$PKG/files/navmaster_junction_demo
adb shell setprop debug.navmaster.jv 1
view "osmand.api://navigate?start_lat=$START_LAT&start_lon=$START_LON&dest_lat=$DEST_LAT&dest_lon=$DEST_LON&dest_name=NAVMASTER_DEMO&profile=truck&force=true"
sleep 10
tap_text "mantieni attivo|keep active"; sleep 2
tap 990 1664; sleep 3; tap 990 1840; sleep 3   # zoom in/out forces a map redraw
for i in 1 2 3; do adb emu geo fix 12.47$((4-i)) 43.96$((2-i)); sleep 2; done   # moves trigger a map redraw
sleep 2; shot 10_svincolo_demo
adb shell settings put system user_rotation 1; sleep 6; adb emu geo fix 12.4700 43.9600; sleep 6; shot 11_svincolo_demo_orizzontale
adb shell settings put system user_rotation 0; sleep 3
adb shell rm -f /sdcard/Android/data/$PKG/files/navmaster_junction_demo
adb shell setprop debug.navmaster.jv 0

# 5c) arrival panel with satellite view + restriction banner (sample data)
view "osmand.api://navigate?start_lat=$START_LAT&start_lon=$START_LON&dest_lat=$DEST_LAT&dest_lon=$DEST_LON&dest_name=NAVMASTER_ARRIVO&profile=truck&force=true"
sleep 10
tap_text "mantieni attivo|keep active"; sleep 2
for i in 1 2 3; do adb emu geo fix 12.4785 43.9665; sleep 3; done
sleep 8; shot 12_arrivo_satellite
tap_btn report; sleep 3; shot 14_segnala; texts segnala   # "Segnala" button -> report types
adb shell input keyevent 4; sleep 2
adb shell settings put system user_rotation 1; sleep 6; adb emu geo fix 12.4780 43.9660; sleep 10; shot 13_arrivo_orizzontale
adb shell settings put system user_rotation 0; sleep 3
adb logcat -d | grep -i "NavMasterDL\|NavMaster:" > "$OUT/navmaster_driver_log.txt" || true

# 5d) smooth simulation (auto-started by the NAVMASTER_SIM destination name), Sygic-style bottom panel
view "osmand.api://navigate?start_lat=$START_LAT&start_lon=$START_LON&dest_lat=$DEST_LAT&dest_lon=$DEST_LON&dest_name=NAVMASTER_SIM&profile=truck&force=true"
sleep 10
tap_text "mantieni attivo|keep active"; sleep 2
sleep 8; shot 15_simulazione_1
sleep 6; shot 16_simulazione_2
tap_btn poi; sleep 1; tap_btn poi; sleep 3; shot 18_poi_impostazioni; texts poi   # POI button -> categories, always / on demand
tap_text "carburante|fuel"; sleep 1; tap_text "parcheggi|parking"; sleep 1; tap_text "aree di servizio|service areas"; sleep 1
tap_text "ok"; sleep 10; shot 19_poi_sovraimpressione
tap_btn report; sleep 3; shot 20_segnala_icone; texts segnala2
adb shell input keyevent 4; sleep 2
adb shell settings put system user_rotation 1; sleep 8; shot 17_simulazione_orizzontale
adb shell settings put system user_rotation 0; sleep 3

# 6) launcher icon
adb shell input keyevent 3; sleep 2
adb shell input swipe 540 1800 540 400 300; sleep 3; shot 09_icona_app
adb logcat -d -t 3000 > "$OUT/logcat.txt" || true
adb shell "ls -la /data/anr; for f in /data/anr/*; do echo == \$f; head -c 60000 \$f; done" > "$OUT/anr.txt" 2>&1 || true
adb logcat -d | grep -i "NavMasterJV" > "$OUT/navmaster_log.txt" || true
cp "$INFO" "$OUT/preview_info.txt" 2>/dev/null || true
ls -la "$OUT"
exit 0
