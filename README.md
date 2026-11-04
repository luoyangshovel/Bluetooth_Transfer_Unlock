# Bluetooth Transfer Unlock

> LSPosed / Vector module built on the **modern libxposed API (102)** — no UI, no Activity, no settings screen.
> It lifts four Bluetooth OPP limitations: receive any file type, send any file type, stay discoverable
> without opening the pairing screen, and show up in **every** app's share sheet.
> Install the APK, enable the module, tick the scope, restart the target process — there is nothing to configure.
>
> 中文文档见下（本工程只有中文 README，以上是英文摘要）。

| 功能 | 一句话 |
| :- | :- |
| 一 | 接收端不再按 MIME 白名单拒绝任何文件类型 |
| 二 | 发送端不再因为没有类型 / 类型不认识而中断分享 |
| 三 | 永久可被搜索：蓝牙一打开就自己断言 discoverable，不用进配对页面，也不会被超时降级 |
| 四 | 蓝牙出现在**任何** App 的分享面板里，不再只有 zip/图片/视频能看到 |

工程里没有 `de.robv.android.xposed.*`，全部走 `io.github.libxposed:api:102.0.0`（`compileOnly`，不打进 APK）。

> **风险提示**：功能三让设备在蓝牙打开期间随时可被扫描到，这是一个隐私权衡；不想被别人搜到就关掉蓝牙。
> 功能四是注入 system_server 的全局 hook，会影响整机所有分享面板。

---

## 1. 构建

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

## 2. 启用

1. 安装 APK → LSPosed / Vector 里启用 **Bluetooth Transfer Unlock**。
   模块靠 APK 内的 `META-INF/xposed/java_init.list` 被识别（LSPosed `ModuleUtil.getModernModuleApk()` 只看这个文件），
   manifest 里**没有** legacy 的 `xposedmodule/xposedminversion/xposedscope` meta-data，这是现代 API 的要求。
2. 勾选作用域。`module.prop` 里 `staticScope=true`，APK 内的 `scope.list` 只负责预填默认值（`com.android.bluetooth` + `system`），装好后在 LSPosed 里仍可逐条勾选：
   - `com.android.bluetooth` —— 功能一/二/三的全部实现都在这里（OPP + btservice）。**只要这三项的话，勾这一条就够了。**
   - `system` —— **这一条是功能四的唯一开关**：意图解析跑在 system_server，只有把模块注入该进程才改得了解析结果；顺带兜住少数把 scan mode 记账放在 framework 的 ROM。不需要"任何 App 的分享面板都出现蓝牙"，就别勾它（`scope.list` 里这行也可以直接删掉）。
   - 不需要 `android`：LSPosed 对现代模块按行**原样**读取 `scope.list`，`android`↔`system` 的别名转换只存在于 legacy `xposedscope`，所以 token 必须写对。写成 `android` 的后果是模块进不了 system_server，功能四静默失效，而功能一/二/三照常安装——很容易被误判成 hook 代码有问题。
3. 重启目标进程：
   - 功能一/二/三：蓝牙进程要重启。最省事是关掉蓝牙再打开（`AdapterService` 进程随之重启），或 `adb shell am force-stop com.android.bluetooth`。
   - **功能四必须重启整机**（system_server 不会因为蓝牙进程重启而重新加载模块）。LSPosed 管理器里可以直接"重启系统"。

## 3. 各 Android 版本已核实的 Hook 点

类名/方法名写在表里，但代码里**不硬编码签名**：类按候选名查，方法按"名字 + 参数形状"匹配，找不到就静默跳过并打 WARN 日志（见 §6）。

