# Hoshi Reader Android Agent Instructions

本仓库是 Hoshi Reader 的 Android/Kotlin/Jetpack Compose 原生复刻项目。目标是按垂直切片复刻 iOS 用户可见行为。

## 核心规则

- iOS 用户可见行为和 UI 是唯一真源；不要让 Android 默认行为、第三方示例或 POC 覆盖 iOS 用户可见行为。
- 开始功能切片前，先查看 `reference/Hoshi-Reader-iOS` 对应实现并总结用户交互、状态流和边界行为。
- 修复问题时不要做补丁式修复（补丁后发现没修好再继续叠新补丁）；应先把 iOS 版作为真源，直接参考 iOS 现有实现和状态流来修。只有在尽可能照齐 iOS 逻辑后，仍因 Android/iOS 系统层差异导致行为不一致时，才做最小偏差的 Android 侧适配，且不可进一步扩大实现偏差。
- 不要把 Swift 源码复制到 `app/src/main` 或任何 Android package。
- iOS 架构只作行为参考；Android 使用 repository、ViewModel、不可变 UI state。
- 涉及 Android 系统能力、平台限制、权限、Intent、DocumentsUI、WebView、Media3、WorkManager、Google/Jetpack API、打包安装或后台任务时，优先查询 Android/Google/Jetpack 官方文档确认当前推荐实现和限制；iOS 只作为用户交互和行为逻辑参考，具体实现方式以 Android 官方文档和本仓库 Android 架构为准。
- 推进顺序：model/storage -> bookshelf import -> reader -> dictionary popup -> Anki -> sync -> settings。
- 主路径：bookshelf -> import EPUB -> open reader -> select text -> lookup。
- 完成需求时先更新 `docs/TODO.md`，再把代码和 TODO 放进同一个 commit；用户明确要求不 commit 时不要提交。
- `docs/TODO.md` 只记录当前状态、下一步、阻塞项和长期有效的验证入口；不要把它当流水账。不要粘贴长 emulator/adb 验证记录、截图观察、发布历史或每次提交的详细复现过程。
- 用户可见变更写入 `docs/CHANGELOG.md`；架构重构方向保留在 `docs/ARCHITECTURE_REFACTORING.md`，具体执行流程和切片状态不要写入 tracked 文档，优先使用仓库本地 `.codex/skills/hoshi-refactoring-workflow`；详细调查和验证证据优先放在 issue、PR、commit message 或专门文档中。
- 完成新功能或修复问题时，同步更新 `docs/CHANGELOG.md` 的 `[Unreleased]` section；CHANGELOG 面向普通用户，只记录用户可感知的 App 功能、体验和问题修复，不记录 CI、agent workflow、私有 skill、构建脚本、依赖管理或仅开发者可见的内部改动。
- `[Unreleased]` 里的未发布功能不要再追加修补说明：如果一个功能还没发布，后续对它的 UI、行为、稳定性或内部实现调整应合并进该功能原本的 Added/Changed 条目，或直接不写；只有已发布版本中用户可遇到的问题被修复时，才写入 Fixed。
- 如果 commit 修复或实现了某个 GitHub Issue，`docs/CHANGELOG.md` 对应用户可见条目末尾加上 `#123` 形式的 issue 引用，便于 GitHub Release 页面自动生成可跳转链接。
- 修复问题时，如果用户要求建立 GitHub Issue，先调查问题现象和复现方式，再创建关联 issue；之后再进行实际修复，并在修复完成后的 commit message 中使用 closing keyword（如 `Closes #123`）关联该 issue，便于后续追踪 bug 记录。
- Commit message 使用 Conventional Commits。
- 修复 GitHub Issue 时，在 commit message 中使用 closing keyword（如 `Closes #123`）。
- 小型 GitHub Issue 修复（如文案、链接、配置等低风险单点修改）直接在 `main` 分支完成并提交；较大功能、跨模块重构或高风险改动再开 `codex/` 前缀分支。
- 禁止新增读取 `src/main` 源文件后用 `contains`、`substringAfter`、`indexOf` 等字符串方式断言实现细节的源码文本测试；这类断言浪费 token 和上下文，不能替代行为测试。需要回归覆盖时，优先写行为/API/状态流测试；只有 Manifest、资源 XML、Gradle 依赖、权限/Provider 声明等结构化配置，才可用解析结构后的断言。
- 禁止对已连接设备或模拟器运行会清除、重装或卸载 app 数据的测试命令，例如 `connectedDebugAndroidTest`、`connectedAndroidTest`、`installDebugAndroidTest` 或其他 Android instrumentation Gradle 任务；除非用户明确指定一次性设备并允许清数据。需要此类覆盖时，先使用专用空模拟器或让用户确认。
- 禁止使用手绘、自造或临时拼接的图标；新增或替换图标时使用 Material 3 / Material Icons 已有图标（Compose `Icons.*` 或官方 Material vector asset），只有明确的品牌资产需求才例外。

