#!/usr/bin/env bash
# Smoke test for the native app (android/nativeapp) on a real Android device:
# install it, launch it, use it the way a person would, send it a bank SMS
# while it is in the background, and fail loudly if any of that goes wrong.
#
#   usage: native-smoke.sh NOSMS_DEBUG_APK SMS_DEBUG_APK [OUT_DIR [V3_NOSMS_DEBUG_APK]]
#
# V3_NOSMS_DEBUG_APK is a build from before the database went to version 4;
# given one, the test also updates a ledger it made to this build in place.
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
V3_APK=${4:-}
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
# Pasted in the no-SMS build; the reference differs from the SMS above, so
# the two are different payments.
PASTE_AMOUNT=312
PASTE_PAYEE=Swiggy
PASTE_REF=883120114499
# Two payments at one merchant, filed under a category the test then
# deletes and brings back. Not a built-in merchant, so nothing renames it.
MERCHANT=Timezone
MERCHANT_AMOUNTS="180 220"
CATEGORY=Entertainment
# Saved by the version-3 build before the update.
V3_AMOUNT=375
V3_PAYEE=Theobroma

# The paste page's own words. Its title is the same text as the buttons that
# open it, so the title alone cannot tell "arrived" from "still on Home".
PASTE_PAGE_RX='same parser that reads captured'
PASTE_RX='^Paste a bank message$'
SHEET_PASTE_RX='^Paste a bank message instead$'

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

