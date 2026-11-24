# 一加 15：虚拟屏后续 Activity 路由（T-1279）

2026-11-23，设备为 PLK110、ColorOS、Android SDK 37，运行 Mika Dev `a30a81e0`。问题未修复，未覆装。

## 已确认

- 旧的熄屏记录显示，12306 启动任务在虚拟屏 15，物理背光从 `0` 变为 `1570`。这段日志没有捕获首页的任务落点；不能仅凭亮屏就判定是哪条系统调用导致的。
- 上一轮 WSL 的第 30 次重试于手机本地时间 20:00:28 提交，Mika 最终报 `done`。物理屏已于 19:59:53 亮起；`mon.log` 在 20:00:25–20:01:14 没有背光归零记录。`events.log` 显示 20:01:07 启动 `WelcomeGuideActivity`、20:01:08 进入带 `NEW_TASK` 的 `MainActivity`，两者都在虚拟屏 3 的同一任务 `35202`。这次未记录到逃逸，不能作为熄屏验收。
- `ShellService.launchOptions` 只约束 Mika 发起的启动。当前固件在 `TaskLaunchParamsModifierExtImpl` 里明确把 Mirage 流转屏（模式 4）上没有来源 Activity、也没有指定显示区域的任务重用启动改派到默认显示区。这条分支发生在任务摆放前，不是 `MainScreenSpill` 能事后阻止的。
- 旧版 Mika 用 Mirage 登记取得非默认显示组；单独删掉它会令该固件上的虚拟屏功能不可用。真机上带独立组标记、无 Mirage 登记的私有可信显示屏仍加入组 `0`，创建和释放时读到物理背光为 `0`。这次没有启动任何 Activity。
- 已停掉 WSL 残留的背光监控及系统事件采集，重试循环已自行退出。保留了手机 `/data/local/tmp` 的 `mon.log`、`retry.log` 和 `events.log`，停止后确认三份日志均不再增长。

## 接续方案

在系统的 Activity 启动和任务摆放阶段实施隔离，不要在主屏出现后再移走任务。只约束 Mika 当前会话登记的包、用户和有效虚拟屏；必须覆盖无来源 Activity 的进程启动及任务重用。虚拟屏不可用或无法保证隔离时拒绝启动，不回落显示屏 `0`。需要经确认的系统级部署窗口；仅修改 Mika 的调用方或换一种未经验证的投屏模式，都不能证明这个约束。

真机回归在物理屏已熄灭时执行，与模型服务解耦：用 Mika 的 root 启动链路打开 12306，只等待首页并取虚拟屏截图，不点按、不下单。通过条件是启动页、首页及其任务只出现在同一块虚拟屏，过程中物理背光为 `0`、主屏焦点不变。

## 2026-11-24 接续：阻断已知不安全回退，完整修复仍未完成

