# Bluetooth Transfer Unlock

[中文](README.md) | English

> **Note: the author only knows a little about Android and supplied the ideas — every change in this repository is AI-generated. Review the code before using it, because these hooks touch the system Bluetooth stack and can cause irreversible damage.**

A LSPosed module built on the **modern libxposed API (102)**: no UI, no Activity, no settings screen. Install the APK, enable the module, tick the scope, restart the target process — there is nothing to configure. Ticking `com.android.bluetooth` alone gets you features 1/2/3; `system` exists only for feature 4 (see §2).

| Feature | In one line |
| :- | :- |
| 1 | inbound transfers are no longer refused by the MIME accept-list |
| 2 | outbound shares no longer break on a missing or unknown type |
| 3 | permanently discoverable: the module asserts it as soon as Bluetooth is on — no pairing screen, no timeout downgrade |
| 4 | Bluetooth appears in **every** app's share sheet, not only for zip / image / video |

The source contains no `de.robv.android.xposed.*` at all; everything goes through `io.github.libxposed:api:102.0.0` (`compileOnly`, never packaged into the APK).

> **Risk:** feature 3 keeps the device discoverable whenever Bluetooth is on — a privacy trade-off, so switch Bluetooth off when you don't want to be found. Feature 4 is a global system_server hook and affects every share sheet on the device.

---

## 1. Building

- JDK 17 (any local install; point `JAVA_HOME` at it)
- Gradle 9.3.1 (the wrapper is checked in) + AGP 9.1.0
- compileSdk / targetSdk 36, minSdk 26 (covers Android 8.0; the practical support range starts at Android 9)

```bash
JAVA_HOME="<path to your JDK 17>" ./gradlew :app:assembleDebug :app:assembleRelease
```

The only dependencies are `io.github.libxposed:api:102.0.0` and `androidx.annotation:annotation-jvm`, both `compileOnly`, so the first build needs network access.