leave_page() {  # leave_page NAME: the top bar's Back, then wait for Home
  # The app's own Back rather than the system key: on Home the system key
  # closes the app, so a second press after a slow first one would end the run.
  dump "$1" && tap "$1" '^Back$' text && wait_for 15 "$1" '^TOTAL BALANCE$'
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

db_ok() { [ -n "$(dsh 'command -v sqlite3')" ] && db "SELECT 1" | grep -q '^1$'; }

# Money as Insights.money writes it, for the whole rupee amounts this test
# uses: "₹400", not "₹400.0".
money() { printf '₹%s' "${1%.0}"; }

# The system Back key, only ever to close a sheet. Pages are left by the top
# bar's Back instead: on a tab the system key goes to Home, and on Home it
# closes the app, so one press too many would end the run.
close_sheet() { dsh input keyevent KEYCODE_BACK; sleep 1; }

page_back() {  # page_back NAME: one page back, by the top bar's Back
  dump "$1" && tap "$1" '^Back$' text && sleep 1
}

to_tabs() {  # to_tabs NAME: back through every open page to the tabs
  local k
  for k in 1 2 3 4; do
    dump "$1" || return 1
    on_screen "$1" '^Back$' || return 0
    tap "$1" '^Back$' text
    sleep 1
  done
  dump "$1" && ! on_screen "$1" '^Back$'
}

# --- another app's screen ---------------------------------------------------

wait_in() {  # wait_in SECONDS NAME PKG REGEX: wait_for, in PKG's windows
  local deadline=$((SECONDS + $1)) name=$2 pkg=$3 rx=$4
  while :; do
    if dump "$name" && ui has "$OUT/$name.xml" "$pkg" "$rx"; then return 0; fi
    if [ "$SECONDS" -ge "$deadline" ]; then return 1; fi
    sleep 1
  done
}

tap_in() {  # tap_in NAME PKG REGEX: tap the first match in PKG's windows
  local xy
  xy=$(ui center "$OUT/$1.xml" "$2" "$3") || return 1
  dsh input tap $xy
}

press_in() {  # press_in NAME PKG REGEX: long-press the first match
  local xy x y
  xy=$(ui center "$OUT/$1.xml" "$2" "$3") || return 1
  read -r x y <<< "$xy"
  dsh input swipe "$x" "$y" "$x" "$y" 1200
}

texts_in() {  # texts_in NAME PKG
  echo "  on screen [$1]:"
  ui texts "$OUT/$1.xml" "$2"
}

# --- the clipboard, the way a person fills it -------------------------------

# Someone on the no-SMS build gets a bank message into SpendWise the one way
# the phone offers: open it in their messages app, long-press it, Copy text,
# then paste it into the app. So that is what this does. There is no shell
# command that sets the clipboard (ClipboardService has never had a
# `cmd clipboard`), `input text` cannot type everything a bank sends, and a
# broadcast or a debug-only hook into the app would test a path no user has.
SMS_APP=
sms_app() {
  if [ -z "$SMS_APP" ]; then
    SMS_APP=$(dsh cmd role get-role-holders android.app.role.SMS 2>/dev/null |
              tr ';' '\n' | tr -d ' ' | grep -m1 .)
    [ -n "$SMS_APP" ] || SMS_APP=com.android.messaging
    # Its first run otherwise opens on a permissions screen. A grant the app
    # never asks for is refused, harmlessly.
    for p in READ_SMS SEND_SMS RECEIVE_SMS RECEIVE_MMS READ_PHONE_STATE READ_CONTACTS; do
      dsh pm grant "$SMS_APP" "android.permission.$p" > /dev/null 2>&1
    done
  fi
  echo "$SMS_APP"
}

resumed() { dsh dumpsys activity activities | grep -m1 -E 'mResumedActivity|ResumedActivity'; }

copy_from_messages() {  # copy_from_messages NAME SENDER BODY_REGEX: copy that message's text
  local name=$1 sender=$2 rx=$3 app comp
  app=$(sms_app)
  dsh cmd statusbar collapse > /dev/null 2>&1
  # Straight into the sender's conversation, as tapping its notification
  # would. Never a tap on the bubble itself: banks' reference numbers are
  # linkified as phone numbers, and a tap there dials.
  dsh am start -W -a android.intent.action.SENDTO -d "smsto:$sender" -p "$app" > /dev/null 2>&1
  sleep 2
  echo "  messages app: $app; $(resumed | sed 's/^ *//')"
  if ! wait_in 15 "$name-a" "$app" "$rx" || resumed | grep -qi 'conversationlist'; then
    # Not the conversation: the list, then the bank's row in it.
    comp=$(dsh cmd package resolve-activity --brief -a android.intent.action.MAIN \
           -c android.intent.category.LAUNCHER "$app" | tail -n 1)
    dsh am start -W -n "$comp" > /dev/null 2>&1
    wait_in 15 "$name-a" "$app" "$sender" && tap_in "$name-a" "$app" "$sender" && sleep 2 &&
      wait_in 15 "$name-a" "$app" "$rx" ||
      { echo "  the message is not on screen:"; texts_in "$name-a" ""; shot "$name-a"; return 1; }
  fi
  press_in "$name-a" "$app" "$rx" || return 1
  sleep 1
  dump "$name-b"
  if ! tap_in "$name-b" "$app" '(?i)^copy( text)?$'; then
    tap_in "$name-b" "$app" '(?i)^more options$' && sleep 1 && dump "$name-b" &&
      tap_in "$name-b" "" '(?i)^copy( text)?$' ||
      { echo "  no Copy action after the long-press:"; texts_in "$name-b" ""; shot "$name-b"; return 1; }
  fi
  sleep 1
  return 0
}

paste_into() {  # paste_into NAME LABEL_REGEX EXPECT_REGEX: paste the clipboard into a field
  local name=$1 label=$2 want=$3 xy x y
  dump "$name" || return 1
  xy=$(ui field "$OUT/$name.xml" "$PKG" "$label" 0) || return 1
  dsh input tap $xy
  sleep 1
  # KEYCODE_PASTE: what a keyboard's paste key sends, and what Compose's text
  # field maps to its own paste from the system clipboard.
  dsh input keyevent 279
  sleep 1
  dump "$name-in" && ui has "$OUT/$name-in.xml" "$PKG" "$want" && return 0
  # The touch route instead: long-press the field, then the toolbar's Paste.
  read -r x y <<< "$xy"
  dsh input swipe "$x" "$y" "$x" "$y" 1200
  sleep 1
  dump "$name-menu" && tap_in "$name-menu" "" '(?i)^paste$'
  sleep 1
  dump "$name-in" && ui has "$OUT/$name-in.xml" "$PKG" "$want"
}

# --- forms ------------------------------------------------------------------

# The category chips scroll sideways, and a chip scrolled out of the row is
# not in the dump at all — so "not offered" means the whole row was walked.
# 0: found (CHIP_DUMP names the dump it is in); 1: the whole row has been
# seen and it is not there; 2: there is no chip row on screen.
CHIP_DUMP=
chip_scan() {  # chip_scan NAME REGEX [tap]
  local name=$1 rx=$2 act=${3:-} i y= prev= cur xy
  for i in 0 1 2 3 4 5 6; do
    dump "$name-$i" || return 2
    if [ -z "$y" ]; then
      xy=$(ui center "$OUT/$name-$i.xml" "$PKG" '^Uncategorised$') || return 2
      y=${xy#* }
    fi
    if on_screen "$name-$i" "$rx"; then
      CHIP_DUMP=$name-$i
      [ "$act" = tap ] && tap "$name-$i" "$rx"
      return 0
    fi
    cur=$(ui words "$OUT/$name-$i.xml" "$PKG" | tr '\n' '|')
    [ "$cur" = "$prev" ] && return 1
    prev=$cur
    dsh input swipe $((W * 8 / 10)) "$y" $((W * 2 / 10)) "$y" 500
    sleep 1
  done
  return 1
}

add_sheet() {  # add_sheet NAME: open the + sheet from a list screen
  dump "$1" || return 1
  { tap "$1" '^Add a transaction$' desc || tap "$1" '^Add a transaction$' text; } || return 1
  wait_for 15 "$1-sheet" '^Add transaction$'
}

add_expense() {  # add_expense NAME AMOUNT PAYEE [CATEGORY]: through the + sheet
  local n=$1 amount=$2 payee=$3 cat=${4:-} xy
  add_sheet "$n-a" || return 1
  cp "$OUT/$n-a-sheet.xml" "$OUT/$n-b.xml"
  xy=$(ui field "$OUT/$n-b.xml" "$PKG" '^Amount$' 0) && dsh input tap $xy
  sleep 1
  dsh input text "$amount"
  dump "$n-c"
  xy=$(ui field "$OUT/$n-c.xml" "$PKG" '^Merchant$' 1) && dsh input tap $xy
  sleep 1
  dsh input text "$payee"
  # The payee lookup is debounced; let it answer before Save reads it.
  sleep 2
  if [ -n "$cat" ]; then
    chip_scan "$n-chip" "^$cat\$" tap || { echo "  no '$cat' chip"; return 1; }
    sleep 1
  fi
  scroll_to "$n-d" "^Save ₹$amount\$" && tap "$n-d" "^Save ₹$amount\$" || return 1
  # The sheet closes on Save; the + is back once it has.
  wait_for 15 "$n-e" '^Add a transaction$'
}

open_paste() {  # open_paste NAME: Settings > SMS capture > Paste a bank message
  dump "$1-a" || return 1
  tap "$1-a" '^Settings$' || return 1
  scroll_to "$1-b" '^Paste a bank message$' || return 1
  tap "$1-b" '^Paste a bank message$' || return 1
  wait_for 15 "$1-c" '^Read it$'
}

read_paste() {  # read_paste NAME: tap Read it and wait for the card it draws
  tap "$1" '^Read it$' || return 1
  wait_for 20 "$1-card" \
    "^Here's what SpendWise read\$|^Check this one before saving it\$|^Already in your ledger\$|^You deleted this one before\$|^That doesn't read as a bank transaction\$"
}

LOG_MARK=0
crashed_since() {  # crashed_since LINE: crash/ANR evidence in logcat after LINE
  tail -n +"$(($1 + 1))" "$OUT/logcat.txt" > "$OUT/logcat-since-$1.txt"
  ui crashes "$PKG" "$OUT/logcat-since-$1.txt"
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

# In this build pasting is how bank data arrives at all, so it is one tap
# from Home: the empty ledger's first button.
if tap 02b-home "$PASTE_RX" text && wait_for 15 02b1-paste "$PASTE_PAGE_RX"; then
  pass "nosms: Home -> 'Paste a bank message' opens the paste page (one tap)"
else
  fail "nosms: Home -> 'Paste a bank message' opens the paste page (one tap)"
  failed_here 02b1-paste
fi
texts 02b1-paste
shot 02b1-paste
if leave_page 02b-home; then
  pass "Back from the paste page returns to Home"
else
  fail "Back from the paste page returns to Home"; failed_here 02b-home
fi

# The FAB first (it is on every list screen); the empty-state button second.
if { tap 02b-home '^Add a transaction$' desc || tap 02b-home '^Enter by hand$' text; } &&
   wait_for 15 02c-sheet '^Add transaction$'; then
  pass "'+' opens the Add transaction sheet"
else
  fail "'+' opens the Add transaction sheet"; failed_here 02c-sheet
fi
texts 02c-sheet
on_screen 02c-sheet "$SHEET_PASTE_RX"
check $? "the add sheet offers 'Paste a bank message instead'"

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
# Not only while the ledger is empty: it stays the build's way in.
on_screen 02f-home-after "$PASTE_RX"
check $? "nosms: Home keeps 'Paste a bank message' once the ledger has a row"

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
step "3. Paste a bank message (no-SMS build): read, save once, refuse a second copy"

# The no-SMS build's only way bank data gets in. The message arrives as a
# real SMS in the Messages app, is copied out of it and pasted, as a person
# on that build does it. SWIGGY is a built-in merchant, so the card should
# already name the payee.
PASTED=no
db_ok && HAVE_SQLITE=yes
if [ "$EMULATOR" != yes ]; then
  skip "paste: needs an emulator to deliver the bank SMS that is copied"
else
  day=$(dsh date +%d-%b-%y)
  PASTE_BODY="Rs.$PASTE_AMOUNT.00 debited from a/c **1234 on $day to SWIGGY Ref $PASTE_REF UPI"
  PASTE_SMS_RX="SWIGGY Ref $PASTE_REF"
  reply=$(adb emu sms send VM-HDFCBK "$PASTE_BODY" 2>&1 < /dev/null)
  echo "    emulator console: ${reply:-<no reply>}"
  sleep 3
  copy_from_messages p01-messages VM-HDFCBK "$PASTE_SMS_RX"
  check $? "copied a bank debit SMS out of the Messages app (long-press, Copy text)"
  shot p01-messages-b

  launch > /dev/null
  if open_paste p02-open; then
    pass "Settings > 'Paste a bank message' opens the paste page"
  else
    fail "Settings > 'Paste a bank message' opens the paste page"; failed_here p02-open-c
  fi

  paste_into p03-paste '^Bank message$' "$PASTE_SMS_RX"
  check $? "the copied SMS pastes into 'Bank message' from the clipboard"

  if read_paste p03-paste-in && on_screen p03-paste-in-card "^Here's what SpendWise read\$"; then
    pass "'Read it' shows the preview card ('Here's what SpendWise read')"
  else
    fail "'Read it' shows the preview card ('Here's what SpendWise read')"
  fi
  texts p03-paste-in-card
  shot p03-paste-in-card
  got=$(ui value "$OUT/p03-paste-in-card.xml" "$PKG" '^Amount$' 1)
  [ "$got" = "$PASTE_AMOUNT" ]
  check $? "the card read the amount: $PASTE_AMOUNT (field shows '$got')"
  got=$(ui value "$OUT/p03-paste-in-card.xml" "$PKG" '^Payee$' 2)
  [ "$(echo "$got" | tr '[:upper:]' '[:lower:]')" = "$(echo "$PASTE_PAYEE" | tr '[:upper:]' '[:lower:]')" ]
  check $? "the card read the payee: $PASTE_PAYEE (field shows '$got')"

  if scroll_to p04-save "^Save ₹$PASTE_AMOUNT\$" && tap p04-save "^Save ₹$PASTE_AMOUNT\$" &&
     wait_for 15 p04-saved '^Read it$' && ! on_screen p04-saved "^Here's what SpendWise read\$"; then
    pass "Save ₹$PASTE_AMOUNT files it and clears the card"
    PASTED=yes
  else
    fail "Save ₹$PASTE_AMOUNT files it and clears the card"; failed_here p04-saved
  fi

  to_tabs p05-back
  if dump p05-activity && tap p05-activity '^Activity$' &&
     wait_for 15 p05-activity "(?i)^$PASTE_PAYEE\$"; then
    rows=$(ui count "$OUT/p05-activity.xml" "$PKG" "(?i)^$PASTE_PAYEE\$")
    ledger=?
    [ "$HAVE_SQLITE" = yes ] && ledger=$(db "SELECT count(*) FROM transactions WHERE isDeleted = 0 AND amount = $PASTE_AMOUNT")
    [ "$rows" = 1 ] && on_screen p05-activity "₹$PASTE_AMOUNT\$" && { [ "$ledger" = 1 ] || [ "$ledger" = '?' ]; }
    check $? "Activity lists the pasted payment exactly once ($PASTE_PAYEE ₹$PASTE_AMOUNT: $rows on screen, $ledger in the ledger)"
  else
    fail "Activity lists the pasted payment exactly once ($PASTE_PAYEE ₹$PASTE_AMOUNT)"
  fi
  texts p05-activity
  shot p05-activity

  # The clipboard still holds the same message.
  open_paste p06-open
  paste_into p06-paste '^Bank message$' "$PASTE_SMS_RX"
  if read_paste p06-paste-in && on_screen p06-paste-in-card '^Already in your ledger$' &&
     on_screen p06-paste-in-card 'one bank message is one transaction'; then
    pass "pasting the same message again says 'Already in your ledger'"
  else
    fail "pasting the same message again says 'Already in your ledger'"
  fi
  texts p06-paste-in-card
  shot p06-paste-in-card
  tap p06-paste-in-card '^Done$'
  if [ "$HAVE_SQLITE" = yes ]; then
    n=$(db "SELECT count(*) FROM transactions WHERE amount = $PASTE_AMOUNT")
    [ "$n" = 1 ]; check $? "the second paste wrote nothing (rows for ₹$PASTE_AMOUNT: $n)"
  fi

  # A bank message in wording the parser does not know: not a transaction to
  # it, and exactly what the unreadable-messages list in Settings is for.
  sent=$(dsh date +%d-%m-%y)
  UNREAD_BODY="Rs 500 deducted from your A/c XX1234 on $sent towards annual fee"
  UNREAD_RX='towards annual fee'
  reply=$(adb emu sms send VM-HDFCBK "$UNREAD_BODY" 2>&1 < /dev/null)
  echo "    emulator console: ${reply:-<no reply>}"
  sleep 3
  copy_from_messages p07-messages VM-HDFCBK "$UNREAD_RX"
  check $? "copied a second bank SMS (one the parser cannot read) out of the Messages app"
  launch > /dev/null
  wait_for 15 p08-paste '^Read it$'
  paste_into p08-paste '^Bank message$' "$UNREAD_RX"
  if read_paste p08-paste-in && on_screen p08-paste-in-card "^That doesn't read as a bank transaction\$" &&
     on_screen p08-paste-in-card '^Nothing was saved\.$'; then
    pass "a pasted message that is not a transaction is refused ('That doesn't read as a bank transaction')"
  else
    fail "a pasted message that is not a transaction is refused ('That doesn't read as a bank transaction')"
  fi
  on_screen p08-paste-in-card 'masked is in Settings'
  check $? "the refusal says a masked copy is kept in Settings"
  texts p08-paste-in-card
  shot p08-paste-in-card
  tap p08-paste-in-card '^Clear$'
  if [ "$HAVE_SQLITE" = yes ]; then
    n=$(db "SELECT count(*) FROM transactions WHERE amount = 500")
    m=$(db "SELECT count(*) FROM parse_misses")
    [ "$n" = 0 ] && [ "$m" = 1 ]
    check $? "nothing filed for it, one masked miss stored (rows for ₹500: $n, parse_misses: $m)"
  fi

  page_back p09-back
  if scroll_to p09-settings "^1 bank message couldn.t be read\$"; then
    pass "Settings > SMS capture says '1 bank message couldn't be read'"
  else
    fail "Settings > SMS capture says '1 bank message couldn't be read'"
  fi
  texts p09-settings
  shot p09-settings
  to_tabs p09-back
fi

# ============================================================================
step "4. Update to the SMS build, grant SMS, send a bank SMS while in background"

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

# Capture is on here, so Home stays as it was and pasting — for a message
# capture missed — is under "+" instead.
if on_screen 03a-home "$PASTE_RX"; then
  fail "sms: Home carries no paste control of its own"
else
  pass "sms: Home carries no paste control of its own"
fi
if tap 03a-home '^Add a transaction$' desc &&
   wait_for 15 03a1-sheet "$SHEET_PASTE_RX" && tap 03a1-sheet "$SHEET_PASTE_RX" text &&
   wait_for 15 03a2-paste "$PASTE_PAGE_RX"; then
  pass "sms: '+' -> 'Paste a bank message instead' opens the paste page (two taps)"
else
  fail "sms: '+' -> 'Paste a bank message instead' opens the paste page (two taps)"
  failed_here 03a2-paste
fi
texts 03a2-paste
if leave_page 03a-home; then
  pass "sms: Back from the paste page returns to Home"
else
  fail "sms: Back from the paste page returns to Home"; failed_here 03a-home
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
# The pasted message is in the inbox too, and the SMS build scanned the inbox
# when it first opened. One message is one payment, whichever way it came in.
if [ "$PASTED" = yes ] && [ "$HAVE_SQLITE" = yes ]; then
  n=$(db "SELECT count(*) FROM transactions WHERE isDeleted = 0 AND amount = $PASTE_AMOUNT")
  [ "$n" = 1 ]
  check $? "the SMS build's inbox scan did not file the pasted message again (₹$PASTE_AMOUNT rows: $n)"
fi
texts 03-after-sms
shot 03-after-sms

# ============================================================================
step "5. Cold start"

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
step "6. Merchant page: from the report's merchant row, its totals, Forget and Undo"

i=0
for amount in $MERCHANT_AMOUNTS; do
  i=$((i + 1))
  add_expense "m0$i-add" "$amount" "$MERCHANT" "$CATEGORY"
  check $? "added ₹$amount at $MERCHANT under $CATEGORY through the + sheet"
done

want_n=2
want_total=400.0
if [ "$HAVE_SQLITE" = yes ]; then
  read -r want_n want_total < <(db "SELECT count(*), sum(amount) FROM transactions WHERE isDeleted = 0 AND type = 'expense' AND merchantName = '$MERCHANT'" |
                                awk -F' \\| ' '{print $1, $2}')
  echo "  the ledger: $want_n payments at $MERCHANT, $want_total in all"
fi

if dump m03-home && tap m03-home '^Report$' && wait_for 15 m03-report '^Back$'; then
  pass "Home > Report opens the report"
else
  fail "Home > Report opens the report"; failed_here m03-report
fi

# The row under "Merchants this month", not the same name anywhere above it.
opened=no
for k in 1 2 3 4 5 6 7 8 9 10; do
  if dump m04-report && xy=$(ui after "$OUT/m04-report.xml" "$PKG" '(?i)^merchants this month$' "^$MERCHANT\$"); then
    dsh input tap $xy
    opened=yes
    break
  fi
  dsh input swipe $((W / 2)) $((H * 7 / 10)) $((W / 2)) $((H * 3 / 10)) 600
  sleep 1
done
texts m04-report
shot m04-report
if [ "$opened" = yes ] && wait_for 15 m05-merchant '^SPENT HERE$'; then
  pass "tapping $MERCHANT under 'Merchants this month' opens its merchant page"
else
  fail "tapping $MERCHANT under 'Merchants this month' opens its merchant page"; failed_here m05-merchant
fi
texts m05-merchant
shot m05-merchant

got=$(ui next "$OUT/m05-merchant.xml" "$PKG" '^SPENT HERE$')
[ "$got" = "$(money "$want_total")" ]
check $? "merchant page total is $(money "$want_total") (shows '$got')"
got=$(ui next "$OUT/m05-merchant.xml" "$PKG" '^Payments$')
[ "$got" = "$want_n" ]
check $? "merchant page payment count is $want_n (shows '$got')"

learned_before=$(db "SELECT confirmationCount FROM learning WHERE merchantName = '$MERCHANT'")
FORGET_RX="^Forget .$MERCHANT.\$"
if scroll_to m06-learned "$FORGET_RX" && on_screen m06-learned "^New payments from $MERCHANT are filed under $CATEGORY\\.\$"; then
  pass "the learning card says new $MERCHANT payments are filed under $CATEGORY, with a Forget button"
else
  fail "the learning card says new $MERCHANT payments are filed under $CATEGORY, with a Forget button"
fi
texts m06-learned
shot m06-learned

if tap m06-learned "$FORGET_RX" desc && wait_for 10 m07-forgot '^Undo$'; then
  pass "Forget offers Undo ('Forgot “$MERCHANT”…')"
  gone=1
  for k in 1 2 3 4 5; do
    dump m07-forgot && ! on_screen m07-forgot "$FORGET_RX" && { gone=0; break; }
    sleep 1
  done
  rows=$(db "SELECT count(*) FROM learning WHERE merchantName = '$MERCHANT'")
  [ "$gone" = 0 ] && { [ "$HAVE_SQLITE" != yes ] || [ "$rows" = 0 ]; }
  check $? "after Forget the mapping is gone from the card and the ledger (learning rows: $rows)"
  shot m07-forgot
  tap m07-forgot '^Undo$'
  if wait_for 10 m08-undone "$FORGET_RX"; then
    learned_after=$(db "SELECT confirmationCount FROM learning WHERE merchantName = '$MERCHANT'")
    [ "$HAVE_SQLITE" != yes ] || [ "$learned_after" = "$learned_before" ]
    check $? "Undo puts the mapping back as it was (confirmations $learned_before -> $learned_after)"
  else
    fail "Undo puts the mapping back as it was"; failed_here m08-undone
  fi
else
  fail "Forget offers Undo ('Forgot “$MERCHANT”…')"; failed_here m07-forgot
fi
to_tabs m09-back

# ============================================================================
step "7. Deleting a category keeps what is filed under it; adding the name back restores it"

cat_id=
filed=$want_n
if [ "$HAVE_SQLITE" = yes ]; then
  cat_id=$(db "SELECT id FROM categories WHERE name = '$CATEGORY'")
  filed=$(db "SELECT count(*) FROM transactions WHERE isDeleted = 0 AND categoryId = '$cat_id'")
fi
if [ "$filed" = 1 ]; then KEPT="Its 1 transaction is kept, still filed under $CATEGORY."
else KEPT="Its $filed transactions are kept, still filed under $CATEGORY."; fi
echo "  $CATEGORY is $cat_id, with $filed live transactions"

dump c01-home && tap c01-home '^Budgets$'
if scroll_to c01-budgets "^$CATEGORY\$"; then
  got=$(ui next "$OUT/c01-budgets.xml" "$PKG" "^$CATEGORY\$")
  [ "$got" = "$(money "$want_total")" ]
  check $? "Budgets shows $CATEGORY with $(money "$want_total") spent (shows '$got')"
else
  fail "Budgets shows $CATEGORY with $(money "$want_total") spent"
fi

dump c02-budgets && tap c02-budgets '^Categories$'
if wait_for 15 c02-dialog '^New category$' && scroll_to c02-dialog "^Delete $CATEGORY\$" &&
   tap c02-dialog "^Delete $CATEGORY\$" desc && wait_for 10 c03-confirm "^Delete $CATEGORY\\?\$"; then
  pass "Budgets > Categories > Delete $CATEGORY asks first"
else
  fail "Budgets > Categories > Delete $CATEGORY asks first"; failed_here c03-confirm
fi
texts c03-confirm
shot c03-confirm
on_screen c03-confirm "^$KEPT\$"
check $? "the question names the count: '$KEPT'"

tap c03-confirm "^Delete $CATEGORY\$" text
if wait_for 10 c04-list '^New category$' && sleep 1 && dump c04-list &&
   ! on_screen c04-list "^$CATEGORY\$|^Delete $CATEGORY\$"; then
  pass "after confirming, $CATEGORY is gone from the category list"
else
  fail "after confirming, $CATEGORY is gone from the category list"
fi
shot c04-list
if [ "$HAVE_SQLITE" = yes ]; then
  state=$(db "SELECT isArchived FROM categories WHERE id = '$cat_id'")
  still=$(db "SELECT count(*) FROM transactions WHERE isDeleted = 0 AND categoryId = '$cat_id'")
  [ "$state" = 1 ] && [ "$still" = "$filed" ]
  check $? "the ledger archived it and kept its transactions on it (isArchived $state, $still of $filed still filed)"
fi
tap c04-list '^Done$'
sleep 1
if scroll_to c05-budgets "^$CATEGORY\$"; then
  fail "Budgets no longer lists $CATEGORY"
else
  pass "Budgets no longer lists $CATEGORY"
fi

dump c06-tabs && tap c06-tabs '^Activity$'
wait_for 15 c06-activity "(?i)^$MERCHANT\$"
if add_sheet c07-new; then
  chip_scan c07-chips "^$CATEGORY\$"; rc=$?
  case $rc in
    1) pass "the add sheet no longer offers $CATEGORY (every chip seen)" ;;
    0) fail "the add sheet no longer offers $CATEGORY (still a chip)"; shot "$CHIP_DUMP" ;;
    *) fail "the add sheet no longer offers $CATEGORY (no chip row found)"; failed_here c07-chips ;;
  esac
