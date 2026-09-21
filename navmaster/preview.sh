#!/bin/bash
# NavMaster - automatic preview on an Android emulator (runs inside android-emulator-runner)
# Usage: bash android/navmaster/preview.sh <apk> <out_dir>
set -x
APK="$1"; OUT="$2"; mkdir -p "$OUT"
PKG=net.osmand.dev
ACT=net.osmand.plus.activities.MapActivity
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
view() { adb shell am start -a android.intent.action.VIEW -d "\"$1\"" $PKG; }

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb install -r -g "$APK"

# small offline map (San Marino) so the preview shows real streets
if curl -fsSL -o sm.zip "https://download.osmand.net/download?standard=yes&file=San-marino_europe_2.obf.zip"; then
  unzip -o sm.zip
  adb shell mkdir -p /sdcard/Android/data/$PKG/files
  adb push San-marino_europe_2.obf /sdcard/Android/data/$PKG/files/
fi
START_LAT=43.9670; START_LON=12.4790; DEST_LAT=43.9360; DEST_LON=12.4460
adb emu geo fix $START_LON $START_LAT

adb shell am start -n $PKG/$ACT
sleep 4;  shot 01_avvio
sleep 35; shot 02_primo_avvio
adb shell input keyevent 4; sleep 3; shot 03_dopo_chiusura_dialoghi
adb shell am start -n $PKG/$ACT; sleep 5
view "geo:$START_LAT,$START_LON?z=16"; sleep 12; shot 04_mappa
adb shell input keyevent KEYCODE_S; sleep 6; shot 05_ricerca
adb shell input keyevent 4; sleep 3
view "osmand.api://navigate?start_lat=$START_LAT&start_lon=$START_LON&dest_lat=$DEST_LAT&dest_lon=$DEST_LON&dest_name=San%20Marino&profile=truck&force=true"
sleep 25; shot 06_navigazione
for i in 1 2 3 4 5 6; do adb emu geo fix 12.47$((9-i)) 43.96$((7-i)); sleep 2; done
sleep 4; shot 07_navigazione_in_movimento
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1; sleep 8; shot 08_navigazione_orizzontale
adb shell settings put system user_rotation 0; sleep 3
adb shell input keyevent 3; sleep 3; shot 09_home
adb logcat -d -t 4000 > "$OUT/logcat.txt" || true
ls -la "$OUT"
exit 0