| Android | 源码位置 | 功能一（接收） | 功能二（发送） | 功能三（可发现） |
| :- | :- | :- | :- | :- |
| 9 / 10 / 11 / 12 | `packages/apps/Bluetooth` | `opp.BluetoothOppObexServerSession.onPut(javax.obex.Operation)`；`opp.Constants.mimeTypeMatches(String,String[])` + 私有 `(String,String)`；`Constants.ACCEPTABLE_SHARE_INBOUND_TYPES` | `opp.BluetoothOppLauncherActivity.onCreate` 的 `stream != null && type != null` 门；`opp.BluetoothOppSendFileInfo.generateFileInfo(Context,Uri,String,boolean)`；`opp.BluetoothOppManager.saveSendingFileInfo(String,…)` | `BluetoothAdapter.setScanMode(int,int)` → `btservice.AdapterService.setScanMode(int,int)` → `setDiscoverableTimeout(int)` → `AdapterProperties.setDiscoverableTimeout(int)` → `BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT`；同一条链上 `AdapterProperties.setScanMode(int)` 收的是 `convertScanModeToHal` **之后**的 HAL 值 2（见 §4） |
| 13 / 14 / 15 / 16 | `packages/modules/Bluetooth/android/app`（蓝牙 APK 已进主线 APEX `/apex/com.android.btservices`，包名不变） | **同一套逻辑**，只是 `onPut(com.android.obex.Operation)`、`isWhitelisted` 改名 `isAcceptlisted` | 同上 | `BluetoothAdapter.setScanMode(int)` + `setDiscoverableTimeout(Duration)` → binder `AdapterService$AdapterServiceBinder` → `AdapterProperties.setDiscoverableTimeout(int)` |

功能一在 Android 14 源码里对应的拒绝分支（逐条核对）：

```java
if (length == 0)            return OBEX_HTTP_LENGTH_REQUIRED;   // 保留
if (name == null||isEmpty)  return OBEX_HTTP_BAD_REQUEST;       // 保留
if (dotIndex < 0 && mimeType == null) return OBEX_HTTP_BAD_REQUEST; // 保留
type = map.getMimeTypeFromExtension(extension);
if (type == null && mimeType == null) return OBEX_HTTP_UNSUPPORTED_TYPE; // 打开
if (mimeType == null || (!isAcceptlisted && !Constants.mimeTypeMatches(mimeType, ACCEPTABLE_SHARE_INBOUND_TYPES)))
                            return OBEX_HTTP_UNSUPPORTED_TYPE;  // 打开
```

对应 hook（`setId` 前缀 `btu:`，重复安装是替换而不是叠加）：

| hook id | 位置 | 动作 |
| :- | :- | :- |
| `receive.acceptList` | `Constants` 里所有 `static boolean` 且形如 `(String, String[]|String|List)` 的类型匹配方法 | 直接返回 `true`，不再查白名单 |
| `receive.onPut` | `BluetoothOppObexServerSession.onPut` | 打开一个线程内的"放宽窗口"，并 `deoptimize()` 该方法对抗内联 |
| `receive.extension` | `android.webkit.MimeTypeMap.getMimeTypeFromExtension` | 仅在放宽窗口内、且扩展名查不到时返回 `application/octet-stream`；窗口外原样返回 |
| `send.launcher` | `BluetoothOppLauncherActivity.onCreate/onNewIntent` | `ACTION_SEND(_MULTIPLE)` 且 `EXTRA_STREAM` 存在而 type 缺失/`*/*` 时，按 `ContentResolver.getType` → 扩展名 → `application/octet-stream` 补一个类型 |
| `send.generateFileInfo` / `send.saveFileInfo` | 同名 OPP 方法 | 只把**声明为 String 且实际为 null** 的参数补成 `application/octet-stream`（不会误替换别的参数） |
| `scan.timeout` | `set*DiscoverableTimeout*` 全部重载 | `Integer→0` / `Long→0L` / `Duration→Duration.ZERO` |
| `scan.mode` | `setScanMode*` 全部重载 | 可发现模式（框架值 23 或 HAL 值 2）时把末尾的时长参数清零，并**按调用所在层**记录编码：`BluetoothAdapter` / `AdapterService` 记框架值，`AdapterProperties` 只记它真正收到过的值 |
| `scan.propertyChanged` | `AdapterProperties.adapterPropertyChangedCallback` | 原生栈把模式降回 connectable 后，重新以 timeout=0 断言一次 |
| `scan.capture` | `AdapterService.onCreate` / `onAdapterEnabled` / `processProfileServiceStateChanged` / `setState` | 在任何人请求 scan mode 之前拿到 `AdapterService`（并顺带取出它的 `AdapterProperties` 字段）；断言阶梯 = 公开 API → 这两个实例，见 §4 |
| `share.resolver` | system_server `queryIntentActivities(Intent, String, …)`（Android 13+ 在 `IPackageManagerBase`，Android 9-12 在 `PackageManagerService`） | 给 `ACTION_SEND(_MULTIPLE)` 的结果追加真实蓝牙条目（见 §5） |

