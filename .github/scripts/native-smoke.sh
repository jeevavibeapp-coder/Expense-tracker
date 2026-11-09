#!/usr/bin/env bash
# Smoke test for the native app (android/nativeapp) on a real Android device:
# install it, launch it, use it the way a person would, send it a bank SMS
# while it is in the background, and fail loudly if any of that goes wrong.
#
#   usage: native-smoke.sh NOSMS_DEBUG_APK SMS_DEBUG_APK [OUT_DIR]
#
# Drives whichever device adb points at (ANDROID_SERIAL), so it can be re-run
# by hand. Needs adb and python3; Pillow or ImageMagick only for the in-log
# screenshots. It starts from a clean install, which deletes the app's ledger
# on that device, so it refuses a device that already has the app unless the
# device is an emulator or SMOKE_WIPE_OK=1 is set. The SMS step needs an
# emulator (`adb emu`), and is skipped on a phone.
#
# Every assertion prints one PASS / FAIL / SKIP line and is repeated in a
# table at the end; the exit status is non-zero if any failed. Screenshots go
# into the log as small base64 JPEGs between SCREENSHOT-BEGIN / SCREENSHOT-END
# lines (strip the log's timestamp column before decoding), because the job
# log is the one thing every reader of this repository's CI can reach. The
# full-size PNGs, UI dumps and logcat are written to OUT_DIR.

set -uo pipefail

NOSMS_APK=${1:?usage: native-smoke.sh NOSMS_DEBUG_APK SMS_DEBUG_APK [OUT_DIR]}
SMS_APK=${2:?usage: native-smoke.sh NOSMS_DEBUG_APK SMS_DEBUG_APK [OUT_DIR]}
OUT=${3:-native-smoke-out}
PKG=com.jeevavibeapp.spendwise.native
# The namespace, not the applicationId: the class lives in the source package.
ACTIVITY=com.jeevavibeapp.spendwise.NativeMainActivity
LEDGER=/data/data/$PKG/databases/spendwise.db
HERE=$(cd "$(dirname "$0")" && pwd)
DEV_XML=/data/local/tmp/native-smoke-ui.xml

# What the test types and sends. The expense payee is not a built-in merchant,
# so what appears in the list is what was typed rather than a seeded rename.
MANUAL_AMOUNT=249
MANUAL_PAYEE=Chaayos
SMS_AMOUNT=450
SMS_PAYEE=Zomato

mkdir -p "$OUT"
FAILS=0
RESULTS=()

record() {
  RESULTS+=("$1  $2")
  echo "$1  $2"
  if [ "$1" = FAIL ]; then FAILS=$((FAILS + 1)); fi
  return 0
}
pass() { record PASS "$*"; }
fail() { record FAIL "$*"; }
skip() { record SKIP "$*"; }
check() { local ok=$1; shift; if [ "$ok" = 0 ]; then pass "$@"; else fail "$@"; fi; }
step() { echo; echo "=================== $* ==================="; }

ui() { python3 "$HERE/uitree.py" "$@"; }
# stdin closed: adb shell otherwise swallows the caller's stdin.
dsh() { adb shell "$@" < /dev/null | tr -d '\r'; }

# --- the screen -------------------------------------------------------------

dump() {  # dump NAME: read the screen into $OUT/NAME.xml
  local f="$OUT/$1.xml" i
  for i in 1 2 3; do
    dsh rm -f "$DEV_XML"
    dsh uiautomator dump "$DEV_XML" > /dev/null 2>&1
    adb exec-out cat "$DEV_XML" > "$f" 2>/dev/null < /dev/null
    grep -q '<hierarchy' "$f" && return 0
    sleep 1
  done
  return 1
}

texts() {  # texts NAME: what a dump already taken shows
  echo "  on screen [$1]:"
  ui texts "$OUT/$1.xml" "$PKG"
}

shot() {  # shot NAME: full-size PNG to OUT_DIR, a small copy into the log
  adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null < /dev/null
  ui thumb "$OUT/$1.png" "$1"
}

