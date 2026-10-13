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
# Android shows a one-time "Viewing full screen" hint for immersive apps; mark it as seen.
adb shell settings put secure immersive_mode_confirmations confirmed
# The emulator's own launcher sometimes stalls under load; hide system "not responding" dialogs.
adb shell settings put global hide_error_dialogs 1
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
adb logcat -c

shot() {
  local name="$1"; local wait="$2"; shift 2
  adb shell am force-stop "$PKG"
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
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
adb shell am force-stop "$PKG"
adb logcat -d > "$OUT/logcat_part0.txt"
adb logcat -c
adb shell am start -W -n "$PKG/$ACT" --es demo_answer "مدرسة" --es demo_input "ممممم" --ez demo_submit true >/dev/null
for _ in $(seq 1 120); do adb logcat -d -s KhumasiDemo:I | grep -q submitted && break; sleep 0.1; done
sleep 0.3
adb exec-out screencap -p > "$OUT/04_rejected.png"
echo "captured 04_rejected"
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

# Phase 2: tutorial, rewards, color-blind mode, sky themes, hard mode, timer.
shot 21_tutorial 6 --es demo_answer "مدرسة" --ez demo_tutorial true
shot 22_rewards 9 --es demo_answer "مدرسة" --ez demo_rewards true --ei demo_wins 30
shot 23_colorblind 8 --es demo_answer "مدرسة" --es demo_guesses "سيارة,مكتبة,درجات" --es demo_input "مد" --ez demo_colorblind true --ez demo_timer true
shot 24_sunset 9 --es demo_answer "مدرسة" --es demo_guesses "مكتبة,مدرسة" --ez demo_result true --ei demo_wins 30 --es demo_sky sunset --es demo_tiles sand --ez demo_colorblind false --ez demo_timer false
shot 25_night 9 --es demo_answer "مدرسة" --es demo_guesses "سيارة,مكتبة" --ei demo_wins 30 --es demo_sky night --es demo_tiles night
shot 26_aurora 9 --es demo_answer "مدرسة" --es demo_guesses "مكتبة,مدرسة" --ez demo_result true --ei demo_wins 60 --es demo_sky aurora --es demo_tiles gold
shot 27_settings 6 --es demo_answer "مدرسة" --ez demo_settings true --es demo_sky day --es demo_tiles ivory
# Hard mode: after مكتبة, سيارة breaks the rules (first letter must stay م).
adb shell am force-stop "$PKG"
adb logcat -d >> "$OUT/logcat_part0.txt"
adb logcat -c
adb shell am start -W -n "$PKG/$ACT" --es demo_answer "مدرسة" --es demo_guesses "مكتبة" --es demo_input "سيارة" --ez demo_hard true --ez demo_submit true >/dev/null
for _ in $(seq 1 120); do adb logcat -d -s KhumasiDemo:I | grep -q submitted && break; sleep 0.1; done
sleep 0.3
adb exec-out screencap -p > "$OUT/28_hard_mode.png"
adb shell am start -W -n "$PKG/$ACT" --ez demo_hard false --es demo_answer "مدرسة" >/dev/null
sleep 2

# Gemini: settings section and an offline hint (no key on the emulator).
shot 29_ai_settings 6 --es demo_answer "مدرسة" --ez demo_settings true --ez demo_scroll_ai true
shot 30_hint_offline 8 --es demo_answer "مدرسة" --es demo_guesses "مكتبة" --ez demo_hint true

# Phase 4: play modes, daily challenge, friend challenge, backup.
shot 31_modes 7 --es demo_answer "مدرسة" --ei demo_wins 6 --ez demo_modes true
shot 32_daily 9 --es demo_answer "مدرسة" --ei demo_wins 6 --ez demo_daily true
shot 33_friend_create 7 --es demo_answer "مدرسة" --ez demo_friend_create true
shot 34_friend_enter 7 --es demo_answer "مدرسة" --es demo_friend_word "مكتبة"
shot 35_friend_round 9 --es demo_answer "مدرسة" --ez demo_friend_start true
# Sharing a challenge message to the app (as from a chat app) opens it ready to play.
adb shell am force-stop "$PKG"
adb shell am start -W -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "تحديتك! الرمز: KH-TEST" -n "$PKG/$ACT" >/dev/null
sleep 6
adb exec-out screencap -p > "$OUT/36_shared_bad_code.png"
echo "captured 36_shared_bad_code"

# A short 16:9 phone (360x640 dp): the board and keyboard must fit without clipping.
adb shell wm size 1080x1920
adb shell wm density 480
sleep 2
shot 17_small_midgame 9 --es demo_answer "مدرسة" --es demo_guesses "سيارة,مكتبة,درجات,حديقة,جزيرة,مدينة" --es demo_input "مد" --ei demo_wins 4
shot 18_small_result 9 --es demo_answer "مدرسة" --es demo_guesses "مكتبة,مدرسة" --ez demo_result true --ei demo_wins 12
adb shell wm size reset
adb shell wm density reset
sleep 2

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
  # Hold a key to capture the letter preview bubble.
  adb shell input swipe "${KX[ش]}" "${KY[ش]}" "${KX[ش]}" "${KY[ش]}" 4000 &
  sleep 2
  adb exec-out screencap -p > "$OUT/20_key_preview.png"
  wait
  tap DELETE
  for ch in ك ت ا ب ة; do tap "$ch"; done
  tap ENTER
  sleep 3.5
  adb exec-out screencap -p > "$OUT/13_typed_first_guess.png"
  for ch in م د ر س; do tap "$ch"; done
  tap ي; tap DELETE; tap ة
  adb exec-out screencap -p > "$OUT/14_typed_before_submit.png"
  # Close the app completely and reopen it normally: the round must come back as it was.
  adb shell am force-stop "$PKG"
  sleep 1
  adb shell am start -W -n "$PKG/$ACT" >/dev/null
  sleep 7
  adb exec-out screencap -p > "$OUT/14b_restored_after_restart.png"
  tap ENTER
  sleep 2.2
  adb exec-out screencap -p > "$OUT/15_win_animation.png"
  sleep 4
  adb exec-out screencap -p > "$OUT/16_win_result.png"
  # Closing the result (back gesture) brings the board back with a "new word" bar.
  adb shell input keyevent 4
  sleep 2.5
  adb exec-out screencap -p > "$OUT/19_result_closed.png"
  echo "typed through the keyboard"
else
  echo "::error::Keyboard positions were not logged"
  exit 1
fi

adb logcat -d > "$OUT/logcat_part2.txt"
cat "$OUT/logcat_part0.txt" "$OUT/logcat_part1.txt" "$OUT/logcat_part2.txt" > "$OUT/logcat.txt"
rm -f "$OUT"/logcat_part*.txt
grep -E "Khumasi|KhumasiGL" "$OUT/logcat.txt" | grep -v KhumasiKeys | head -120 > "$OUT/app_log.txt" || true
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