Output:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk` (about 26 KB; both build types keep minification off, see §7. Release currently reuses the debug `signingConfig` — replace it before distributing.)

`kotlin.stdlib.default.dependency=false` in `gradle.properties` is deliberate: a pure Java module must not ship kotlin-stdlib. `androidx.annotation` is `compileOnly` + `transitive = false` for the same reason, and is only there for `@NonNull/@Nullable`.

## 2. Enabling

1. Install the APK, then enable **Bluetooth Transfer Unlock** in LSPosed.
   The module is recognised through `META-INF/xposed/java_init.list` inside the APK (LSPosed's `ModuleUtil.getModernModuleApk()` looks at that file and nothing else), and the manifest deliberately carries **no** legacy `xposedmodule` / `xposedminversion` / `xposedscope` meta-data — the modern API requires that.
2. Tick the scope. `module.prop` sets `staticScope=true`, so `scope.list` inside the APK only pre-fills the defaults (`com.android.bluetooth` + `system`); after installation you can still tick each entry separately:
   - `com.android.bluetooth` — the whole of features 1/2/3 lives here (OPP + btservice). **If you only want those three, this one entry is enough.**
   - `system` — **this entry is the only switch for feature 4**: intent resolution runs in system_server, and the module has to be injected into that process to change the result. It also covers the handful of ROMs that keep scan-mode bookkeeping in the framework. Don't tick it if you don't want Bluetooth offered for every shared file type (you can also delete that line from `scope.list`).
   - `android` is not needed. LSPosed reads `scope.list` verbatim, line by line, for modern modules; the `android`↔`system` alias only exists for legacy `xposedscope`, so the token has to be correct. Write `android` and the module never reaches system_server: feature 4 dies silently while features 1/2/3 install normally, which looks exactly like broken hook code.
3. Restart the target process:
   - Features 1/2/3 need the Bluetooth process restarted. The easiest way is turning Bluetooth off and on again (that restarts `AdapterService`), or `adb shell am force-stop com.android.bluetooth`.
   - **Feature 4 requires a full reboot** — system_server does not reload the module when the Bluetooth process restarts. LSPosed's manager has a reboot button.

## 3. Verified hook points per Android version

The class and method names below are documented, but the code **hard-codes no signatures**: classes are looked up from a candidate list, methods are matched by *name + parameter shape*, and anything missing is skipped silently with a WARN log (see §6) instead of crashing the host process.

| Android | Source | Feature 1 (receive) | Feature 2 (send) | Feature 3 (discoverable) |
| :- | :- | :- | :- | :- |
| 9 / 10 / 11 / 12 | `packages/apps/Bluetooth` | `opp.BluetoothOppObexServerSession.onPut(javax.obex.Operation)`; `opp.Constants.mimeTypeMatches(String,String[])` plus the private `(String,String)`; `Constants.ACCEPTABLE_SHARE_INBOUND_TYPES` | the `stream != null && type != null` gate in `opp.BluetoothOppLauncherActivity.onCreate`; `opp.BluetoothOppSendFileInfo.generateFileInfo(Context,Uri,String,boolean)`; `opp.BluetoothOppManager.saveSendingFileInfo(String,…)` | `BluetoothAdapter.setScanMode(int,int)` → `btservice.AdapterService.setScanMode(int,int)` → `setDiscoverableTimeout(int)` → `AdapterProperties.setDiscoverableTimeout(int)` → `BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT`; on the same chain `AdapterProperties.setScanMode(int)` receives the **post-**`convertScanModeToHal` HAL value 2 (see §4) |
| 13 / 14 / 15 / 16 | `packages/modules/Bluetooth/android/app` (the Bluetooth APK now ships in the mainline APEX `/apex/com.android.btservices`; package names unchanged) | **same logic**, only `onPut(com.android.obex.Operation)` and `isWhitelisted` renamed to `isAcceptlisted` | same as above | `BluetoothAdapter.setScanMode(int)` + `setDiscoverableTimeout(Duration)` → binder `AdapterService$AdapterServiceBinder` → `AdapterProperties.setDiscoverableTimeout(int)` |

The rejection branches behind feature 1, as they read in the Android 14 source (checked one by one):

```java
if (length == 0)            return OBEX_HTTP_LENGTH_REQUIRED;   // kept
if (name == null||isEmpty)  return OBEX_HTTP_BAD_REQUEST;       // kept
if (dotIndex < 0 && mimeType == null) return OBEX_HTTP_BAD_REQUEST; // kept
type = map.getMimeTypeFromExtension(extension);
if (type == null && mimeType == null) return OBEX_HTTP_UNSUPPORTED_TYPE; // opened
if (mimeType == null || (!isAcceptlisted && !Constants.mimeTypeMatches(mimeType, ACCEPTABLE_SHARE_INBOUND_TYPES)))
                            return OBEX_HTTP_UNSUPPORTED_TYPE;  // opened