# A slow emulator boot routinely leaves "System UI isn't responding" in front
# of everything, and it would swallow every tap that follows.
clear_system_dialogs() {
  local f="$OUT/$1.xml" xy
  if ui has "$f" "" "isn.t responding"; then
    echo "  a system 'isn't responding' dialog is up; pressing Wait"
    ui texts "$f" ""
    xy=$(ui center "$f" "" '^(Wait|WAIT)$') && dsh input tap $xy
  fi
}

wait_for() {  # wait_for SECONDS NAME REGEX: poll until REGEX is on screen
  local deadline=$((SECONDS + $1)) name=$2 rx=$3
  while :; do
    if dump "$name" && ui has "$OUT/$name.xml" "$PKG" "$rx"; then return 0; fi
    if [ "$SECONDS" -ge "$deadline" ]; then return 1; fi
    clear_system_dialogs "$name"
    sleep 1
  done
}

on_screen() {  # on_screen NAME REGEX: is REGEX on the last dump NAME
  ui has "$OUT/$1.xml" "$PKG" "$2"
}

tap() {  # tap NAME REGEX [text|desc|any]: tap the first match on dump NAME
  local xy
  xy=$(ui center "$OUT/$1.xml" "$PKG" "$2" "${3:-any}") || return 1
  dsh input tap $xy
}

scroll_to() {  # scroll_to NAME REGEX: swipe the content up until REGEX shows
  local i
  for i in 1 2 3 4 5 6 7 8; do
    if dump "$1" && on_screen "$1" "$2"; then return 0; fi
    # Slow enough not to fling past what is being looked for.
    dsh input swipe $((W / 2)) $((H * 7 / 10)) $((W / 2)) $((H * 3 / 10)) 600
    sleep 1
  done
  dump "$1" && on_screen "$1" "$2"
}

failed_here() {  # failed_here NAME: leave evidence in the log at a failure
  echo "  -- screen at this failure --"
  dump "$1" && texts "$1"
  shot "$1"
}

# --- the app ----------------------------------------------------------------

alive() { [ -n "$(dsh pidof "$PKG")" ]; }

crashed() {  # print crash/ANR evidence so far; exit 0 if there is any
  adb logcat -d -b crash -v threadtime < /dev/null > "$OUT/crash-buffer.txt" 2>/dev/null
  ui crashes "$PKG" "$OUT/logcat.txt" "$OUT/crash-buffer.txt"
}

LAST_TOTAL=
launch() {  # start the app the way the launcher does; am's report into the log
  local out
  out=$(dsh am start -W -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER -n "$COMPONENT" 2>&1)
  echo "$out" | sed 's/^/    am: /'
  echo "$out" >> "$OUT/am-start.txt"
  LAST_TOTAL=$(echo "$out" | awk -F': ' '/^TotalTime/ {print $2}')
  echo "$out" | grep -q '^Status: ok'
}

HAVE_SQLITE=no
db() {  # db SQL: read the app's ledger; debug builds only (run-as)
  dsh "run-as $PKG sqlite3 -separator ' | ' $LEDGER \"$1\"" 2>&1
}

is_emulator() {
  [ "$(dsh getprop ro.kernel.qemu)" = 1 ] || [ "$(dsh getprop ro.boot.qemu)" = 1 ] ||
    case "$(dsh getprop ro.hardware)" in ranchu|goldfish) true ;; *) false ;; esac
}

# ============================================================================
step "0. Device"
adb wait-for-device
SDK=$(dsh getprop ro.build.version.sdk)
EMULATOR=no
is_emulator && EMULATOR=yes
read -r W H < <(dsh wm size | awk -F'[ x]' '/size/ {w = $(NF - 1); h = $NF} END {print w, h}')
echo "device: $(dsh getprop ro.product.model), API $SDK, emulator: $EMULATOR, screen ${W}x${H}"
if [ -z "${W:-}" ] || [ -z "${H:-}" ]; then W=1080; H=1920; fi