## 4. 功能三：永久可发现，不用进配对页面

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

## 5. 功能四：为什么"分享面板没有蓝牙"，以及怎么改

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

## 6. 诊断

日志统一 tag `BTUnlock`：

```bash
adb logcat -s BTUnlock
# 详细日志要在目标进程启动前设置
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

## 7. 刻意保留 / 不做的事

- **不破坏其它校验**：长度为 0、文件名为空、既无扩展名也无 TYPE 头，一律照旧拒绝（没有名字/类型可落地）。
- **不碰安全修复**：`BluetoothOppSendFileInfo` 的 `isForbiddenContent`（CVE-2022-35310991 系列）与"只允许外部存储 `file://`"限制原样保留 —— 那是权限问题不是类型问题，放开等于让任意 App 读它没被授权的文件。
- **不处理 `FileUriExposedException`**：Android 9+ 由系统对 `file://` 的限制决定，模块不主动丢弃 `file://` URI，也不代为绕过。
- **不阻止用户关蓝牙**：功能三只在实时状态 `STATE_ON` 时补 discoverable，从不调 `enable()`；别的 UID 明确请求 `SCAN_MODE_NONE` / `SCAN_MODE_CONNECTABLE` 会解除 pin，直到蓝牙关开一次再恢复。
- **拒绝热重载**：`onHotReloading()` 返回 `false` 并打日志。现代 API 热重载不会重放包生命周期回调，默认实现还会把老 hook 全 `unhook()`，接了反而留下一个静默无 hook 的进程。
- **不开混淆**：入口类名要被 `java_init.list` 指到、目标类靠名字反射查，`minifyEnabled false`；`proguard-rules.pro` 只是给以后打开 R8 的人准备的 libxposed 官方规则。

## 8. 已知限制

1. **共享 App 自己不给类型**时，补类型只发生在蓝牙进程内；面板里那条蓝牙是功能四加的，点开才会走补类型逻辑。
2. 功能四在**蓝牙未打开时不显示蓝牙**（该 activity 本身是 disabled），这是设计而非 bug。
3. 功能四的前提是面板**去问系统解析**。自带固定分享列表、根本不查解析的自研面板会漏掉。
4. 功能三的"永久"依赖蓝牙进程活着：进程重启期间有最长 5 s 空窗，且 discoverable 是在 enable 回调 / 属性变化 / 看门狗三个点上补的。ROM 若把这三层 setter 全改名，日志会打 `discoverable: nothing reachable to set the mode with …`，此时只剩超时钳位（即回到"要先进一次配对页面"）。
5. 未做任何 UI/通知：模块包只有一个 `application` 节点，没有图标也没有启动入口。
6. `system` 这个 token 在 LSPosed 2.1.1 上验证过。别的框架若把 system_server 记成别的名字，症状是日志里没有 `[system_server] hooks installed` 那一行且功能四静默失效，功能一/二/三不受影响 —— 按 §6 的判断流程走，不要先怀疑 hook 代码。
7. 真机验证覆盖 Android 10 与 Android 16 各一台；Android 11-15 是按 AOSP 源码推出的同一套形状匹配，未逐版验证。

## 9. 结构

```
app/src/main/resources/META-INF/xposed/
  java_init.list      com.btunlock.ModuleEntry
  module.prop         minApiVersion=102 / targetApiVersion=102 / staticScope=true
  scope.list          com.android.bluetooth / system
app/src/main/java/com/btunlock/
  ModuleEntry.java            入口：生命周期回调 + 按包安装 + 热重载拒绝
  core/Hooks.java             候选类查找、方法形状匹配、hook 安装（PROTECTIVE + setId）、deoptimize、invokeOriginal
  hooks/OppReceiveHooks.java  功能一
  hooks/OppSendHooks.java     功能二
  hooks/ScanModeHooks.java    功能三（断言阶梯 公开 API→AdapterService→AdapterProperties + 实例捕获 + 看门狗）
  hooks/ShareSheetHooks.java  功能四（system_server 意图解析注入）
```
