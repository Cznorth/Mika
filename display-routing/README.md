# Mika system display routing

This is a separate LSPosed module, scoped only to `android` (`system_server`). It is
paired with Mika's root helper. Installing a new APK alone does not activate its
system hooks in an already-running system process. Do not restart Android or the
phone without the owner's deployment window.

## Enforcement

The helper talks to a root-only private transaction on the existing `activity_task`
Binder. There is no exported component, writable routing file, or new network port.
The handshake succeeds only when every required hook was installed. A live helper
Binder owns each lease; the lease records both display ID and unique display identity.
The system resolves package/user to UID itself and rejects binding if that UID has
activities outside the leased display.

- `ActivityStarter.startActivityInner`: bind launches from the leased package, UID,
  or source activity; set both display ID and task display area before placement;
  clear stale task/root selectors and refuse cross-session launches.
- `LaunchParamsController.calculate`: force the final output after the vendor's
  modifiers, including source-less launches and task reuse. This does not depend on
  the removed Android 17 `getPreferredLaunchTaskDisplayArea` helper.
- `resolveReusableTask`: ignore reusable tasks outside the session display.
- `WindowContainer.addChild/reparent`: reject off-display placement before the
  task or activity is attached. This also covers explicit task migration.
- `ActivityRecord.canTurnScreenOn` and attributed application wakes in
  `PowerManagerService.wakePowerGroupLocked`: suppress session application wakes
  of the physical power group. User power-button and gesture wakes are unaffected.

Death, changed display identity, loss of its independent group, and close-in-progress
deny further launches. Owner death retains deny bindings. A root helper can explicitly
rebind a revoked package only after the system confirms it has no activities outside
the replacement display; it does not free other packages of the old session.
Normal close first installs a system close barrier, removes tasks, verifies that no
session activities remain, then unbinds and releases the display.

The root helper may use the ColorOS Mirage registration again **only after the system
handshake succeeds**. Without the active module it refuses before creating a display.
The code has no post-escape task-moving fallback.

## Build and deployment

Commit first and use `pc-build submit`, never direct Gradle. Build the paired Mika Dev
APK (`-PdevInstall`), this module, `SessionRoutesTest`, and the existing root backend
tests. Use `/opt/android-build/mika-original-debug.keystore` when resigning for this
OnePlus 15. Verify package identities and the certificate of the currently installed
Mika APK before any update.

Deployment must hold `/run/lock/sidekick-android-install.lock`, back up the old APK,
app data, and LSPosed configuration, and use an in-place update. Never uninstall Mika.
Configure this module for `android` only. Do not install the paired Mika update unless
the module activation window is available: an inactive module makes the new helper
refuse all root virtual sessions. Do not modify LSPosed's SQLite files while its daemon
is running; use its supported manager/developer interface.

The policy tests verify routing decisions, not actual system hooks or screen power.
Device acceptance remains mandatory: wait for natural screen-off, use Mika's
`IsolatedSessionMain` root path to launch `com.MobileTicket`, observe Splash and home
`MainActivity` on the same virtual display, capture that display only, record actual
backlight at 50 ms intervals, and confirm display-0 focus stays unchanged. Stop on any
nonzero reading. Do not tap, order, or wake the phone for this check.