if [ -n "$(dsh pm path "$PKG")" ]; then
  if [ "$EMULATOR" = yes ] || [ "${SMOKE_WIPE_OK:-}" = 1 ]; then
    dsh pm uninstall "$PKG" > /dev/null
  else
    echo "REFUSED: $PKG is installed on this phone. This test starts with an uninstall,"
    echo "which deletes its ledger, and SpendWise has no cloud copy. Use an emulator,"
    echo "or set SMOKE_WIPE_OK=1 if that ledger is disposable."
    exit 2
  fi
fi

dsh input keyevent KEYCODE_WAKEUP
dsh wm dismiss-keyguard > /dev/null 2>&1
if [ "$EMULATOR" = yes ]; then
  # A screen that sleeps halfway through makes every later step fail for a
  # reason that has nothing to do with the app.
  dsh svc power stayon true
  dsh settings put system screen_off_timeout 1800000
fi

adb logcat -G 8M < /dev/null > /dev/null 2>&1
adb logcat -b all -c < /dev/null > /dev/null 2>&1 || adb logcat -c < /dev/null
adb logcat -v threadtime -b main,system,crash < /dev/null > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!
trap 'kill "$LOGCAT" 2> /dev/null' EXIT

# ============================================================================
step "1. Install the no-SMS build and launch it from the launcher"

out=$(adb install -r "$NOSMS_APK" 2>&1 < /dev/null); rc=$?
echo "$out" | sed 's/^/    /'
check "$rc" "nosms debug APK installs"

COMPONENT=$(dsh cmd package resolve-activity --brief \
            -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tail -n 1)
echo "  launcher activity: $COMPONENT"
case "$COMPONENT" in
  "$PKG/$ACTIVITY") pass "LAUNCHER intent resolves to NativeMainActivity" ;;
  *) fail "LAUNCHER intent resolves to NativeMainActivity (got '$COMPONENT')"
     COMPONENT="$PKG/$ACTIVITY" ;;
esac

launch; check $? "first launch: am start reports Status ok"
echo "  first launch TotalTime: ${LAST_TOTAL:-?} ms (creates the ledger)"
sleep 5
alive; check $? "process alive 5 s after first launch"
if crashed; then fail "no FATAL EXCEPTION / ANR after first launch"
else pass "no FATAL EXCEPTION / ANR after first launch"; fi

if wait_for 40 01-first-launch 'Your money, tracked on your phone'; then
  pass "first screen is the introduction ('Your money, tracked on your phone.')"
else
  fail "first screen is the introduction ('Your money, tracked on your phone.')"
fi
texts 01-first-launch
shot 01-first-launch

# The no-SMS flavour must say it cannot read messages rather than offer a
# permission the manifest does not carry.
if scroll_to 01b-intro-end 'About this build'; then
  pass "nosms introduction says the build has no SMS access ('About this build')"
else
  fail "nosms introduction says the build has no SMS access ('About this build')"
fi

# ============================================================================
step "2. Finish the introduction and add an expense by hand"

if scroll_to 02a-intro-button '^Start using SpendWise$' &&
   tap 02a-intro-button '^Start using SpendWise$' &&
   wait_for 20 02b-home '^TOTAL BALANCE$'; then
  pass "'Start using SpendWise' opens Home (TOTAL BALANCE)"
else
  fail "'Start using SpendWise' opens Home (TOTAL BALANCE)"; failed_here 02b-home
fi
texts 02b-home

# The FAB first (it is on every list screen); the empty-state button second.
if { tap 02b-home '^Add a transaction$' desc || tap 02b-home '^Add a transaction$' text; } &&
   wait_for 15 02c-sheet '^Add transaction$'; then
  pass "'+' opens the Add transaction sheet"
else
  fail "'+' opens the Add transaction sheet"; failed_here 02c-sheet
fi
texts 02c-sheet

xy=$(ui field "$OUT/02c-sheet.xml" "$PKG" '^Amount$' 0) && dsh input tap $xy
sleep 1
dsh input text "$MANUAL_AMOUNT"
dump 02d-typed
xy=$(ui field "$OUT/02d-typed.xml" "$PKG" '^Merchant$' 1) && dsh input tap $xy
sleep 1
dsh input text "$MANUAL_PAYEE"
# The payee lookup is debounced; let it answer before Save reads it.
sleep 2