else
  fail "the add sheet no longer offers $CATEGORY (the sheet did not open)"
fi
close_sheet

# What is already filed there still says so where it is edited.
dump c08-activity
if tap c08-activity "(?i)^$MERCHANT\$" && wait_for 10 c08-row '^Edit$' && tap c08-row '^Edit$' &&
   wait_for 15 c09-edit '^Edit transaction$'; then
  chip_scan c09-chips "^$CATEGORY\$"; rc=$?
  state=$( [ "$rc" = 0 ] && ui checked "$OUT/$CHIP_DUMP.xml" "$PKG" "^$CATEGORY\$")
  [ "$rc" = 0 ] && [ "$state" = checked ]
  check $? "an existing $MERCHANT payment still shows $CATEGORY, selected, on its edit sheet ($state)"
  [ "$rc" = 0 ] && shot "$CHIP_DUMP"
else
  fail "an existing $MERCHANT payment still shows $CATEGORY, selected, on its edit sheet"; failed_here c09-edit
fi
close_sheet

dump c10-tabs && tap c10-tabs '^Budgets$'
sleep 1
dump c10-budgets && tap c10-budgets '^Categories$'
if wait_for 15 c11-dialog '^New category$' &&
   xy=$(ui field "$OUT/c11-dialog.xml" "$PKG" '^New category$' 0) && dsh input tap $xy && sleep 1 &&
   dsh input text "$CATEGORY" && sleep 1 && dump c11-typed && tap c11-typed '^Add$' &&
   sleep 1 && scroll_to c12-list "^Delete $CATEGORY\$"; then
  pass "adding the name $CATEGORY again puts it back in the category list"
