# Bluetooth Transfer Unlock

中文 | [English](README.en.md)

> [!WARNING]
> **注意，本人只对Android系统有少量了解，项目中本人只提供想法，所有修改均为AIGC，使用前务必审查项目代码防止出现不可逆破坏。**

LSPosed 模块，现代 libxposed API（102）：无 UI、无配置界面，启用模块 → 勾选作用域 → 重启目标进程即可。解开蓝牙 OPP 的四条限制：

| 功能 | 一句话 |
| :- | :- |
| 一 | 接收端不再按 MIME 白名单拒绝任何文件类型 |
| 二 | 发送端不再因为没有类型 / 类型不认识而中断分享 |
| 三 | 永久可被搜索：蓝牙一开就自己断言 discoverable，不用进配对页面，也不会被超时降级 |
| 四 | 蓝牙出现在**任何** App 的分享面板里，不再只有 zip/图片/视频能看到 |

> **风险**：功能三让设备在蓝牙打开期间随时可被扫描到（不想被搜到就关蓝牙）；功能四是注入 system_server 的全局 hook，影响整机所有分享面板。

## 构建

JDK 17 + Gradle 9.3.1（wrapper 已带上）+ AGP 9.1.0，compile/target SDK 36，minSdk 26。依赖只有 `io.github.libxposed:api:102.0.0` 与 `androidx.annotation:annotation-jvm`，都是 `compileOnly`，不打进 APK，所以整个模块只有约 26 KB。

```bash
JAVA_HOME="<你的 JDK 17 路径>" ./gradlew :app:assembleDebug :app:assembleRelease
```

## 启用

`module.prop` 里 `staticScope=true`，APK 内的 `META-INF/xposed/scope.list` 只预填默认值，装完仍可在 LSPosed 里逐条勾选：

- `com.android.bluetooth` —— 功能一/二/三的全部实现。**只要这三项，勾这一条就够。**
- `system` —— 功能四的唯一开关（意图解析跑在 system_server）。不需要它就别勾。
- 别写 `android`：现代模块的 `scope.list` 是按行原样读的，`android`↔`system` 别名只存在于 legacy `xposedscope`。写错的后果是功能四静默失效、其余功能照常，看起来像 hook 代码坏了。

重启：功能一/二/三重启蓝牙进程即可（关开一次蓝牙，或 `adb shell am force-stop com.android.bluetooth`）；**功能四必须重启整机**，system_server 不会因为蓝牙进程重启而重新加载模块。

模块靠 `META-INF/xposed/java_init.list` 被识别，manifest 里没有 legacy 的 `xposedmodule/xposedminversion/xposedscope`；工程里没有 `de.robv.android.xposed.*`。

## 能改哪些点

类按候选名查、方法按"名字 + 参数形状"匹配，找不到就静默跳过并打 WARN，不会崩掉被注入的进程。已核对的 Android 9-16 目标：

- **接收**：`opp.Constants` 里所有形如 `(String, String[]|String|List)` 的类型匹配方法直接返回 `true`；`BluetoothOppObexServerSession.onPut` 开一个线程内的"放宽窗口"，窗口内查不到的扩展名补成 `application/octet-stream`；顺带 `deoptimize()` 对抗内联。
- **发送**：`BluetoothOppLauncherActivity.onCreate/onNewIntent` 在 `EXTRA_STREAM` 存在而 type 缺失/`*/*` 时按 `ContentResolver.getType` → 扩展名 → `application/octet-stream` 补类型；`generateFileInfo` / `saveSendingFileInfo` 只替换**声明为 String 且实际为 null** 的参数。
- **可发现**：`set*DiscoverableTimeout*` 全部重载钳成 0；`setScanMode*` 在可发现模式时清零时长参数并按层记录编码；`AdapterService#onCreate` 抓住 service 实例（连带它的 `AdapterProperties`），之后每次 enable 回调 / 属性变化 / 5 s 看门狗都读实时 scan mode，不是 discoverable 就按 `BluetoothAdapter`（公开 API）→ `AdapterService` → `AdapterProperties` 的阶梯补一次，三次都走 `invokeOriginal`。
  **Android 9-12 的坑**：`AdapterProperties#setScanMode(int)` 吃的是 `convertScanModeToHal` **之后**的 HAL 值 2，不是框架值 23；写 23 会静默失效、实时模式永远停在 21。所以模块不猜编码——每一层只用自己的方法真正收到过的值。