SAVE_RX="^Save ₹$MANUAL_AMOUNT\$"
if scroll_to 02e-filled "$SAVE_RX"; then
  pass "typed amount reached the form (button reads 'Save ₹$MANUAL_AMOUNT')"
else
  fail "typed amount reached the form (button reads 'Save ₹$MANUAL_AMOUNT')"
fi
texts 02e-filled
shot 02e-filled

tap 02e-filled "$SAVE_RX"
if wait_for 15 02f-home-after "^₹$MANUAL_AMOUNT\$"; then
  pass "Home shows ₹$MANUAL_AMOUNT spent after saving"
else
  fail "Home shows ₹$MANUAL_AMOUNT spent after saving"; failed_here 02f-home-after
fi
texts 02f-home-after

if tap 02f-home-after '^Activity$' && wait_for 15 02-after-add "(?i)^$MANUAL_PAYEE\$" &&
   on_screen 02-after-add "₹$MANUAL_AMOUNT\$"; then
  pass "Activity lists the new expense ($MANUAL_PAYEE, ₹$MANUAL_AMOUNT)"
else
  fail "Activity lists the new expense ($MANUAL_PAYEE, ₹$MANUAL_AMOUNT)"
fi
texts 02-after-add
shot 02-after-add
alive; check $? "process alive after the manual journey"

# ============================================================================
step "3. Update to the SMS build, grant SMS, send a bank SMS while in background"

# An update, not a fresh install: the ledger made above has to survive it,
# exactly as it must on a phone that moves from one flavour to the other.
out=$(adb install -r "$SMS_APK" 2>&1 < /dev/null); rc=$?
echo "$out" | sed 's/^/    /'
check "$rc" "sms debug APK installs over the nosms one (update, data kept)"

for p in RECEIVE_SMS READ_SMS; do
  out=$(dsh pm grant "$PKG" "android.permission.$p" 2>&1)
  [ -n "$out" ] && echo "    pm grant $p: $out"
done
granted=$(dsh dumpsys package "$PKG" | grep -cE 'android.permission.(RECEIVE|READ)_SMS: granted=true')
[ "$granted" -ge 2 ]; check $? "RECEIVE_SMS and READ_SMS granted ($granted of 2 report granted=true)"

if [ "${SDK:-0}" -ge 33 ]; then
  if dsh dumpsys package "$PKG" | grep -q 'android.permission.POST_NOTIFICATIONS'; then
    dsh pm grant "$PKG" android.permission.POST_NOTIFICATIONS
    pass "POST_NOTIFICATIONS granted"
  else
    skip "POST_NOTIFICATIONS: the app does not request it"
  fi
else
  skip "POST_NOTIFICATIONS: API $SDK has no such runtime permission"
fi

if [ -n "$(dsh 'command -v sqlite3')" ] && db "SELECT 1" | grep -q '^1$'; then HAVE_SQLITE=yes; fi
echo "  sqlite3 through run-as: $HAVE_SQLITE"

launch; check $? "launch after the update: am start reports Status ok"
if wait_for 30 03a-home '^TOTAL BALANCE$'; then
  pass "after the update the app opens on Home (introduction already done)"
else
  fail "after the update the app opens on Home (introduction already done)"; failed_here 03a-home
fi

dsh input keyevent KEYCODE_HOME
sleep 2
echo "  resumed now: $(dsh dumpsys activity activities | grep -m1 -E 'mResumedActivity|ResumedActivity' | sed 's/^ *//')"

if [ "$EMULATOR" != yes ]; then
  skip "bank SMS injection: needs an emulator (adb emu)"