else
  fail "adding the name $CATEGORY again puts it back in the category list"; failed_here c12-list
fi
if [ "$HAVE_SQLITE" = yes ]; then
  back_id=$(db "SELECT id FROM categories WHERE name = '$CATEGORY' AND isArchived = 0")
  still=$(db "SELECT count(*) FROM transactions WHERE isDeleted = 0 AND categoryId = '$cat_id'")
  [ "$back_id" = "$cat_id" ] && [ "$still" = "$filed" ]
  check $? "it came back under its old id with its $filed transactions (id ${back_id:-none}, $still filed)"
fi
dump c12-list && tap c12-list '^Done$'
sleep 1
if scroll_to c13-budgets "^$CATEGORY\$"; then
  got=$(ui next "$OUT/c13-budgets.xml" "$PKG" "^$CATEGORY\$")
  [ "$got" = "$(money "$want_total")" ]
  check $? "Budgets lists $CATEGORY again with its $(money "$want_total") (shows '$got')"
else
  fail "Budgets lists $CATEGORY again with its $(money "$want_total")"
fi
shot c13-budgets

dump c14-tabs && tap c14-tabs '^Activity$'
if wait_for 15 c14-activity '^Add a transaction$' && add_sheet c14-new; then
  chip_scan c14-chips "^$CATEGORY\$"
  check $? "the add sheet offers $CATEGORY again"