## 参考源码

- iOS：`reference/Hoshi-Reader-iOS`，上游分支 `develop`
- 常用查询：

```bash
rg "ReaderViewModel" reference/Hoshi-Reader-iOS
rg "LookupEngine" reference/Hoshi-Reader-iOS
```

## Android 技术栈

- UI：Jetpack Compose、Material 3。
- 语言/构建：Kotlin、Kotlin DSL。
- 导航：使用当前 Navigation3/typed route back stack 架构；新增路由按现有 `AppShell` / `NavDisplay` / route key 模式接入。
- 状态：ViewModel + immutable UI state + `StateFlow`。
- 数据：repository 负责文件、数据库、辞典、EPUB、网络。
- 异步：Kotlin coroutines，禁止阻塞主线程。
- JSON：Kotlin Serialization 或 Moshi。
- 持久化：保留 iOS sidecar JSON。
- 文件导入：Android Storage Access Framework + app-specific storage。
- Android API 按平台语义选择，不机械映射 iOS API。

## 辞典引擎

- 使用 `third_party/hoshidicts-kotlin-bridge` 做辞典导入和查询。
- JNI 绑定：`app/src/main/java/de/manhhao/hoshi/HoshiDicts.kt`
- Native 参考：`third_party/hoshidicts-kotlin-bridge/app/src/main/cpp`
- bridge 是辞典数据类和 native 入口的事实来源。
- 除非 bridge 缺少必要行为且已记录差距，否则不要重新实现 Yomitan 导入、变形还原、查词、媒体读取或样式提取。

```bash
rg "external fun" third_party/hoshidicts-kotlin-bridge
rg "importDictionary" third_party/hoshidicts-kotlin-bridge
git submodule status --recursive
git submodule update --init --recursive
```

## EPUB 与阅读器

- Parser 能力收敛到 iOS 已用模型：manifest、spine、toc、章节内容、资源读取、封面路径等；不要新增搜索、全文索引或额外导航 API。
- UniFFI：`uniffi.toml` 的 `[bindings.kotlin]` 需要 `android = true`；Android 打包侧用 JNA AAR，JVM 单测侧用 jar。
- `cargo-ndk` 只构建库目标，避免交叉编译 UniFFI bindgen 等 host binary。
- EPUB 导入必须走 SAF，把 zip 解压到 app-specific storage 后交给 Rust parser；不要依赖外部存储 file URI。
- 阅读器必须保留 WebView；查词依赖 WebView 和 JS 侧选择、坐标、DOM 逻辑。
- WebView 用 Compose `AndroidView` 嵌入；本地章节内容优先 `WebViewAssetLoader` 或 `loadDataWithBaseURL()`。
- 不要启用宽泛 file URL 访问，如 `allowUniversalAccessFromFileURLs`。
- 竖排分页注意 Android WebView/WKWebView 差异；图片页需稳定 CSS 尺寸约束，必要时取整 CSS 页面变量，避免 fractional column overflow 产生空白页。

## 阅读器调试

