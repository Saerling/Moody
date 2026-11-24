#!/usr/bin/env bash
# runs inside the emulator job: installs moody, walks past the first screens by tapping whatever button reads like
# "continue", then opens home and library and keeps screenshots, the screen's text and moody's log for each
set -u
OUT=${1:-emulator-out}
mkdir -p "$OUT"
PKG=$(aapt dump badging dist/moody.apk 2>/dev/null | sed -n "s/package: name='\([^']*\)'.*/\1/p")
[ -z "$PKG" ] && PKG=com.saerling.moody
echo "package: $PKG"
adb install -r -g dist/moody.apk || exit 1
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 25

shot() {
  adb exec-out screencap -p > "$OUT/$1.png"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1
  grep -o 'text="[^"]\+"' "$OUT/$1.xml" 2>/dev/null | sed 's/text=//' | tr '\n' ' ' > "$OUT/$1.txt"
  echo "== $1: $(head -c 600 "$OUT/$1.txt")"
}

# tap the centre of the first node whose text matches
tap_text() {
  local xml="$1" pattern="$2"
  local bounds
  bounds=$(grep -ioE "text=\"($pattern)\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$xml" | head -1 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')
  [ -z "$bounds" ] && bounds=$(grep -ioE "content-desc=\"($pattern)\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$xml" | head -1 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')
  [ -z "$bounds" ] && return 1
  local n; n=($(echo "$bounds" | grep -oE '[0-9]+'))
  adb shell input tap $(( (n[0]+n[2])/2 )) $(( (n[1]+n[3])/2 ))
}

NEXT='Continue|Next|Skip|Get started|Done|Start|Not now|Later|Allow|OK|Continuar|Siguiente|Omitir|Empezar|Listo|Ahora no|Permitir|Aceptar|Skip for now|Maybe later'
for i in $(seq 1 14); do
  shot "setup_$i"
  if grep -qE 'text="(Home|Inicio|Library|Biblioteca)"' "$OUT/setup_$i.xml" 2>/dev/null && ! grep -qiE 'text="(Continue|Continuar|Next|Siguiente)"' "$OUT/setup_$i.xml"; then break; fi
  # the profile step wants a name before Next does anything
  if grep -q 'text="username"' "$OUT/setup_$i.xml" 2>/dev/null; then
    tap_text "$OUT/setup_$i.xml" 'username' && sleep 1 && adb shell input text moodyci && adb shell input keyevent KEYCODE_BACK && sleep 1
  fi
  tap_text "$OUT/setup_$i.xml" "$NEXT" || adb shell input tap 540 1200
  sleep 5
done

sleep 20
shot home
tap_text "$OUT/home.xml" 'Library|Biblioteca' && sleep 8 && shot library
tap_text "$OUT/library.xml" 'Playlists' && sleep 6 && shot playlists
adb shell input keyevent KEYCODE_BACK; sleep 3
tap_text "$OUT/home.xml" 'Settings|Ajustes' && sleep 6 && shot settings

adb logcat -d > "$OUT/logcat-all.txt"
PID=$(adb shell pidof "$PKG" | tr -d '\r')
grep -E "AndroidRuntime|FATAL|$PKG|Sync|AutoSync|Import|InnerTube|YouTube" "$OUT/logcat-all.txt" | tail -400 > "$OUT/logcat-moody.txt"
echo "== crashes:"; grep -A25 "FATAL EXCEPTION" "$OUT/logcat-all.txt" | head -80 || true
echo "== app alive: ${PID:-no}"