- 检查了上一轮工作区与提交记录：Mika 仍在 `a30a81e0`，上一轮没有提交任务路由修复；已有未提交测试调整和 watchdog 文件单独保留。
- 本轮变更在 `ShellService.createVirtualDisplay` 中移除实际调用 Mirage 登记的回退分支。系统未原生分配独立显示组时，先释放尚未启动 App 的空显示屏，返回专门的不可用状态；`AgentDisplay` 将其传回任务入口，明确提示任务路由隔离尚不可用。不会为了得到独立电源组而启用存在 mode-4 任务回退的路径。
- 这只是停止不安全启动，不是 12306 首页跳转已修复。在当前一加 15 固件上，这会暂时拒绝虚拟屏操作；计算器等依赖同一 Mirage 回退的 App 也受影响。未实现按包、用户、显示屏会话绑定的系统任务启动/放置拦截，不能恢复该回退。
- 针对本轮分支验证默认组、未知/无效组和物理显示屏被拒绝；原生独立组仍被接受。单元测试不能证明后续 Activity 或背光的真机行为。
- root SSH 响应延迟较大，但最终返回设备 `PLK110`、SDK `37`、Mika Dev 与 helper 正在运行、LSPosed 服务存在。查询时物理背光为 `1558`、电源状态 `Awake`。ADB 为 `offline`；另一项 Sidekick 更新正在使用安装流程。本轮没有唤醒或操纵物理屏、没有启动 12306、没有改 LSPosed 配置、没有重启或安装 APK。
- 后一次只读查询取得手机本地时间 `2026-11-24 14:19:22 +08`，实际背光为 `0`；未在此期间启动 App，不能作为 12306 熄屏验收。
- 代码提交：`dce64656`（拒绝 Mirage 回退）、`26908fcf`（将连接器的 Android 日志输出移到调用层），已推送 `root-display-backend`。首轮编译成功，但 4 个连接器 JVM 测试因直接调用 Android 日志失败；修正后通过 `pc-build` 在电脑上复跑同组检查：`RootDisplayIsolationTest` 5 项、`AndroidRootDisplayDriverTest` 3 项、`RootDisplayConnectorTest` 4 项，共 12 项，零失败、零错误、零跳过。任务编号 `20261124T062935-mika-26908fcf6-a67d`，XML 结果在该任务的 `out/`。本轮未构建装机 APK。
- 后续应在已确认的系统任务钩子部署方式下完成会话级路由约束，再持安装锁并核对原证书、备份 APK 与数据、覆盖更新。若激活新系统钩子需要重启，须另外确认窗口；不为验收擅自重启。最后等待自然熄屏，完成从启动页到首页的同显示屏与背光 `0` 验证。

## 2026-11-24 再接续：系统侧实现已构建，未装机，等待激活窗口

本轮已实现独立的 `display-routing` LSPosed 模块及 Mika root 助手客户端，**不是再加一层禁用开关，但也尚未通过真机验收，问题保持未完成**。手机没有覆装、没有更改 LSPosed 配置、没有重启，也没有启动 12306。

### 系统实现

- `display-routing/src/main/java/com/wangzan101/mika/routing/SystemRoutingModule.java` 仅在 `android` 系统进程中运行。通过现有 `activity_task` Binder 的 UID 0 专用事务登记会话；不增加网络端口、可写配置文件或对外组件。必须成功安装全部钩子并核对依赖字段／方法，握手才返回可用。
- 绑定包含包名、用户、实际 UID、显示屏 ID、显示屏 uniqueId 和助手存活 Binder。包与 UID 由系统包管理器解析；该 UID 在其他屏有 Activity 时拒绝绑定。同 UID 的派生进程和共享进程包沿用会话，不影响其他用户的同名包。
- 在 `ActivityStarter.startActivityInner` 开始时设置显示屏和显示区 token，清除调用方残留的 task/root-task 选择；在 `LaunchParamsController.calculate` 返回后覆盖最终显示区，覆盖 ColorOS 的无来源启动／复用任务改派。复用任务只接受同屏任务，`WindowContainer.addChild/reparent` 在实际挂接前拒绝跨屏放置。没有逃逸后再搬回的逻辑。
- 对会话 Activity 拒绝 `canTurnScreenOn`，并阻止可归因于会话的应用请求唤醒物理电源组；不阻止用户的电源键和手势唤醒。这个归因和实际背光行为仍需真机验证，不能凭代码或单元测试认定已通过。
- 显示屏失效／身份变化、独立显示组丢失、助手死亡和关闭中的会话均拒绝继续启动。助手死亡保留拒绝绑定；正常结束先建立系统关闭屏障，再移除任务，系统确认会话不再有 Activity 后才解除绑定和释放显示屏。
- `RootTaskRouting.kt` 与 `ShellService.kt` 接入会话协议。只有系统握手成功后才允许恢复 Mirage 登记，且登记后必须拿到独立组并通过系统会话校验；钩子未加载时在建屏前停止。分享、链接和系统页面入口也通过同一绑定路径。

