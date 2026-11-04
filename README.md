# Bluetooth Transfer Unlock

> **注意，本人只对Android系统有少量了解，项目中本人只提供想法，所有修改均为AIGC，使用前务必审查项目代码防止出现不可逆破坏。**
>
> **Note:** the author only knows a little about Android and supplied the ideas — every change in this repository is AI-generated. Review the code before you use it; these hooks touch the system Bluetooth stack.

中文在前，英文跟在每一节之后。**Chinese first, English follows each section.**

| 功能 | 一句话 | Feature | In one line |
| :- | :- | :- | :- |
| 一 | 接收端不再按 MIME 白名单拒绝任何文件类型 | 1 | inbound transfers are no longer rejected by the MIME accept-list |
| 二 | 发送端不再因为没有类型 / 类型不认识而中断分享 | 2 | outbound shares no longer break on a missing / unknown type |
| 三 | 永久可被搜索：蓝牙一打开就自己断言 discoverable，不用进配对页面，也不会被超时降级 | 3 | permanently discoverable: the module asserts it as soon as Bluetooth is on — no pairing screen, no timeout downgrade |
| 四 | 蓝牙出现在**任何** App 的分享面板里，不再只有 zip/图片/视频能看到 | 4 | Bluetooth shows up in **every** app's share sheet, not only for zip/image/video |

> **风险提示**：功能三让设备在蓝牙打开期间随时可被扫描到，这是一个隐私权衡；不想被别人搜到就关掉蓝牙。功能四是注入 system_server 的全局 hook，会影响整机所有分享面板。
>
> **Risk:** feature 3 keeps the device discoverable whenever Bluetooth is on — a privacy trade-off, so switch Bluetooth off when you don't want to be found. Feature 4 is a global system_server hook that affects every share sheet on the device.

这是一个 LSPosed 模块，用现代 libxposed API（102）实现：**无 UI、无 Activity、无配置界面**，启用模块、勾选作用域、重启目标进程即可，没有需要点的开关。
作用域只勾 `com.android.bluetooth` 就能拿到功能一/二/三；`system` 只为功能四存在（见 §2）。
工程里没有 `de.robv.android.xposed.*`，全部走 `io.github.libxposed:api:102.0.0`（`compileOnly`，不打进 APK）。

**EN** — A LSPosed module built on the **modern libxposed API (102)**: no UI, no Activity, no settings screen. Install the APK, enable the module, tick the scope, restart the target process — there is nothing to configure. The source contains no `de.robv.android.xposed.*` at all; everything goes through `io.github.libxposed:api:102.0.0` (`compileOnly`, never packaged).

---

## 1. 构建 / Building

- JDK 17（`JAVA_HOME` 指向本机任意一个 JDK 17）
- Gradle 9.3.1（wrapper 随仓库带上）+ AGP 9.1.0
- compileSdk / targetSdk 36，minSdk 26（覆盖 Android 8.0，实际支持面 Android 9+）

```bash
JAVA_HOME="<你的 JDK 17 路径>" ./gradlew :app:assembleDebug :app:assembleRelease
```

