# Root virtual-display keyboard prototype

This is an offline prototype. It is **disabled by default** and is not called by
ShellService, phone tools, task dispatch, a receiver, or an exported component.
No APK built here should be installed as part of this work.

The candidate follows scrcpy's Android 15+ input-port/display-unique-ID
association mechanism (Apache-2.0, upstream commit
`a60891aea193d92e7e5c3942700eca63f9d19a5f`). See
[UhidManager](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/control/UhidManager.java).
The keyboard descriptor and Linux packed event encoder are local implementations
of the Linux UHID protocol; they do not start scrcpy or depend on its host ADB connection.

`RootIsolatedKeyboardProbe.create(context, displayId, uniqueId, focusIsolated)`
returns an inactive session. Even `start()` performs no device operations unless
the caller explicitly passes `enabled = true`. It requires Android 15+, root,
a non-primary display ID, a display unique ID, and confirmation that Mika created
the display with `STEAL_TOP_FOCUS_DISABLED`. This confirmation is a caller
precondition, not something this prototype can independently prove.

The adapter associates a unique input port **before** creating `/dev/uhid`, waits
up to 1.5 seconds for one device with its unique name, checks its associated
display ID and the display's unique ID, and rechecks before every key report.
Missing APIs, a wrong route, a removed/reused display, or input failure closes
the keyboard and removes the association. It never sends fallback input to the
primary display. The descriptor has no output reports and the fd is nonblocking.
A short UHID write fails rather than replaying an ambiguous event.

Use `use { ... }` / `finally { close() }` for future harnesses. Close releases held
keys by destroying the virtual keyboard before removing its mapping. Failed
cleanup is exposed or attached to `failure` and may be retried. Production task
cancellation is not integrated yet; that integration must preserve this lifetime.
A killed helper loses its UHID fd automatically, but this prototype has no
restart-time scavenger for a stale system association. Its unique per-session
port prevents reuse; process-death cleanup still needs a device test before
production integration.

`stroke()` accepts HID keyboard usages, **not arbitrary Unicode text**. Chinese
composition still needs the phone's IME. This code does not switch IME, read or
write clipboard, set window focus, change display flags, unlock the phone,
modify power/system policy, or enable accessibility/wireless debugging.

Routing checks cannot make checking and kernel input injection atomic. A ROM may
also report a mapping while routing incorrectly. In particular, scrcpy
[issue #6719](https://github.com/Genymobile/scrcpy/issues/6719) reports Android 16
input reaching the main screen. Unit tests and a successful build therefore do
not establish physical-device isolation or working locked-screen Chinese.

## Validation before any production integration

1. Run `:core:test` (including `IsolatedKeyboardProbeTest`, `HelperRestoreTest`,
   and `BackgroundGuardTest`) and `:app:assembleDebug` on the server.
2. When the phone connection returns, read API version, UHID availability,
   display identity and current association state. Do not start the prototype
   automatically on reconnection.
3. In an explicitly arranged idle-phone test, retain Mika's existing focus
   isolation flags. Verify virtual-display input while watching that the main
   display's focus and content stay unchanged. Stop if input reaches display 0.
4. Verify screen lock/unlock, Chinese IME composition, display destruction,
   cancellation, fd cleanup and association removal. No global IME change or
   focus change is authorized by this prototype.
5. Only after that evidence should a separate change wire the prototype to phone
   task tools, preserving a user-handoff path when isolated input is unavailable.

Build APKs are server debug artifacts. The server debug signing key has not been
verified against the phone's existing Mika installation, so they are not an
installation or release delivery.
