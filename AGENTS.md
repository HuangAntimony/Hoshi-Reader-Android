# Hoshi Reader Android Agent Instructions

本仓库是 Hoshi Reader 的 Android/Kotlin/Jetpack Compose 原生复刻项目。目标是按垂直切片复刻 iOS 用户可见行为。

## 核心规则

- iOS 用户可见行为和 UI 是唯一真源；不要让 Android 默认行为、第三方示例或 POC 覆盖 iOS 行为。
- 开始功能切片前，先查看 `reference/Hoshi-Reader-iOS` 对应实现并总结行为。
- 不要把 Swift 源码复制到 `app/src/main` 或任何 Android package。
- iOS 架构只作行为参考；Android 使用 repository、ViewModel、不可变 UI state。
- 推进顺序：model/storage -> bookshelf import -> reader -> dictionary popup -> Anki -> sync -> settings。
- 主路径：bookshelf -> import EPUB -> open reader -> select text -> lookup。
- 完成需求时先更新 `docs/TODO.md`，再把代码和 TODO 放进同一个 commit；用户明确要求不 commit 时不要提交。
- Commit message 使用 Conventional Commits。
- 修复 GitHub Issue 时，在 commit message 中使用 closing keyword（如 `Closes #123`）。

## 参考源码

- iOS：`reference/Hoshi-Reader-iOS`，上游分支 `develop`
- 常用查询：

```bash
rg "ReaderViewModel" reference/Hoshi-Reader-iOS
rg "LookupEngine" reference/Hoshi-Reader-iOS
```

## Android 技术栈

- UI：Jetpack Compose、Material 3、自定义 Hoshi 主题。
- 语言/构建：Kotlin、Kotlin DSL。
- 导航：优先 AndroidX Navigation Compose。
- 状态：ViewModel + immutable UI state + `StateFlow`。
- 数据：repository 负责文件、数据库、辞典、EPUB、网络。
- 异步：Kotlin coroutines，禁止阻塞主线程。
- JSON：Kotlin Serialization 或 Moshi。
- 持久化：优先保留 iOS sidecar JSON；仅在明显简化时使用 Room。
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

- EPUB 解析优先沿用 `../Hoshi-POC/app/src/main/rust/hoshiepub` 的 Rust/UniFFI 方案，对齐 iOS `EPUBKit`。
- 可借鉴 `../Hoshi-POC` 的 EPUB 解析和 Rust/UniFFI 构建方式；不要借鉴其阅读器 UI/交互。
- 不优先接入 Readium。
- Parser 能力收敛到 iOS 已用模型：manifest、spine、toc、章节内容、资源读取、封面路径等；不要新增搜索、全文索引或额外导航 API。
- UniFFI：`uniffi.toml` 的 `[bindings.kotlin]` 需要 `android = true`；Android 打包侧用 JNA AAR，JVM 单测侧用 jar。
- `cargo-ndk` 只构建库目标，避免交叉编译 UniFFI bindgen 等 host binary。
- EPUB 导入必须走 SAF，把 zip 解压到 app-specific storage 后交给 Rust parser；不要依赖外部存储 file URI。
- 阅读器必须保留 WebView；查词依赖 WebView 和 JS 侧选择、坐标、DOM 逻辑。
- WebView 用 Compose `AndroidView` 嵌入；本地章节内容优先 `WebViewAssetLoader` 或 `loadDataWithBaseURL()`。
- 不要启用宽泛 file URL 访问，如 `allowUniversalAccessFromFileURLs`。
- 覆盖日文竖排、自定义 CSS、字体/主题变化、进度恢复、文本选择、高亮、辞典弹窗定位。
- 翻页以 iOS `ReaderWebView` / `reader.js` 为准：页内由 JS 滚动；到章节边界才切章节；反向跨章节进入上一章末尾。
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

- `adb shell input text '食べる'` 失败通常是 shell/input method 限制，不代表应用失败。
- 日语优先测 `食べる` / `たべる`；ASCII 可导入 `testdata/MK3.zip` 后查 `test`。
- 自动输入受影响时，允许用户手动输入；之后基于当前模拟器状态继续验证，不要清数据或重导入。
- 查词 WebView 用 DevTools/CDP 检查 DOM、按钮状态、JS 变量和 console log。
- 音频按钮可用 `input motionevent DOWN/UP` 捕捉按下态，再查 logcat 的 `MediaPlayer`、`MediaHTTPService`、`audio/mpeg`。

## 集成

- Anki：先调查 AnkiDroid API、intent 或 Android 可用 AnkiConnect 路径。
- Google Drive：使用 Android Google Sign-In/OAuth/Drive API，不复用 iOS token/keychain 思路。
- Audio/Sasayaki：用 AndroidX Media3/ExoPlayer 做原型验证。

## 验证

声明实现完成前运行：

```bash
./gradlew test
./gradlew assembleDebug
```

修改资源、manifest、UI 或打包时还要运行：

```bash
./gradlew lint
```
