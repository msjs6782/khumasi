#!/usr/bin/env bash
# Installs the debug APK on the emulator, opens prepared game states (debug-only intent extras)
# and saves a screenshot of each. Fails if the app crashes or the 3D renderer reports an error.
set -u
APK="$1"
OUT="$2"
PKG=com.khumasi.game.debug
ACT=com.khumasi.game.MainActivity
mkdir -p "$OUT"

adb wait-for-device
adb shell input keyevent 82 >/dev/null 2>&1 || true
adb install -r "$APK"
adb logcat -c

shot() {
  local name="$1"; local wait="$2"; shift 2
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/$ACT" "$@" >/dev/null
  sleep "$wait"
  adb exec-out screencap -p > "$OUT/$name.png"
  echo "captured $name"
}

# A fresh round (fixed answer so screenshots are comparable).
shot 01_start 9 --es demo_answer "مدرسة" --ei demo_wins 0
# Mid-round: greens, ambers, grays, keyboard coloring, a partly typed row.
shot 02_midgame 8 --es demo_answer "مدرسة" --es demo_guesses "سيارة,مكتبة,درجات" --es demo_input "مدر" --ei demo_wins 4
# Duplicate letters: answer سلسلة with repeated guesses.
shot 03_duplicates 8 --es demo_answer "سلسلة" --es demo_guesses "سلاسل,مسلسل" --ei demo_wins 2
# A word that is not in the list is rejected with a message and no attempt is used.
shot 04_rejected 3 --es demo_answer "مدرسة" --es demo_input "ممممم" --ez demo_submit true
# A won round with the result sheet.
shot 05_won 9 --es demo_answer "مدرسة" --es demo_guesses "مكتبة,مدرسة" --ez demo_result true --ei demo_wins 12
# A lost round.
shot 06_lost 9 --es demo_answer "قنطرة" --es demo_guesses "سيارة,مكتبة,حديقة,جزيرة,مدينة,عاصمة,جامعة,طائرة" --ez demo_result true --ei demo_wins 3
# Rules sheet.
shot 07_rules 6 --es demo_answer "مدرسة" --ez demo_rules true
# Island at the top level.
shot 08_island_max 10 --es demo_answer "مدرسة" --es demo_guesses "سيارة" --ei demo_wins 60
# Light mode (no shadows or glass, no anti-aliasing).
shot 09_light 8 --es demo_answer "مدرسة" --es demo_guesses "سيارة,مكتبة" --ez demo_light true --ei demo_wins 6
# Statistics and settings sheets.
shot 10_stats 6 --es demo_answer "مدرسة" --ez demo_stats true --ei demo_wins 20
shot 11_settings 6 --es demo_answer "مدرسة" --ez demo_settings true

# Simulated front-camera cutout.
adb shell cmd overlay enable com.android.internal.display.cutout.emulation.tall >/dev/null 2>&1 || true
sleep 3
shot 12_cutout 9 --es demo_answer "مدرسة" --es demo_guesses "سيارة,مكتبة" --es demo_input "مد" --ei demo_wins 4
adb shell cmd overlay disable com.android.internal.display.cutout.emulation.tall >/dev/null 2>&1 || true

# Real typing through the on-screen keyboard: the debug build logs each key's screen position.
adb logcat -d > "$OUT/logcat_part1.txt"
adb logcat -c
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/$ACT" --es demo_answer "مدرسة" --ez demo_keys true >/dev/null
sleep 7
declare -A KX KY
while read -r _ label x y; do KX["$label"]=$x; KY["$label"]=$y; done < <(adb logcat -d -s KhumasiKeys:I | grep -o "KEY .*" | tr -d '\r')
echo "keys found: ${#KX[@]}"
tap() { adb shell input tap "${KX[$1]}" "${KY[$1]}"; sleep 0.35; }
if [ "${#KX[@]}" -ge 35 ]; then
  for ch in ك ت ا ب ة; do tap "$ch"; done
  tap ENTER
  sleep 3.5
  adb exec-out screencap -p > "$OUT/13_typed_first_guess.png"
  for ch in م د ر س; do tap "$ch"; done
  tap ي; tap DELETE; tap ة
  adb exec-out screencap -p > "$OUT/14_typed_before_submit.png"
  tap ENTER
  sleep 2.2
  adb exec-out screencap -p > "$OUT/15_win_animation.png"
  sleep 4
  adb exec-out screencap -p > "$OUT/16_win_result.png"
  echo "typed through the keyboard"
else
  echo "::error::Keyboard positions were not logged"
  exit 1
fi

adb logcat -d > "$OUT/logcat_part2.txt"
cat "$OUT/logcat_part1.txt" "$OUT/logcat_part2.txt" > "$OUT/logcat.txt"
grep -E "Khumasi|KhumasiGL" "$OUT/logcat.txt" | head -50 > "$OUT/app_log.txt" || true
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  echo "::error::The app crashed on the emulator"
  grep -A 30 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60
  exit 1
fi
if grep -qE "Renderer setup failed|Falling back to the flat board" "$OUT/logcat.txt"; then
  echo "::error::The 3D renderer failed on the emulator"
  grep -B 2 -A 20 -E "Renderer setup failed" "$OUT/logcat.txt" | head -60
  exit 1
fi
echo "UI check passed"