else
  before=0
  [ "$HAVE_SQLITE" = yes ] && before=$(db "SELECT count(*) FROM transactions WHERE source='sms'")
  day=$(dsh date +%d-%b-%y)
  body="Rs.$SMS_AMOUNT.00 debited from a/c **1234 on $day to ZOMATO Ref 883120114455 UPI"
  sent=
  # A DLT header as Indian banks really send it; the emulator console only
  # takes [A-Za-z0-9] senders, so the bare header is the fallback.
  for sender in VM-HDFCBK HDFCBK; do
    reply=$(adb emu sms send "$sender" "$body" 2>&1 < /dev/null)
    echo "    emulator console, sender $sender: ${reply:-<no reply>}"
    case "$reply" in *KO*) continue ;; esac
    sent=$sender; break
  done
  if [ -n "$sent" ]; then pass "emulator accepted a bank debit SMS from $sent"
  else fail "emulator accepted a bank debit SMS"; fi

  if [ "$HAVE_SQLITE" = yes ]; then
    after=$before
    for i in $(seq 1 20); do
      after=$(db "SELECT count(*) FROM transactions WHERE source='sms'")
      [ "${after:-0}" -gt "${before:-0}" ] 2>/dev/null && break
      sleep 1
    done
    # Fields, never the message body: the body is the most sensitive text on
    # a phone, and the test has no reason to repeat it.
    echo "    sms rows: $(db "SELECT amount, merchantName, status, smsSender FROM transactions WHERE source='sms'" | tr '\n' ';')"
    echo "    senders:  $(db "SELECT sender, kind, trust, messageCount FROM sms_senders" | tr '\n' ';')"
    echo "    held:     $(db "SELECT count(*) FROM quarantine") message(s)"
    [ "${after:-0}" -gt "${before:-0}" ] 2>/dev/null
    check $? "receiver filed the SMS while the app was in the background (sms rows $before -> $after)"
  else
    skip "receiver check in the ledger: no sqlite3 on this device"
    sleep 10
  fi
fi

dsh cmd statusbar collapse > /dev/null 2>&1
launch; check $? "reopen from the launcher: am start reports Status ok"
if wait_for 30 03b-home '^TOTAL BALANCE$' && tap 03b-home '^Activity$' &&
   wait_for 20 03-after-sms "(?i)^$SMS_PAYEE\$" && on_screen 03-after-sms "₹$SMS_AMOUNT\$"; then
  pass "Activity shows the SMS transaction ($SMS_PAYEE, ₹$SMS_AMOUNT)"
else
  fail "Activity shows the SMS transaction ($SMS_PAYEE, ₹$SMS_AMOUNT)"
fi
on_screen 03-after-sms "(?i)^$MANUAL_PAYEE\$"
check $? "the hand-entered expense survived the update ($MANUAL_PAYEE still listed)"
texts 03-after-sms
shot 03-after-sms

# ============================================================================
step "4. Cold start"

times=()
for i in 1 2 3; do
  dsh am force-stop "$PKG"
  sleep 2
  launch
  echo "  cold start $i: TotalTime ${LAST_TOTAL:-?} ms"
  [ -n "$LAST_TOTAL" ] && times+=("$LAST_TOTAL")
  sleep 3
done
if [ "${#times[@]}" -gt 0 ]; then
  median=$(printf '%s\n' "${times[@]}" | sort -n | awk '{a[NR] = $1} END {print a[int((NR + 1) / 2)]}')
  pass "cold start measured: TotalTime ${times[*]} ms, median $median ms (first frame)"
else
  fail "cold start measured (am start -W printed no TotalTime)"
fi
alive; check $? "process alive after the cold starts"
if wait_for 30 04-cold-start '^TOTAL BALANCE$'; then
  pass "cold start reaches Home"
else
  fail "cold start reaches Home"
fi
texts 04-cold-start
shot 04-cold-start

# ============================================================================
step "5. Crashes, hangs and the app's own warnings"

sleep 2
if crashed; then fail "no FATAL EXCEPTION / ANR for $PKG during the whole run"
else pass "no FATAL EXCEPTION / ANR for $PKG during the whole run"; fi
echo "  the app's own warnings (SpendWiseLedger / SpendWiseSms):"
grep -E ' [WE] SpendWise(Ledger|Sms)' "$OUT/logcat.txt" | head -40 | sed 's/^/    /'

# ============================================================================
step "Summary"
printf '%s\n' "${RESULTS[@]}" | tee "$OUT/summary.txt"
echo "${#RESULTS[@]} checks, $FAILS failed"
[ "$FAILS" -eq 0 ]