依赖只有 `io.github.libxposed:api:102.0.0` 和 `androidx.annotation:annotation-jvm`（两者都是 `compileOnly`），
首次构建需要联网拉一次。

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`（约 26 KB；两个构建类型都关闭了混淆，见 §7。release 目前复用 debug 签名，公开分发请自行替换 `signingConfig`）

`gradle.properties` 里的 `kotlin.stdlib.default.dependency=false` 是刻意的：纯 Java 模块不该把 kotlin-stdlib 打进 APK；
`androidx.annotation` 也只作为 `compileOnly` + `transitive = false` 存在，只为 `@NonNull/@Nullable`。

**EN** — JDK 17 (any local install, point `JAVA_HOME` at it), Gradle 9.3.1 via the checked-in wrapper, AGP 9.1.0, compile/target SDK 36, minSdk 26. The only dependencies are `io.github.libxposed:api:102.0.0` and `androidx.annotation:annotation-jvm`, both `compileOnly`, so the first build needs network access. Output is roughly 26 KB for release; both build types keep minification off (see §7), and release currently reuses the debug `signingConfig` — replace it before distributing. `kotlin.stdlib.default.dependency=false` in `gradle.properties` and the non-transitive annotation dependency are deliberate: a pure Java module must not ship kotlin-stdlib.

## 2. 启用 / Enabling

1. 安装 APK → LSPosed 里启用 **Bluetooth Transfer Unlock**。
   模块靠 APK 内的 `META-INF/xposed/java_init.list` 被识别（LSPosed `ModuleUtil.getModernModuleApk()` 只看这个文件），
   manifest 里**没有** legacy 的 `xposedmodule/xposedminversion/xposedscope` meta-data，这是现代 API 的要求。
2. 勾选作用域。`module.prop` 里 `staticScope=true`，APK 内的 `scope.list` 只负责预填默认值（`com.android.bluetooth` + `system`），装好后在 LSPosed 里仍可逐条勾选：
   - `com.android.bluetooth` —— 功能一/二/三的全部实现都在这里（OPP + btservice）。**只要这三项的话，勾这一条就够了。**
   - `system` —— **这一条是功能四的唯一开关**：意图解析跑在 system_server，只有把模块注入该进程才改得了解析结果；顺带兜住少数把 scan mode 记账放在 framework 的 ROM。不需要"任何 App 的分享面板都出现蓝牙"，就别勾它（`scope.list` 里这行也可以直接删掉）。
   - 不需要 `android`：LSPosed 对现代模块按行**原样**读取 `scope.list`，`android`↔`system` 的别名转换只存在于 legacy `xposedscope`，所以 token 必须写对。写成 `android` 的后果是模块进不了 system_server，功能四静默失效，而功能一/二/三照常安装——很容易被误判成 hook 代码有问题。
3. 重启目标进程：
   - 功能一/二/三：蓝牙进程要重启。最省事是关掉蓝牙再打开（`AdapterService` 进程随之重启），或 `adb shell am force-stop com.android.bluetooth`。
   - **功能四必须重启整机**（system_server 不会因为蓝牙进程重启而重新加载模块）。LSPosed 管理器里可以直接"重启系统"。

**EN** —
1. Install the APK, enable the module in LSPosed. It is recognised through `META-INF/xposed/java_init.list` (LSPosed's `ModuleUtil.getModernModuleApk()` only looks at that file); the manifest deliberately carries **no** legacy `xposedmodule` / `xposedminversion` / `xposedscope` meta-data, as the modern API requires.
2. Scope. `staticScope=true` means `scope.list` only pre-fills the defaults (`com.android.bluetooth` + `system`); you can still tick each entry separately. `com.android.bluetooth` holds the whole of features 1/2/3 (OPP + btservice) — **that one entry is enough if you don't want feature 4**. `system` is the **only** switch for feature 4: intent resolution runs in system_server, and it also covers the handful of ROMs that keep scan-mode bookkeeping in the framework. Do not use `android` — LSPosed reads `scope.list` verbatim, line by line, and the `android`↔`system` alias exists only for legacy `xposedscope`. A wrong token means the module never reaches system_server, so feature 4 dies silently while features 1/2/3 install normally, which looks like broken hook code.
3. Restart the target: features 1/2/3 need the Bluetooth process restarted (toggling Bluetooth off and on is the easiest way, or `adb shell am force-stop com.android.bluetooth`); **feature 4 requires a full reboot**, because system_server does not reload the module when the Bluetooth process restarts. LSPosed's manager has a "reboot" button.

## 3. 各 Android 版本已核实的 Hook 点 / Verified hook points per Android version

类名/方法名写在表里，但代码里**不硬编码签名**：类按候选名查，方法按"名字 + 参数形状"匹配，找不到就静默跳过并打 WARN 日志（见 §6）。表体是框架符号，中文说明由本节末尾的英文段落统一覆盖。

| Android | 源码位置 / source | 功能一（接收）/ receive | 功能二（发送）/ send | 功能三（可发现）/ discoverable |
| :- | :- | :- | :- | :- |
| 9 / 10 / 11 / 12 | `packages/apps/Bluetooth` | `opp.BluetoothOppObexServerSession.onPut(javax.obex.Operation)`；`opp.Constants.mimeTypeMatches(String,String[])` + 私有 `(String,String)`；`Constants.ACCEPTABLE_SHARE_INBOUND_TYPES` | `opp.BluetoothOppLauncherActivity.onCreate` 的 `stream != null && type != null` 门；`opp.BluetoothOppSendFileInfo.generateFileInfo(Context,Uri,String,boolean)`；`opp.BluetoothOppManager.saveSendingFileInfo(String,…)` | `BluetoothAdapter.setScanMode(int,int)` → `btservice.AdapterService.setScanMode(int,int)` → `setDiscoverableTimeout(int)` → `AdapterProperties.setDiscoverableTimeout(int)` → `BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT`；同一条链上 `AdapterProperties.setScanMode(int)` 收的是 `convertScanModeToHal` **之后**的 HAL 值 2（见 §4） |
| 13 / 14 / 15 / 16 | `packages/modules/Bluetooth/android/app`（蓝牙 APK 已进主线 APEX `/apex/com.android.btservices`，包名不变） | **同一套逻辑 / same logic**，只是 `onPut(com.android.obex.Operation)`、`isWhitelisted` 改名 `isAcceptlisted` | 同上 / same as above | `BluetoothAdapter.setScanMode(int)` + `setDiscoverableTimeout(Duration)` → binder `AdapterService$AdapterServiceBinder` → `AdapterProperties.setDiscoverableTimeout(int)` |

功能一在 Android 14 源码里对应的拒绝分支（逐条核对）：

```java
if (length == 0)            return OBEX_HTTP_LENGTH_REQUIRED;   // 保留 / kept
if (name == null||isEmpty)  return OBEX_HTTP_BAD_REQUEST;       // 保留 / kept
if (dotIndex < 0 && mimeType == null) return OBEX_HTTP_BAD_REQUEST; // 保留 / kept
type = map.getMimeTypeFromExtension(extension);
if (type == null && mimeType == null) return OBEX_HTTP_UNSUPPORTED_TYPE; // 打开 / opened
if (mimeType == null || (!isAcceptlisted && !Constants.mimeTypeMatches(mimeType, ACCEPTABLE_SHARE_INBOUND_TYPES)))
                            return OBEX_HTTP_UNSUPPORTED_TYPE;  // 打开 / opened