else
  fail "the add sheet offers $CATEGORY again (the sheet did not open)"
fi
close_sheet

# ============================================================================
step "8. A real database upgrade, version 3 -> 4, in place"

# The last build whose ledger is version 3 (built by the workflow from
# 2b0e3c1e) is installed fresh, used, and then updated to this build with
# `adb install -r`, as a phone with the old app would be. Room opens the old
# file through MIGRATION_3_4 and checks every table against the entities; a
# migration that leaves any difference throws IllegalStateException on open.
if [ -z "$V3_APK" ]; then
  skip "v3 -> v4 upgrade: no version-3 APK given"
elif [ ! -s "$V3_APK" ]; then
  fail "v3 -> v4 upgrade: the version-3 APK was built ($V3_APK is missing)"
else
  dsh pm uninstall "$PKG" > /dev/null
  out=$(adb install "$V3_APK" 2>&1 < /dev/null); rc=$?
  echo "$out" | sed 's/^/    /'
  check "$rc" "the version-3 nosms debug APK installs on a clean slate"

  launch > /dev/null
  if wait_for 40 u01-intro 'Your money, tracked on your phone' &&
     scroll_to u01-intro '^Start using SpendWise$' && tap u01-intro '^Start using SpendWise$' &&
     wait_for 20 u02-home '^TOTAL BALANCE$'; then
    pass "the version-3 build opens and its introduction leads to Home"
  else
    fail "the version-3 build opens and its introduction leads to Home"; failed_here u02-home
  fi
  db_ok && HAVE_SQLITE=yes
  ver=$(db "PRAGMA user_version")
  [ "$ver" = 3 ]; check $? "its ledger is database version 3 (user_version $ver)"

  add_expense u03-add "$V3_AMOUNT" "$V3_PAYEE" "$CATEGORY"
  check $? "the version-3 build saves an expense ($V3_PAYEE ₹$V3_AMOUNT, under $CATEGORY)"
  before=$(db "SELECT count(*) FROM transactions WHERE isDeleted = 0 AND merchantName = '$V3_PAYEE'")
  echo "    v3 ledger: $(db "SELECT count(*) FROM transactions") transaction(s), $(db "SELECT count(*) FROM categories") categories, $(db "SELECT count(*) FROM learning") learned"

  LOG_MARK=$(wc -l < "$OUT/logcat.txt")
  out=$(adb install -r "$NOSMS_APK" 2>&1 < /dev/null); rc=$?
  echo "$out" | sed 's/^/    /'
  case "$out" in *UPDATE_INCOMPATIBLE*) echo "    the two builds are signed with different keys" ;; esac
  check "$rc" "this build installs over the version-3 one in place (adb install -r, no uninstall)"

  launch; check $? "first launch after the update: am start reports Status ok"
  sleep 5
  alive; check $? "process alive 5 s after the update"
  if crashed_since "$LOG_MARK"; then
    fail "no crash opening the version-3 ledger (Room throws IllegalStateException on a bad migration)"
    grep -E 'Migration|IllegalStateException|SQLiteException|room' "$OUT/logcat-since-$LOG_MARK.txt" | head -40 | sed 's/^/    /'
  else
    pass "no crash opening the version-3 ledger (Room throws IllegalStateException on a bad migration)"
  fi

  if wait_for 30 u04-home '^TOTAL BALANCE$'; then
    pass "after the update the app opens on Home, not the introduction (settings kept)"
  else
    fail "after the update the app opens on Home, not the introduction (settings kept)"; failed_here u04-home
  fi
  ver=$(db "PRAGMA user_version")
  table=$(db "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'parse_misses'")
  [ "$ver" = 4 ] && [ "$table" = 1 ]
  check $? "the ledger is version 4 with a parse_misses table (user_version $ver, table $table)"
  after=$(db "SELECT count(*) FROM transactions WHERE isDeleted = 0 AND merchantName = '$V3_PAYEE'")
  kept=$(db "SELECT count(*) FROM transactions t JOIN categories c ON c.id = t.categoryId WHERE t.merchantName = '$V3_PAYEE' AND c.name = '$CATEGORY'")
  [ "$after" = 1 ] && [ "$before" = 1 ] && [ "$kept" = 1 ]
  check $? "the version-3 expense is in the version-4 ledger, still under $CATEGORY (rows $before -> $after)"

  dump u05-home && tap u05-home '^Activity$'
  if wait_for 15 u05-activity "(?i)^$V3_PAYEE\$" && on_screen u05-activity "₹$V3_AMOUNT\$"; then
    pass "Activity shows the expense saved before the update ($V3_PAYEE, ₹$V3_AMOUNT)"
  else
    fail "Activity shows the expense saved before the update ($V3_PAYEE, ₹$V3_AMOUNT)"
  fi
  texts u05-activity
  shot u05-activity

  # The new table, written and read back through the app: an unreadable
  # bank message pasted in shows up in Settings.
  if [ "$EMULATOR" = yes ] && [ -n "${UNREAD_RX:-}" ]; then
    copy_from_messages u06-messages VM-HDFCBK "$UNREAD_RX"
    launch > /dev/null
    if open_paste u07-open && paste_into u07-paste '^Bank message$' "$UNREAD_RX" &&
       read_paste u07-paste-in && on_screen u07-paste-in-card "^That doesn't read as a bank transaction\$" &&
       page_back u08-back && scroll_to u08-settings "^1 bank message couldn.t be read\$"; then
      pass "Settings shows the unreadable-messages section after the update ('1 bank message couldn't be read')"
    else
      fail "Settings shows the unreadable-messages section after the update ('1 bank message couldn't be read')"
      failed_here u08-settings
    fi
    texts u08-settings
    shot u08-settings
    m=$(db "SELECT count(*) FROM parse_misses")
    [ "$m" = 1 ]; check $? "the miss is a row in the migrated parse_misses table ($m)"
  else
    skip "unreadable messages after the update: needs the emulator's Messages app"
  fi
  alive; check $? "process alive after the upgraded journey"
fi

# ============================================================================
step "9. Crashes, hangs and the app's own warnings"

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