- 修复前先看 `reference/Hoshi-Reader-iOS/Features/Reader/ReaderWebView/ReaderWebView.swift` 和对应 JS/CSS。
- 优先消除 Android 与 iOS 差异，不要先堆单点兼容逻辑。
- “滑动直接换章节”：检查 `scrollTop`、`scrollHeight`、`clientHeight`；若还能页内滚动却切章节，说明 native 手势和 JS 边界判断不一致。
- “章节末尾空白页”：检查 `scrollHeight` 是否比整页高度多出极小尾差；重点看图片、封面、spacer、column gap。
- 图片异常要检查竖排列宽下的 `max-width`、`max-height`、`object-fit`、physical size。
- 调试分页用 Chrome DevTools Protocol 或 WebView inspection 读取 DOM；记录章节 id、`scrollTop`、`scrollHeight`、`clientHeight`。

阅读器手工验证至少覆盖：封面图片页、多图图版页、长文本页内翻页、章节末尾后翻、章节开头前翻、反向跨章节落点。

## 测试数据与模拟器

- EPUB：`testdata/test.epub`、`testdata/test2.epub`
- 辞典：`testdata/JMdict_english.zip`、`testdata/MK3.zip`、`testdata/freq.zip`、`testdata/pitch.zip`
- 字体：`testdata/KleeOne-SemiBold.ttf`
- Sasayaki：`testdata/test.srt`、`testdata/test.m4b`
- EPUB reader 手工验证样本：`testdata/test.epub`
- 导入必须通过 DocumentsUI 选择测试文件，或使用等价的已授权 `content://` URI；不要用 `file:///sdcard/...` 或 shell 拼出的未授权 `content://...`。
- 命令行辅助时，可先把样本推送到模拟器 Downloads，再通过 DocumentsUI 选择。
- 默认保留模拟器 app 数据；除非目标要求首启、空库、重复导入、迁移或损坏数据恢复，否则不要清数据。
- Frequency/Pitch 辞典按 iOS `DictionaryView` 类型逻辑分别导入；不要把 meta dictionaries 当 Term 兜底。
- 完成功能切片后需 Android 模拟器验证再 commit；无法验证或暂时阻塞时，在 `docs/TODO.md` 标 `blocked`。

## 查词测试

- 不要简单断言“adb 不能输入日文”；先检查输入法和 subtype：

```bash
$ANDROID_HOME/platform-tools/adb -s emulator-5554 shell ime list -s
$ANDROID_HOME/platform-tools/adb -s emulator-5554 shell dumpsys input_method | rg -n "mCurrentSubtype|Subtype|RotationList" -C 2
```

- Gboard 日文 QWERTY 可用罗马字输入：

```bash
$ANDROID_HOME/platform-tools/adb -s emulator-5554 shell input tap <search_x> <search_y>
$ANDROID_HOME/platform-tools/adb -s emulator-5554 shell input text taberu
$ANDROID_HOME/platform-tools/adb -s emulator-5554 shell input keyevent 66
```

- 日语优先测 `食べる` / `たべる`。
- 自动输入受影响时，允许用户手动输入；之后基于当前模拟器状态继续验证，不要清数据或重导入。
- 查词 WebView 用 DevTools/CDP 检查 DOM、按钮状态、JS 变量和 console log。

## 集成

- Anki：先调查 AnkiDroid API、intent 或 Android 可用 AnkiConnect 路径。
- Google Drive：使用 Android Google Sign-In/OAuth/Drive API，不复用 iOS token/keychain 思路。
- Audio/Sasayaki：使用 AndroidX Media3/ExoPlayer 和现有 Sasayaki controller/repository 边界推进，保持 iOS 侧可见播放、cue、导出行为一致。

## 验证

声明实现完成前运行：

```bash
./gradlew test
./gradlew assembleDebug
```

需要跑单个 JVM 单测或测试类时，不要使用 `./gradlew test --tests ...`；本仓库的 `:app:test` 是 Android Gradle 聚合任务，不支持 `--tests` 过滤。应使用：

```bash
./gradlew :app:testDebugUnitTest --tests fully.qualified.TestClassName
./gradlew :app:testDebugUnitTest --tests fully.qualified.TestClassName.testMethodName
```

修改资源、manifest、UI 或打包时还要运行：

```bash
./gradlew lint
```