```

对应 hook（`setId` 前缀 `btu:`，重复安装是替换而不是叠加）：

| hook id | 位置 / where | 动作 / behaviour |
| :- | :- | :- |
| `receive.acceptList` | `Constants` 里所有 `static boolean` 且形如 `(String, String[]|String|List)` 的类型匹配方法 | 直接返回 `true`，不再查白名单 / always `true`, the accept-list is never consulted |
| `receive.onPut` | `BluetoothOppObexServerSession.onPut` | 打开一个线程内的"放宽窗口"，并 `deoptimize()` 该方法对抗内联 |
| `receive.extension` | `android.webkit.MimeTypeMap.getMimeTypeFromExtension` | 仅在放宽窗口内、且扩展名查不到时返回 `application/octet-stream`；窗口外原样返回 |
| `send.launcher` | `BluetoothOppLauncherActivity.onCreate/onNewIntent` | `ACTION_SEND(_MULTIPLE)` 且 `EXTRA_STREAM` 存在而 type 缺失/`*/*` 时，按 `ContentResolver.getType` → 扩展名 → `application/octet-stream` 补一个类型 |
| `send.generateFileInfo` / `send.saveFileInfo` | 同名 OPP 方法 | 只把**声明为 String 且实际为 null** 的参数补成 `application/octet-stream`（不会误替换别的参数） |
| `scan.timeout` | `set*DiscoverableTimeout*` 全部重载 | `Integer→0` / `Long→0L` / `Duration→Duration.ZERO` |
| `scan.mode` | `setScanMode*` 全部重载 | 可发现模式（框架值 23 或 HAL 值 2）时把末尾的时长参数清零，并**按调用所在层**记录编码：`BluetoothAdapter` / `AdapterService` 记框架值，`AdapterProperties` 只记它真正收到过的值 |
| `scan.propertyChanged` | `AdapterProperties.adapterPropertyChangedCallback` | 原生栈把模式降回 connectable 后，重新以 timeout=0 断言一次 |
| `scan.capture` | `AdapterService.onCreate` / `onAdapterEnabled` / `processProfileServiceStateChanged` / `setState` | 在任何人请求 scan mode 之前拿到 `AdapterService`（并顺带取出它的 `AdapterProperties` 字段）；断言阶梯 = 公开 API → 这两个实例，见 §4 |
| `share.resolver` | system_server `queryIntentActivities(Intent, String, …)`（Android 13+ 在 `IPackageManagerBase`，Android 9-12 在 `PackageManagerService`） | 给 `ACTION_SEND(_MULTIPLE)` 的结果追加真实蓝牙条目（见 §5） |

**EN** — The tables above are framework symbols, so they are not translated; the reading is: class names are looked up from a candidate list, methods are matched by *name + parameter shape*, and anything missing is skipped silently with a WARN (see §6) rather than crashing the host process. Feature 1 replaces the OPP type-matching predicate with `true` and opens a per-thread relaxation window around `onPut`, inside which an unknown file extension maps to `application/octet-stream`. Feature 2 fills in a MIME type at the OPP launcher and in `generateFileInfo` / `saveSendingFileInfo`, substituting only parameters declared `String` that are actually `null`. Feature 3 clamps every discoverable-timeout overload to 0 and hooks `setScanMode` per layer. Feature 4 appends a real Bluetooth entry to the `ACTION_SEND` resolution result inside system_server.

## 4. 功能三：永久可发现，不用进配对页面 / Feature 3: discoverable without the pairing screen

原生 Android 只在配对页面打开的那几十秒里请求 `SCAN_MODE_CONNECTABLE_DISCOVERABLE`，页面一关、超时一到就降回 connectable，别的手机自然搜不到。只把超时钳成 0 是不够的——那只能**延长别人已经请求过**的可发现窗口：机器开机后没人进过配对页，scan mode 从头到尾就是 connectable，钳了个寂寞。

所以 discoverable 是模块**自己维持的一个姿态**：

1. `scan.capture` 在蓝牙进程刚起来时（`AdapterService#onCreate`）拿到 service 实例，顺带反射取出它的 `AdapterProperties` 字段；
2. 每次 enable 回调 / 属性变化 / 5 s 看门狗都会跑一遍 `verify()`：读实时 scan mode，不是 discoverable 就补一次；
3. 补的方式按优先级挑：`BluetoothAdapter#setScanMode(int)` / `(int, int)`（公开 API，和配对页面同一条路，编码交给 ROM 自己转）→ `AdapterService#setScanMode(int, int)`（Android 9-12）/ `setScanMode(int, String)`（部分 ROM 的 13+ 变体）→ `AdapterProperties#setScanMode(int)`（只有这台 ROM 亲手往这个方法里喂过的值才敢用）；顺带把 `setDiscoverableTimeout` 写成 0。三次尝试都走 `invokeOriginal`，否则模块自己的钳位钩子会把刚选定的 duration 又清零，而 `learn()` 会把自家请求当成"ROM 教我的编码"。

**编码坑（Android 9-12 必须绕开）**：那几版的 `AdapterService#setScanMode(int, int)` 依次是
`setDiscoverableTimeout(duration)` → `convertScanModeToHal(mode)` → `AdapterProperties#setScanMode(hal)`，
也就是说 `AdapterProperties` 那层吃的是 **HAL 值 2**，不是框架值 23。往它写 23，Java 层一声不响，协议栈直接丢掉，实时 scan mode 永远停在 21。
模块因此**不猜编码**：每一层各自记住这台 ROM 真正喂给它的 discoverable 值，断言时按层用对应的值，`AdapterProperties` 那条路只在这台 ROM 亲手喂过之后才会走。

仍然让位于用户的两件事：

- **关蓝牙**：只有实时状态是 `STATE_ON` 才补，模块从不调用 `enable()`；关掉再打开时，姿态在 enable 回调里重新生效。
- **别的 UID 明确请求别的 scan mode**：解除 pin，日志会写"re-pinned when Bluetooth is on again"，即下次蓝牙开关时恢复。

**EN** — Stock Android requests `SCAN_MODE_CONNECTABLE_DISCOVERABLE` only for the tens of seconds the pairing screen is open, then drops back to connectable when the timeout fires, so no other phone can see the device. Clamping the timeout to 0 is not enough: it only lengthens a discoverable window somebody already requested. On a phone nobody ever opened the pairing page for, the scan mode is connectable from boot onwards, so there is nothing to lengthen.

Discoverable is therefore a **posture the module maintains itself**: `scan.capture` grabs the `AdapterService` instance in `onCreate()` (plus its `AdapterProperties` field by reflection) before anyone asks for a scan mode; every enable callback, property change and 5 s watchdog tick runs a `verify()` that reads the live scan mode and re-asserts it if needed; the re-assert walks a ladder — public `BluetoothAdapter#setScanMode` first (same road the pairing screen takes, so the ROM converts the encoding itself), then `AdapterService#setScanMode`, and only last `AdapterProperties#setScanMode`, and each attempt goes through `invokeOriginal` so that the module's own timeout clamp cannot zero the duration it just chose and `learn()` cannot mistake its own request for the ROM teaching it the encoding.

**The encoding trap (Android 9-12)**: in those versions `AdapterService#setScanMode(int, int)` runs `setDiscoverableTimeout(duration)` → `convertScanModeToHal(mode)` → `AdapterProperties#setScanMode(hal)`, so `AdapterProperties` expects the **HAL value 2**, not the framework value 23. Write 23 there and the Java layer stays quiet while the stack drops the request, leaving the live scan mode at 21 forever. The module therefore never guesses: each layer remembers the discoverable value *this* ROM actually fed it, and the `AdapterProperties` path is only used once that ROM has fed it a value itself.

Two things still win over the pin: turning Bluetooth off (the module only re-asserts while the live state is `STATE_ON` and never calls `enable()`; switching it back off and on re-applies the posture from the enable callback) and an explicit scan-mode request from another UID (the pin is released and the log says it will be re-applied the next time Bluetooth is turned on).

## 5. 功能四：为什么"分享面板没有蓝牙"，以及怎么改 / Feature 4: why Bluetooth is missing from the share sheet

分享面板的候选项来自**共享 App 自己**调 `PackageManager.queryIntentActivities(ACTION_SEND, type)`，而能不能匹配上是**系统蓝牙 APK 的 manifest** 决定的。`BluetoothOppLauncherActivity` 的 filter 只列了：

```
image/*  video/*  audio/*  text/x-vcard  text/x-vcalendar  text/calendar
text/plain  text/html  text/xml  application/zip  application/vnd.ms-excel
application/msword  application/vnd.ms-powerpoint  application/pdf
application/vnd.openxmlformats-officedocument.*  application/x-hwp
```

`application/vnd.android.package-archive` 不在里面，所以分享 APK 时面板里没有蓝牙。
有些文件管理器"能看到蓝牙"，是因为它把 APK 的分享类型写成 `application/zip`（或显式指定组件）——它绕过了 filter，而我们改不了别人的 manifest。

能改的是**解析结果本身**：解析虽然由 App 发起，但真正跑在 system_server，于是在那里往结果列表追加一条蓝牙即可，一次覆盖所有 App。要点：

- **不手搓 `ResolveInfo`**：hook 内用同一个方法、把 type 换成 `application/zip` 再查一次（通过 API 102 的 `getInvoker(method).setType(ORIGIN)`，绕过自己的 hook，不会递归），从返回里取蓝牙那条真实对象复用 —— label、图标、`filter`、`match` 标志全是真的，长按"设为默认"不会 NPE。
- **hook 点按候选类轮询**：`IPackageManagerBase` → `PackageManagerService` → `PackageManagerInternalBase` → `PackageManagerServiceEx`，装到第一个出方法的为止。Android 13+ 的 `queryIntentActivities(Intent, String, long, int)` 声明在 `IPackageManagerBase`，`PackageManagerService` 上已经没有它（只挂在后者会装出 0 个 hook）；Android 9-12 才在 `PackageManagerService`，签名是 `(Intent, String, int, int)`。匹配条件收紧成"方法名恰好是 `queryintentactivities` 且第二个参数是 `String`"，用来排除 `PackageManagerService$PackageManagerInternalImpl` 的 `(Intent, int, int, int)`（嵌套类会被一起扫）以及 `…AsUser` / `…Internal` 变体。
- 结果按用户 ID 缓存 10 s，不是每次打开面板都多跑一次解析。
- 列表里已经有蓝牙就不加（`application/zip` 那类分享走原生路径）。
- **失败即不注入**：蓝牙关闭时该 activity 是 `android:enabled="false"`，探针查不到东西，什么都不加；任何异常都原样返回调用方已拿到的列表。
- 点击后是显式组件启动，不再复查 filter，接着就落到功能二的发送链路上。

代价：这是一个全局 hook，影响整机所有分享面板；所以它的异常模式是 `PROTECTIVE`，且只在 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 上做动作。

**EN** — The share sheet's candidates come from the sharing app calling `PackageManager.queryIntentActivities(ACTION_SEND, type)`, and whether Bluetooth matches is decided by the system Bluetooth APK's manifest, whose filter lists only the types above — `application/vnd.android.package-archive` is not among them, so sharing an APK never offers Bluetooth. File managers that *do* show it simply declare the APK as `application/zip` (or name the component explicitly); they bypass the filter, and we cannot rewrite somebody else's manifest. What we can change is the **resolution result itself**: the query is issued by the app but executed in system_server, so appending one Bluetooth entry there covers every app at once.

Key points: never hand-roll a `ResolveInfo` — re-run the same method with the type forced to `application/zip` (through `getInvoker(method).setType(ORIGIN)`, which bypasses this hook and cannot recurse) and reuse the real Bluetooth entry from that result, so its label, icon, `filter` and match flags are genuine and "set as default" will not NPE; poll candidate classes (`IPackageManagerBase` → `PackageManagerService` → `PackageManagerInternalBase` → `PackageManagerServiceEx`) because Android 13+ declares the method on `IPackageManagerBase` while Android 9-12 still has it on `PackageManagerService`, and hooking only the latter installs 0 hooks on a modern ROM; cache per user ID for 10 s; skip when the list already contains Bluetooth; **inject nothing on failure** — with Bluetooth off the activity is `android:enabled="false"`, the probe finds nothing and the caller keeps the list it already had; the click is an explicit component launch, so it goes straight into feature 2's send path. The cost is a global hook, hence `PROTECTIVE` exception mode and action only on `ACTION_SEND` / `ACTION_SEND_MULTIPLE`.

## 6. 诊断 / Diagnostics

日志统一 tag `BTUnlock`：

```bash
adb logcat -s BTUnlock
# 详细日志要在目标进程启动前设置 / set this before the target process starts
adb shell setprop log.tag.BTUnlock VERBOSE
```

装没装上、每个功能装了几条 hook，看这两行就够了：

- `[com.android.bluetooth] hooks installed: receive=N, send=N, discoverable=N`
- `[system_server] hooks installed: discoverable-fallback=N, share-sheet=M`

判断流程：

- **分享面板没有蓝牙**：日志里连 `[system_server] hooks installed` 都没有 → 模块没进 system_server，是作用域缺 `system`（勾选后重启整机）；有这一行但 `share-sheet=0` 并带 `no hookable queryIntentActivities …` → 是这个 ROM 的解析方法换了位置，按 WARN 里列出的类名/方法名补候选。
- **可发现没生效**：`asserted mode … (found 21, watchdog)` 每 5 s 刷一条、永远刷不完 → 断言发出去了但协议栈不认，九成是把框架值喂给了只吃 HAL 值的方法；正常情况这条一个开机周期只出现一两次，之后看门狗读到 discoverable 就闭嘴。
- 某个功能数为 0 会单独打 WARN，并把候选类里查到的方法名列出来，方便对着 ROM 源码补候选名，而不是直接崩掉被注入的进程。
- 功能四的日志只在 system_server，而 `logcat` 环形缓冲区很小，开机那段容易被冲掉；rooted 机器可以直接看框架落盘的日志：`grep btunlock /data/adb/lspd/log/*modules*.log`。

**EN** — All logging uses the single tag `BTUnlock`. Two lines tell you whether the module got in and how many hooks each feature installed: `[com.android.bluetooth] hooks installed: receive=N, send=N, discoverable=N` and `[system_server] hooks installed: discoverable-fallback=N, share-sheet=M`. If Bluetooth is missing from the share sheet and there is no `[system_server]` line at all, the module never reached system_server — the scope is missing `system` (tick it, then reboot). If that line exists with `share-sheet=0` and `no hookable queryIntentActivities …`, this ROM moved the resolver method and you need to extend the candidate list using the names the WARN prints. If `asserted mode … (found 21, watchdog)` repeats every 5 s without end, the assert goes out but the stack ignores it — almost always a framework value handed to a HAL-only setter; normally it appears once or twice per boot. A zero count for any feature logs a WARN with the method names found in the candidate classes, so a renamed ROM method is a documentation gap rather than a crash. Feature 4 logs live only in system_server, where the `logcat` ring buffer is small and boot-time lines get evicted; on a rooted device the framework's own log is steadier: `grep btunlock /data/adb/lspd/log/*modules*.log`.

## 7. 刻意保留 / 不做的事 / Deliberately kept, deliberately not done

- **不破坏其它校验**：长度为 0、文件名为空、既无扩展名也无 TYPE 头，一律照旧拒绝（没有名字/类型可落地）。
- **不碰安全修复**：`BluetoothOppSendFileInfo` 的 `isForbiddenContent`（CVE-2022-35310991 系列）与"只允许外部存储 `file://`"限制原样保留 —— 那是权限问题不是类型问题，放开等于让任意 App 读它没被授权的文件。
- **不处理 `FileUriExposedException`**：Android 9+ 由系统对 `file://` 的限制决定，模块不主动丢弃 `file://` URI，也不代为绕过。
- **不阻止用户关蓝牙**：功能三只在实时状态 `STATE_ON` 时补 discoverable，从不调 `enable()`；别的 UID 明确请求 `SCAN_MODE_NONE` / `SCAN_MODE_CONNECTABLE` 会解除 pin，直到蓝牙关开一次再恢复。
- **拒绝热重载**：`onHotReloading()` 返回 `false` 并打日志。现代 API 热重载不会重放包生命周期回调，默认实现还会把老 hook 全 `unhook()`，接了反而留下一个静默无 hook 的进程。
- **不开混淆**：入口类名要被 `java_init.list` 指到、目标类靠名字反射查，`minifyEnabled false`；`proguard-rules.pro` 只是给以后打开 R8 的人准备的 libxposed 官方规则。

**EN** — Every other validation stays: zero length, empty name, or neither extension nor TYPE header is still refused, because there is no name or type to land on. The security fixes stay: `isForbiddenContent` in `BluetoothOppSendFileInfo` and the "external storage `file://` only" rule are untouched — they are authorisation, not typing, and relaxing them would let any app read files it was never granted. `FileUriExposedException` is not handled: it is the platform's Android 9+ `file://` restriction, and the hook neither drops a `file://` URI nor works around the platform. Switching Bluetooth off always wins, as described above. `onHotReloading()` returns `false` on purpose — a modern-API hot reload does not replay the package lifecycle callbacks and the default implementation unhooks everything, which would leave a silently unhooked process. Minification is off because the entry class name is referenced by `java_init.list` and target classes are found by name; `proguard-rules.pro` only carries the official libxposed rules for whoever enables R8 later.

## 8. 已知限制 / Known limitations

1. **共享 App 自己不给类型**时，补类型只发生在蓝牙进程内；面板里那条蓝牙是功能四加的，点开才会走补类型逻辑。
2. 功能四在**蓝牙未打开时不显示蓝牙**（该 activity 本身是 disabled），这是设计而非 bug。
3. 功能四的前提是面板**去问系统解析**。自带固定分享列表、根本不查解析的自研面板会漏掉。
4. 功能三的"永久"依赖蓝牙进程活着：进程重启期间有最长 5 s 空窗，且 discoverable 是在 enable 回调 / 属性变化 / 看门狗三个点上补的。ROM 若把这三层 setter 全改名，日志会打 `discoverable: nothing reachable to set the mode with …`，此时只剩超时钳位（即回到"要先进一次配对页面"）。
5. 未做任何 UI/通知：模块包只有一个 `application` 节点，没有图标也没有启动入口。
6. `system` 这个 token 在 LSPosed 2.1.1 上验证过。别的框架若把 system_server 记成别的名字，症状是日志里没有 `[system_server] hooks installed` 那一行且功能四静默失效，功能一/二/三不受影响 —— 按 §6 的判断流程走，不要先怀疑 hook 代码。
7. 真机验证覆盖 Android 10 与 Android 16 各一台；Android 11-15 是按 AOSP 源码推出的同一套形状匹配，未逐版验证。

**EN** —
1. When the sharing app supplies no type at all, the type is filled in only inside the Bluetooth process; the Bluetooth row in the sheet comes from feature 4 and the fill-in logic runs after you tap it.
2. Feature 4 shows no Bluetooth row while Bluetooth is off — the activity itself is disabled. That is by design.
3. Feature 4 assumes the sheet asks the system for resolution. A custom sheet with its own hard-coded list, which never queries the resolver, will still miss it.
4. "Permanently" in feature 3 depends on the Bluetooth process being alive: up to 5 s of gap while it restarts, and discoverable is only re-asserted at the enable callback, a property change, or the watchdog. If a ROM renames all three setter layers the log prints `discoverable: nothing reachable to set the mode with …` and only the timeout clamp remains — i.e. you are back to visiting the pairing screen once.
5. No UI and no notifications: the package has a single `<application>` node, no icon and no launcher entry.
6. The `system` token was verified on LSPosed 2.1.1. If another framework records system_server under a different name, feature 4 fails silently while 1/2/3 keep working; follow the §6 decision path before suspecting hook code.
7. Real-device testing covers one Android 10 and one Android 16 phone; Android 11-15 relies on the same shape-matching derived from AOSP source and is not individually verified.

## 9. 结构 / Layout

```
app/src/main/resources/META-INF/xposed/
  java_init.list      com.btunlock.ModuleEntry
  module.prop         minApiVersion=102 / targetApiVersion=102 / staticScope=true
  scope.list          com.android.bluetooth / system
app/src/main/java/com/btunlock/
  ModuleEntry.java            入口：生命周期回调 + 按包安装 + 热重载拒绝
  core/Hooks.java             候选类查找、方法形状匹配、hook 安装（PROTECTIVE + setId）、deoptimize、invokeOriginal
  hooks/OppReceiveHooks.java  功能一 / feature 1
  hooks/OppSendHooks.java     功能二 / feature 2
  hooks/ScanModeHooks.java    功能三（断言阶梯 公开 API→AdapterService→AdapterProperties + 实例捕获 + 看门狗）/ feature 3
  hooks/ShareSheetHooks.java  功能四（system_server 意图解析注入）/ feature 4
```

`ModuleEntry.java` 只做生命周期回调、按包安装和热重载拒绝；所有查找与匹配逻辑集中在 `core/Hooks.java`，
四个功能各自一个文件，互不依赖，某个 ROM 少一个方法只会让那一格变成 WARN。

**EN** — `ModuleEntry.java` is lifecycle + per-package installation + hot-reload refusal; lookup and shape matching live in `core/Hooks.java`; each feature is its own file, so a missing method on some ROM turns one table cell into a WARN instead of taking the module down.