本轮从手机读取了实际 `services.jar`，核对了 `startActivityInner`、`LaunchParamsController.calculate`、`resolveReusableTask`、`WindowContainer` 的挂接接口及电源组唤醒接口签名。真机的 Android 17 已没有旧 AOSP 文件中的 `getPreferredLaunchTaskDisplayArea`，实现未依赖该方法。固件资料保存在服务器报告目录的 `firmware/`，未加入 Git。

### 构建与产物

- 已推送 `root-display-backend`：`8c2da55a` 为主体实现，`4b059bf2` 修正 Kotlin 显式键查询并检查系统接口形状，`bae1baaf` 补齐实际 ATMS 解析及无输入验收脚本，`f50a9015` 完成共享 UID 包约束。
- Mika Dev 的代码在 `4b059bf2` 后没有变动，装机候选是该提交的 `mika-dev-4b059bf2-signed.apk`；系统模块装机候选为最新 `f50a9015` 的 `display-routing-f50a9015-signed.apk`。两者协议版本均为 1。
- 两轮构建均通过 `pc-build` 交电脑完成：`20261124T070016-mika-4b059bf2f-f6e3` 构建配套 Mika 和模块，`20261124T071354-mika-f50a9015a-f39a` 仅重建最后修改的系统模块。两份 Gradle 日志均为 `BUILD SUCCESSFUL`，电脑返回的 `result.json` 均为 `ok:true`。
- `pc-build` 收尾存在工具问题：混合收集 APK 和测试 XML 时把 XML 也交给重签，导致外层命令退出 1。没有重跑编译来规避此问题；分别核验已经生成的真正 APK 后收集产物。两份 APK 的 `apksigner verify` 均通过，证书均为原 Mika 证书 `94ee3378d98379b17d9730f93ba27cad2cb3798f3cd10adcd6a6257fbc639e97`，包名分别为 `com.wangzan101.mika.dev` 与 `com.wangzan101.mika.routing`。
- 最终采用的测试结果是新模块策略 10 项、显示屏隔离 5 项、输入驱动 3 项、连接器 4 项，共 **22 项，零失败、零错误、零跳过**。这些测试没有运行真实系统钩子，不能替代真机回归。
- APK、四份测试 XML 和构建日志在 `/opt/sidekick-work/reports/mika-routing-20261124/`。没有把安装包推到手机，也没有安装模块或 Mika 更新。

### 激活与真机回归仍未完成

手机 root SSH 已恢复，LSPosed 为 `2.2.1 (7912)`、API 102，daemon 正常，`developerMode:false`，系统进程已请求过模块列表。当前 CLI 仅公开 `status` 和 `stop`，未发现受支持的首次动态注入命令。按现有部署方式，需要启用新模块的 Android 作用域并重启以首次激活；本轮已通过当前会话请求激活／重启窗口，尚未得到确认，因此保持未装机状态。不编辑正在运行的 LSPosed SQLite 数据库，也不为调试擅自重启。

`tools/mika/check-root-routing.sh` 已提交，`sh -n` 通过，**尚未在手机执行**。窗口确认后先持 `/run/lock/sidekick-android-install.lock`，按既定规范核验原证书、备份 APK／数据／LSPosed 配置、完成模块激活与覆盖更新（绝不卸载），同步 watchdog 审批 APK，再运行无输入验收。脚本等待自然熄屏，使用 `IsolatedSessionMain` 启动 12306、等待 `MainActivity` 并截取虚拟屏；记录 50 ms 间隔的实际背光、路由事件、首页 Activity dump 及前后焦点。任何非零背光读数都会停止，不点击、不下单。证据保留在手机 `/data/local/tmp/mika-routing.XXXXXX/`，还须人工检查首页图片和全部 Activity／Task 落点，才能认定修复通过。

原有未提交的 `RootDisplayScreenTest.kt` 调整及 watchdog 两个文件保持原样，未混入本轮提交。