```

The corresponding hooks (`setId` prefix `btu:`; reinstalling replaces rather than stacks):

| hook id | where | behaviour |
| :- | :- | :- |
| `receive.acceptList` | every `static boolean` type-matching method in `Constants` shaped like `(String, String[]|String|List)` | returns `true` immediately; the accept-list is never consulted |
| `receive.onPut` | `BluetoothOppObexServerSession.onPut` | opens a "relaxation window" scoped to that thread and calls `deoptimize()` on the method to fight inlining |
| `receive.extension` | `android.webkit.MimeTypeMap.getMimeTypeFromExtension` | returns `application/octet-stream` only inside the relaxation window and only when the extension resolves to nothing; outside the window the original answer is returned |
| `send.launcher` | `BluetoothOppLauncherActivity.onCreate/onNewIntent` | for `ACTION_SEND(_MULTIPLE)` with an `EXTRA_STREAM` whose type is missing or `*/*`, fills in a type via `ContentResolver.getType` → file extension → `application/octet-stream` |
| `send.generateFileInfo` / `send.saveFileInfo` | the OPP methods of those names | substitutes only parameters **declared `String` that are actually `null`** (never touches any other argument) |
| `scan.timeout` | every overload of `set*DiscoverableTimeout*` | `Integer→0` / `Long→0L` / `Duration→Duration.ZERO` |
| `scan.mode` | every overload of `setScanMode*` | zeroes the trailing duration argument in discoverable mode (framework 23 or HAL 2) and records the encoding **per layer**: `BluetoothAdapter` / `AdapterService` learn the framework value, `AdapterProperties` only ever learns a value it actually received |
| `scan.propertyChanged` | `AdapterProperties.adapterPropertyChangedCallback` | re-asserts with timeout 0 after the native stack demotes the mode back to connectable |
| `scan.capture` | `AdapterService.onCreate` / `onAdapterEnabled` / `processProfileServiceStateChanged` / `setState` | captures the `AdapterService` instance before anybody requests a scan mode (and pulls its `AdapterProperties` field along with it); the assert ladder is public API → those two instances, see §4 |
| `share.resolver` | system_server `queryIntentActivities(Intent, String, …)` (Android 13+: `IPackageManagerBase`; Android 9-12: `PackageManagerService`) | appends a real Bluetooth entry to the `ACTION_SEND(_MULTIPLE)` result (see §5) |

## 4. Feature 3: discoverable without the pairing screen

Stock Android requests `SCAN_MODE_CONNECTABLE_DISCOVERABLE` only for the tens of seconds the pairing screen is open; once the screen closes and the timeout fires, the mode drops back to connectable, so no other phone can see the device. Clamping the timeout to 0 is not enough: it only lengthens a discoverable window that somebody has already requested. On a phone nobody ever opened the pairing page for, the scan mode is connectable from boot onwards, so there is nothing to lengthen.

Discoverable is therefore a **posture the module maintains itself**:

1. `scan.capture` grabs the `AdapterService` instance as soon as the Bluetooth process starts (`AdapterService#onCreate`) and pulls out its `AdapterProperties` field by reflection;
2. every enable callback, property change and 5 s watchdog tick runs a `verify()` that reads the live scan mode and re-asserts it when it is not discoverable;
3. the re-assert walks a ladder by priority: `BluetoothAdapter#setScanMode(int)` / `(int, int)` — the public API, the same road the pairing screen takes, so the ROM converts the encoding itself → `AdapterService#setScanMode(int, int)` (Android 9-12) / `setScanMode(int, String)` (a 13+ variant on some ROMs) → `AdapterProperties#setScanMode(int)`, where only a value this ROM has actually fed that method is ever used; `setDiscoverableTimeout` is written as 0 along the way. All three attempts go through `invokeOriginal`, otherwise the module's own timeout clamp would zero the duration it just chose, and `learn()` would mistake its own request for the ROM teaching it the encoding.

**The encoding trap (Android 9-12 must avoid it)**: in those versions `AdapterService#setScanMode(int, int)` runs `setDiscoverableTimeout(duration)` → `convertScanModeToHal(mode)` → `AdapterProperties#setScanMode(hal)`, which means the `AdapterProperties` layer expects the **HAL value 2**, not the framework value 23. Write 23 there and the Java layer stays silent while the stack drops the request, leaving the live scan mode at 21 forever. The module therefore never guesses: each layer remembers the discoverable value *this* ROM actually fed it, and the `AdapterProperties` path is only taken after that ROM has fed it a value itself.

Two things still win over the pin:

- **Turning Bluetooth off**: the module only re-asserts while the live state is `STATE_ON` and never calls `enable()`; switching Bluetooth off and on re-applies the posture from the enable callback.
- **An explicit scan-mode request from another UID**: the pin is released, and the log says it will be re-applied the next time Bluetooth is turned on.

## 5. Feature 4: why Bluetooth is missing from the share sheet, and what changes

The share sheet's candidates come from the sharing app itself calling `PackageManager.queryIntentActivities(ACTION_SEND, type)`, and whether Bluetooth matches is decided by the system Bluetooth APK's manifest. `BluetoothOppLauncherActivity`'s filter only lists:

```
image/*  video/*  audio/*  text/x-vcard  text/x-vcalendar  text/calendar
text/plain  text/html  text/xml  application/zip  application/vnd.ms-excel
application/msword  application/vnd.ms-powerpoint  application/pdf
application/vnd.openxmlformats-officedocument.*  application/x-hwp
```

`application/vnd.android.package-archive` is not among them, so sharing an APK never offers Bluetooth. File managers that *do* show it simply declare the APK as `application/zip` (or name the component explicitly): they bypass the filter, and we cannot rewrite somebody else's manifest.

What we can change is **the resolution result itself**: the query is issued by the app but executed in system_server, so appending one Bluetooth entry there covers every app at once. Key points:

- **Never hand-roll a `ResolveInfo`**: inside the hook, call the same method again with the type forced to `application/zip` (through API 102's `getInvoker(method).setType(ORIGIN)`, which bypasses this hook and cannot recurse) and reuse the real Bluetooth entry from that result — label, icon, `filter` and match flags are all genuine, so long-pressing "set as default" will not NPE.
- **Poll candidate classes for the hook point**: `IPackageManagerBase` → `PackageManagerService` → `PackageManagerInternalBase` → `PackageManagerServiceEx`, installing on the first one that has the method. On Android 13+ `queryIntentActivities(Intent, String, long, int)` is declared on `IPackageManagerBase` and `PackageManagerService` no longer has it (hooking only the latter installs 0 hooks); on Android 9-12 it is on `PackageManagerService` with the signature `(Intent, String, int, int)`. Matching is narrowed to "method name is exactly `queryintentactivities` and the second parameter is `String`" to exclude `PackageManagerService$PackageManagerInternalImpl`'s `(Intent, int, int, int)` (nested classes are scanned too) and the `…AsUser` / `…Internal` variants.
- Results are cached per user ID for 10 s, so opening the sheet does not re-run resolution every time.
- Nothing is added when the list already contains Bluetooth (that is the native path for `application/zip` shares).
- **No injection on failure**: while Bluetooth is off the activity is `android:enabled="false"`, the probe finds nothing, and nothing is added; any exception returns the list the caller already has.
- The click is an explicit component launch, so the filter is never re-checked and the transfer falls into feature 2's send path.

The cost: this is a global hook affecting every share sheet on the device, which is why its exception mode is `PROTECTIVE` and it only acts on `ACTION_SEND` / `ACTION_SEND_MULTIPLE`.

## 6. Diagnostics

All logging uses the tag `BTUnlock`:

```bash
adb logcat -s BTUnlock
# verbose logging must be set before the target process starts
adb shell setprop log.tag.BTUnlock VERBOSE
```

Two lines tell you whether the module got in and how many hooks each feature installed:

- `[com.android.bluetooth] hooks installed: receive=N, send=N, discoverable=N`
- `[system_server] hooks installed: discoverable-fallback=N, share-sheet=M`

Decision path:

- **Bluetooth missing from the share sheet**: if the log does not even contain `[system_server] hooks installed`, the module never reached system_server — the scope is missing `system` (tick it, then reboot the device). If that line is there but `share-sheet=0` with `no hookable queryIntentActivities …`, this ROM moved the resolver method, so extend the candidate list using the class and method names the WARN prints.
- **Discoverable not taking effect**: `asserted mode … (found 21, watchdog)` once every 5 s without end means the assert goes out but the protocol stack ignores it — almost always a framework value handed to a HAL-only setter. Normally this line appears once or twice per boot, after which the watchdog reads discoverable and stays quiet.
- A zero count for any feature logs its own WARN plus the method names found in the candidate classes, so a renamed ROM method is a documentation gap to fix, not a crash in the injected process.
- Feature 4 logs only in system_server, where the `logcat` ring buffer is small and boot-time lines are evicted quickly; on a rooted device the framework's own on-disk log is steadier: `grep btunlock /data/adb/lspd/log/*modules*.log`.

## 7. Deliberately kept, deliberately not done

- **No other validation is broken**: zero length, an empty file name, or neither an extension nor a TYPE header is still refused exactly as before — there is no name or type to land on.
- **Security fixes are untouched**: `isForbiddenContent` in `BluetoothOppSendFileInfo` (the CVE-2022-35310991 family) and the "external storage `file://` only" rule stay as they are — those are authorisation problems, not typing problems, and relaxing them would let any app read files it was never granted.
- **`FileUriExposedException` is not handled**: on Android 9+ that is the platform's own restriction; the hook neither drops a `file://` URI nor works around the platform on your behalf.
- **The user turning off Bluetooth always wins**: feature 3 only re-asserts discoverable while the live state is `STATE_ON` and never calls `enable()`; an explicit `SCAN_MODE_NONE` / `SCAN_MODE_CONNECTABLE` request from another UID releases the pin until Bluetooth is switched off and on once.
- **Hot reload is refused**: `onHotReloading()` returns `false` and logs. A modern-API hot reload does not replay the package lifecycle callbacks, and the default implementation unhooks every existing hook, which would leave a silently unhooked process behind.
- **No minification**: the entry class name is referenced by `java_init.list` and target classes are found by name, so `minifyEnabled false`. `proguard-rules.pro` only carries the official libxposed rules for whoever enables R8 later.

## 8. Known limitations

1. When the sharing app supplies no type at all, the type is filled in only inside the Bluetooth process; the Bluetooth row in the sheet comes from feature 4, and the fill-in logic runs after you tap it.
2. Feature 4 shows no Bluetooth row while Bluetooth is off — the activity itself is disabled. That is by design, not a bug.
3. Feature 4 assumes the sheet asks the system for resolution. On AOSP-derived resolvers (`android/com.android.internal.app.ResolverActivity`, `com.android.intentresolver`) it does; a custom sheet with its own hard-coded list, which never queries the resolver, will still miss it.
4. "Permanently" in feature 3 depends on the Bluetooth process being alive: up to 5 s of gap while it restarts, and discoverable is only re-asserted at the enable callback, a property change, or the watchdog. If a ROM renames all three setter layers, the log prints `discoverable: nothing reachable to set the mode with …` and only the timeout clamp remains — i.e. you are back to visiting the pairing screen once.
5. No UI and no notifications: the module package has a single `<application>` node, no icon and no launcher entry.
6. The `system` token was verified on LSPosed 2.1.1. If another framework records system_server under a different name, the symptom is a missing `[system_server] hooks installed` line and feature 4 failing silently while 1/2/3 keep working — follow the §6 decision path before suspecting the hook code.
7. Real-device testing covers one Android 10 and one Android 16 phone; Android 11-15 relies on the same shape matching derived from the AOSP source and is not individually verified.

## 9. Layout

```
app/src/main/resources/META-INF/xposed/
  java_init.list      com.btunlock.ModuleEntry
  module.prop         minApiVersion=102 / targetApiVersion=102 / staticScope=true
  scope.list          com.android.bluetooth / system
app/src/main/java/com/btunlock/
  ModuleEntry.java            entry point: lifecycle callbacks + per-package install + hot-reload refusal
  core/Hooks.java             candidate-class lookup, method shape matching, hook installation (PROTECTIVE + setId), deoptimize, invokeOriginal
  hooks/OppReceiveHooks.java  feature 1
  hooks/OppSendHooks.java     feature 2
  hooks/ScanModeHooks.java    feature 3 (assert ladder public API → AdapterService → AdapterProperties + instance capture + watchdog)
  hooks/ShareSheetHooks.java  feature 4 (intent-resolution injection in system_server)
```

`ModuleEntry.java` is only lifecycle, per-package installation and hot-reload refusal; all lookup and matching lives in `core/Hooks.java`, and each feature is its own file, so a method missing on some ROM turns one table cell into a WARN instead of taking the module down.