- **分享面板**：面板候选来自共享 App 调 `queryIntentActivities(ACTION_SEND, type)`，而系统蓝牙 APK 的 filter 没列 `application/vnd.android.package-archive`（有些文件管理器"能看到蓝牙"是因为它把 APK 报成 `application/zip`），manifest 改不了，就改解析结果：在 system_server 里给 `ACTION_SEND(_MULTIPLE)` 追加一条蓝牙。条目不是手搓的 `ResolveInfo`，而是把 type 换成 `application/zip` 用 `getInvoker(...).setType(ORIGIN)` 再查一次、复用返回里那条真实对象（label/图标/filter/match 全是真的）；按 `IPackageManagerBase` → `PackageManagerService` → 变体类轮询候选（Android 13+ 声明在前者，9-12 才在后者）；结果按用户 ID 缓存 10 s；已有蓝牙就不加；蓝牙关着（activity 是 disabled）或任何异常就不注入。

## 诊断

```bash
adb logcat -s BTUnlock
adb shell setprop log.tag.BTUnlock VERBOSE   # 要在目标进程启动前设置
```

- `[com.android.bluetooth] hooks installed: receive=N, send=N, discoverable=N` / `[system_server] hooks installed: discoverable-fallback=N, share-sheet=M` —— 装没装上、装了几条，看这两行。
- 面板没有蓝牙：连 `[system_server]` 这行都没有 → 作用域缺 `system`（勾上重启整机）；有这行但 `share-sheet=0` 并带 `no hookable queryIntentActivities …` → 这个 ROM 换了方法位置，照 WARN 列出的类名/方法名补候选。
- 搜不到：`asserted mode … (found 21, watchdog)` 每 5 s 刷不完 → 断言发出去了但协议栈不认，九成是把框架值喂给了只吃 HAL 值的方法；正常一个开机周期只出现一两次。
- system_server 的日志在 `logcat` 里很容易被冲掉，rooted 机器直接看 `grep bluetoothtransferunlock /data/adb/lspd/log/*modules*.log`。

## 已知限制

1. 共享 App 自己不给类型时，补类型只发生在蓝牙进程内；面板里那条蓝牙是功能四加的，点开才走补类型逻辑。
2. 功能四在蓝牙未打开时不显示蓝牙（该 activity 本身 disabled），是设计不是 bug。自带固定分享列表、根本不查解析的自研面板也会漏掉。
3. 功能三依赖蓝牙进程活着：重启期间最长 5 s 空窗；ROM 若把三层 setter 全改名，日志打 `discoverable: nothing reachable to set the mode with …`，此时只剩超时钳位（回到"要先进一次配对页面"）。
4. 无任何 UI/通知；`system` 这个 token 只在 LSPosed 2.1.1 上验过。
5. 真机验证覆盖 Android 10 与 Android 16 各一台；11-15 是按 AOSP 源码推出的同一套形状匹配，未逐版验证。

## 结构

```
app/src/main/resources/META-INF/xposed/   java_init.list / module.prop / scope.list
app/src/main/java/app/bluetoothtransferunlock/
  ModuleEntry.java            入口：生命周期 + 按包安装 + 热重载拒绝
  core/Hooks.java             候选类查找、方法形状匹配、hook 安装、deoptimize、invokeOriginal
  hooks/{OppReceive,OppSend,ScanMode,ShareSheet}Hooks.java   功能一~四，互不依赖
```
