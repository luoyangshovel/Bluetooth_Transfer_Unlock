# Bluetooth Transfer Unlock

[中文](README.md) | English

> [!WARNING]
> **Note: the author only knows a little about Android and supplied the ideas — every change in this repository is AI-generated. Review the code before using it, because these hooks touch the system Bluetooth stack and can cause irreversible damage.**

A LSPosed module on the modern libxposed API (102): no UI, no configuration screen. Enable the module, tick the scope, restart the target process. It lifts four Bluetooth OPP limits:

| Feature | In one line |
| :- | :- |
| 1 | inbound transfers are no longer refused by the MIME accept-list |
| 2 | outbound shares no longer break on a missing or unknown type |
| 3 | permanently discoverable: asserted as soon as Bluetooth is on — no pairing screen, no timeout downgrade |
| 4 | Bluetooth appears in **every** app's share sheet, not only for zip / image / video |

> **Risk:** feature 3 leaves the device discoverable whenever Bluetooth is on (switch it off when you don't want to be found); feature 4 is a global system_server hook that affects every share sheet on the device.

## Building

JDK 17 + Gradle 9.3.1 (wrapper included) + AGP 9.1.0, compile/target SDK 36, minSdk 26. The only dependencies are `io.github.libxposed:api:102.0.0` and `androidx.annotation:annotation-jvm`, both `compileOnly`, so the whole module is about 26 KB.

```bash
JAVA_HOME="<path to your JDK 17>" ./gradlew :app:assembleDebug :app:assembleRelease
```

## Enabling

`staticScope=true` in `module.prop`, so `META-INF/xposed/scope.list` only pre-fills the defaults; you can still tick each entry separately after installing:

- `com.android.bluetooth` — all of features 1/2/3. **That one entry is enough if you don't want feature 4.**
- `system` — the only switch for feature 4 (intent resolution runs in system_server). Leave it unticked if you don't need it.
- Do not write `android`. `scope.list` is read verbatim, line by line, for modern modules; the `android`↔`system` alias exists only for legacy `xposedscope`. The wrong token kills feature 4 silently while everything else installs normally, which looks like broken hook code.

Restart: toggling Bluetooth off and on (or `adb shell am force-stop com.android.bluetooth`) reloads features 1/2/3; **feature 4 needs a full reboot**, because system_server does not reload the module when the Bluetooth process restarts.

Recognition goes through `META-INF/xposed/java_init.list`; the manifest carries no legacy `xposedmodule/xposedminversion/xposedscope`, and the source contains no `de.robv.android.xposed.*`.

## What gets hooked

Classes are looked up from a candidate list and methods matched by *name + parameter shape*; anything missing is skipped silently with a WARN rather than crashing the host process. Verified targets, Android 9-16:

- **Receive**: every type-matching method in `opp.Constants` shaped like `(String, String[]|String|List)` returns `true`; `BluetoothOppObexServerSession.onPut` opens a per-thread relaxation window in which an unresolvable extension becomes `application/octet-stream`; `deoptimize()` counters inlining.
- **Send**: `BluetoothOppLauncherActivity.onCreate/onNewIntent` fills in a type when `EXTRA_STREAM` is present but the type is missing or `*/*` (`ContentResolver.getType` → extension → `application/octet-stream`); `generateFileInfo` / `saveSendingFileInfo` substitute only parameters **declared `String` that are actually `null`**.
- **Discoverable**: every `set*DiscoverableTimeout*` overload is clamped to 0; `setScanMode*` zeroes the trailing duration and records the encoding per layer; `AdapterService#onCreate` captures the service instance (and its `AdapterProperties`), after which each enable callback, property change and 5 s watchdog reads the live scan mode and re-asserts through a ladder — public `BluetoothAdapter` → `AdapterService` → `AdapterProperties` — all via `invokeOriginal`.
  **The Android 9-12 trap**: `AdapterProperties#setScanMode(int)` receives the **post-**`convertScanModeToHal` HAL value 2, not the framework value 23. Write 23 and the request vanishes while the live mode stays at 21. The module never guesses: each layer only uses a value that method actually received.
- **Share sheet**: the sheet's candidates come from the sharing app calling `queryIntentActivities(ACTION_SEND, type)`, and the system Bluetooth APK's manifest does not list `application/vnd.android.package-archive` (file managers that show Bluetooth declare the APK as `application/zip`). A manifest can't be rewritten, so the resolution result is: one Bluetooth entry is appended to the `ACTION_SEND(_MULTIPLE)` list inside system_server. The entry is not a hand-rolled `ResolveInfo` — the same method is called again with the type forced to `application/zip` through `getInvoker(...).setType(ORIGIN)` and the real object from that result is reused (genuine label, icon, `filter`, match flags). Candidate classes are polled (`IPackageManagerBase` → `PackageManagerService` → variants, since Android 13+ declares it on the former and 9-12 on the latter), results are cached per user ID for 10 s, nothing is added when Bluetooth is already listed, and nothing is added on failure or while Bluetooth is off (the activity is disabled).

## Diagnostics

```bash
adb logcat -s BTUnlock
adb shell setprop log.tag.BTUnlock VERBOSE   # set this before the target process starts
```

- `[com.android.bluetooth] hooks installed: receive=N, send=N, discoverable=N` and `[system_server] hooks installed: discoverable-fallback=N, share-sheet=M` — these two lines say what got installed.
- Bluetooth missing from the sheet: no `[system_server]` line at all → the scope lacks `system` (tick it, reboot). That line present but `share-sheet=0` with `no hookable queryIntentActivities …` → this ROM moved the method; extend the candidates from the names in the WARN.
- Not discoverable: `asserted mode … (found 21, watchdog)` every 5 s without end means the assert goes out but the stack ignores it — almost always a framework value handed to a HAL-only setter. Normally it appears once or twice per boot.
- system_server logs are evicted from `logcat` quickly; on a rooted device use `grep bluetoothtransferunlock /data/adb/lspd/log/*modules*.log`.

## Known limitations

1. When the sharing app supplies no type, the fill-in happens only inside the Bluetooth process; the row in the sheet comes from feature 4 and runs after you tap it.
2. No Bluetooth row while Bluetooth is off (the activity is disabled) — by design. A custom sheet with its own hard-coded list that never queries the resolver will also miss it.
3. "Permanently" depends on the Bluetooth process being alive: up to 5 s of gap on restart. If a ROM renames all three setter layers, the log prints `discoverable: nothing reachable to set the mode with …` and only the timeout clamp remains, i.e. back to visiting the pairing screen once.
4. No UI and no notifications; the `system` token was verified on LSPosed 2.1.1 only.
5. Real-device testing covers one Android 10 and one Android 16 phone; Android 11-15 relies on the same shape matching derived from AOSP source and is not individually verified.

## Layout

```
app/src/main/resources/META-INF/xposed/   java_init.list / module.prop / scope.list
app/src/main/java/app/bluetoothtransferunlock/
  ModuleEntry.java            entry point: lifecycle + per-package install + hot-reload refusal
  core/Hooks.java             candidate lookup, method shape matching, hook install, deoptimize, invokeOriginal
  hooks/{OppReceive,OppSend,ScanMode,ShareSheet}Hooks.java   features 1-4, independent of each other
```
