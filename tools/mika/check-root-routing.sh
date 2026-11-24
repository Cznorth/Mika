#!/system/bin/sh
# Run as root on the OnePlus, after activating the module and updating Mika Dev.
# Server invocation must hold /run/lock/sidekick-android-install.lock.
set -eu
umask 077

[ "$(id -u)" = 0 ]
[ "$(getprop ro.product.device)" = OP60FFL1 ]
backlight=/sys/class/backlight/panel0-backlight/brightness
actual_backlight=/sys/class/backlight/panel0-backlight/actual_brightness
shot=/data/local/tmp/mika-isolated-session.png
[ ! -e "$shot" ] || { echo existing_session_screenshot; exit 2; }
out=$(mktemp -d /data/local/tmp/mika-routing.XXXXXX)
child= monitor= events=
cleanup() {
    trap - EXIT HUP INT TERM
    { exec 3>&-; } 2>/dev/null || true
    if [ -n "$child" ]; then wait "$child" || true; fi
    for pid in "$monitor" "$events"; do
        if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
    done
    echo "evidence=$out"
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM

is_off() {
    [ "$(cat "$backlight")" = 0 ] && [ "$(cat "$actual_backlight")" = 0 ] &&
        dumpsys display | awk '/mCommittedState=/ && !seen++ { off = /mCommittedState=OFF$/ } END { exit !off }'
}
# Wait for the user's normal screen timeout. Never send a power key or force sleep.
for attempt in $(seq 1 90); do
    if is_off; then sleep 1; if is_off; then break; fi; fi
    sleep 2
done
is_off || { echo natural_screen_off_not_observed; exit 3; }
activities=$(dumpsys activity activities)
if printf '%s\n' "$activities" | grep -Eq 'com\.MobileTicket|Display #[1-9]'; then
    echo existing_app_or_virtual_display; exit 4
fi
if dumpsys telephony.registry | grep -Eq 'mCallState=[12]'; then echo call_active; exit 4; fi
foreground=$(printf '%s\n' "$activities" | sed -n 's/.*topResumedActivity=.* u[0-9][0-9]* \([^/ ]*\)\/.*/\1/p' | head -1)
if [ -n "$foreground" ] && dumpsys package "$foreground" | grep -Eq 'category=0([[:space:]]|$)|\bIS_GAME\b'; then
    echo game_active; exit 4
fi
focus() {
    dumpsys window displays | awk '
        /Display: mDisplayId=/ { primary = /mDisplayId=0([[:space:]]|$)/ }
        primary && /mCurrentFocus=|mFocusedApp=/ { print; count++ }
        END { exit count != 2 }'
}
focus > "$out/focus-before"

z=$(pidof zygote64 | cut -d ' ' -f1)
for key in BOOTCLASSPATH DEX2OATBOOTCLASSPATH ANDROID_ART_ROOT ANDROID_I18N_ROOT ANDROID_TZDATA_ROOT; do
    value=$(tr '\000' '\n' < "/proc/$z/environ" | sed -n "s/^$key=//p")
    if [ -n "$value" ]; then export "$key=$value"; fi
done
apk=$(pm path --user 0 com.wangzan101.mika.dev | sed -n '1s/^package://p')
[ -f "$apk" ]
mkfifo "$out/commands"
(
    while :; do
        read -r level < "$backlight" || level=unreadable
        read -r actual < "$actual_backlight" || actual=unreadable
        read -r elapsed rest < /proc/uptime
        printf '%s %s %s\n' "$elapsed" "$level" "$actual"
        if [ "$level" != 0 ] || [ "$actual" != 0 ]; then : > "$out/lit"; fi
        sleep 0.05
    done
) > "$out/backlight" &
monitor=$!
logcat -v threadtime -T 1 -s MikaRouting ActivityTaskManager PowerManagerService > "$out/events" 2>&1 &
events=$!
is_off || { echo screen_changed_before_create; exit 5; }
CLASSPATH="$apk" timeout 55 app_process /system/bin --nice-name=mika-routing-check \
    com.wangzan101.mika.cap.phone.shell.IsolatedSessionMain < "$out/commands" > "$out/console" 2>&1 &
child=$!
exec 3> "$out/commands"
dark() { [ ! -e "$out/lit" ] || { echo backlight_changed_stopping; exit 6; }; }
send() { dark; kill -0 "$child"; printf '%s\n' "$1" >&3; }
for attempt in $(seq 1 100); do
    dark
    if grep -q '"ready":true' "$out/console"; then break; fi
    kill -0 "$child" || { cat "$out/console"; exit 7; }
    sleep 0.1
done
grep -q '"ready":true' "$out/console"
is_off || { echo screen_changed_after_create; exit 5; }
send '{"op":"launch","package":"com.MobileTicket"}'
for attempt in $(seq 1 40); do
    sleep 0.5
    dark
    if grep -q '"op":"launch"' "$out/console"; then break; fi
    kill -0 "$child" || exit 7
done
grep -Eq '"op":"launch".*"code":0' "$out/console" || { cat "$out/console"; exit 8; }
send '{"op":"status"}'
for attempt in $(seq 1 35); do
    sleep 0.5
    dark
    if grep -Eq '"op":"status".*"top":"com\.MobileTicket\.([^"]*\.)?MainActivity"' "$out/console"; then break; fi
    send '{"op":"status"}'
done
grep -Eq '"op":"status".*"top":"com\.MobileTicket\.([^"]*\.)?MainActivity"' "$out/console" || { cat "$out/console"; exit 9; }
send '{"op":"shot","wait_ms":1200}'
for attempt in $(seq 1 30); do
    sleep 0.1
    dark
    if grep -q '"path"' "$out/console"; then break; fi
done
grep -q '"path"' "$out/console"
cp "$shot" "$out/home.png"
dumpsys activity activities > "$out/activities-home"
send '{"op":"exit"}'
exec 3>&-
wait "$child"; child=
dark
is_off || { echo screen_changed_during_close; exit 5; }
focus > "$out/focus-after"
cmp -s "$out/focus-before" "$out/focus-after" || { echo main_focus_changed; exit 10; }
echo 'home_captured_backlight_samples_zero_focus_unchanged'
echo 'Inspect home.png, console, events, and activities-home before accepting the routing result.'
