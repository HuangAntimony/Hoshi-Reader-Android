# Hoshi Reader Android Agent 说明

本仓库是 iOS SwiftUI 应用 Hoshi Reader 的 Android/Kotlin/Jetpack Compose 原生复刻项目。

## iOS 参考源码

- iOS 参考源码 submodule：`reference/Hoshi-Reader-iOS`
- 上游分支：`develop`
- 只把 iOS 项目当作行为参考，不把它当作 Android 架构参考。
- 不要把 Swift 源码复制到 Android app source set 中。
- 查找 iOS 行为时，使用 `reference/Hoshi-Reader-iOS` 下的路径。

常用示例：

```bash
rg "ReaderViewModel" reference/Hoshi-Reader-iOS
rg "LookupEngine" reference/Hoshi-Reader-iOS
```

## 辞典引擎

- 辞典导入和查询应使用 `third_party/hoshidicts-kotlin-bridge`。
- bridge 的 Kotlin JNI 绑定在 `app/src/main/java/de/manhhao/hoshi/HoshiDicts.kt`。
- bridge 的 native 构建参考文件在 `third_party/hoshidicts-kotlin-bridge/app/src/main/cpp`。
- 本项目采用 GPLv3，必须使用 `third_party/hoshidicts-gplv3` 中的 GPLv3 版 `hoshidicts` 分支。
- bridge 仓库自身嵌套的 `app/src/main/cpp/hoshidicts` submodule 指向 `main-mit`；不要构建或链接这份嵌套副本。
- 将 native bridge 接入 Android app 时，需要调整 CMake 路径，让 `hoshidicts_jni.cpp` 链接到 `third_party/hoshidicts-gplv3`。
- 如果 GPL 版 bridge 在某个阶段确实阻塞开发，可以临时使用 MIT 版 bridge 先推进功能，但必须在 `docs/TODO.md` 记录临时状态、阻塞原因和后续切回 GPL bridge 的任务。不要因为临时 MIT bridge 而扩大 Android 侧能力或偏离 iOS 行为。
- bridge 是辞典数据类和 native 入口的事实来源。
- 除非 bridge 缺少必要行为并且已先记录差距，否则不要重新实现 Yomitan 导入、变形还原、查词、媒体读取或样式提取。

常用示例：

```bash
rg "external fun" third_party/hoshidicts-kotlin-bridge
rg "importDictionary" third_party/hoshidicts-kotlin-bridge
git -C third_party/hoshidicts-gplv3 branch --show-current
```

本仓库的 submodule 初始化方式：

```bash
git submodule update --init reference/Hoshi-Reader-iOS third_party/hoshidicts-kotlin-bridge third_party/hoshidicts-gplv3
git -C third_party/hoshidicts-gplv3 submodule update --init --recursive
```

不要在仓库根目录运行通用的 `git submodule update --init --recursive`，除非只是为了调查 bridge 内部的 `main-mit` 嵌套 submodule。

## Android 技术方向

- UI：Jetpack Compose。
- 语言：Kotlin。
- 构建脚本：Kotlin DSL。
- 设计系统：Material 3 + 自定义 Hoshi 主题。真实页面实现后不能停留在默认模板样式，界面应现代、精致。
- 导航：优先使用 AndroidX Navigation Compose；如果当前切片用更简单的局部方案明显足够，也可以保持简单。
- 状态：ViewModel 暴露不可变 UI state，优先使用 `StateFlow`。
- 数据工作：repository 负责文件、数据库、辞典、EPUB、网络等逻辑。
- 异步：使用 Kotlin coroutines；不要阻塞主线程。
- 结构化数据：JSON 优先使用 Kotlin Serialization 或 Moshi。
- 持久化：iOS app 使用大量书籍 sidecar JSON 文件。Android 首版应尽量保留这种形态，除非 Room 能明显简化某个功能。
- Android API 选择应符合 Android 行为，不要机械映射 iOS API。
- 文件导入使用 Android Storage Access Framework 和 app-specific storage。

## 迁移工作流

- 不要尝试一次性把整个 SwiftUI 项目翻译成 Android。
- 所有用户可见的交互逻辑和 UI 设计都以 iOS 版作为唯一真源。Android 代码、第三方库示例、POC、平台默认行为都不能覆盖 iOS 版行为。
- 每个功能切片开始时，先查看 `reference/Hoshi-Reader-iOS` 下相关 iOS 文件，再总结行为，然后实现 Android 版本。
- 如果用户指出 Android 行为或 UI 与 iOS 不一致，不要先写局部兼容代码或猜测性修复；应立即回到 iOS 实现，复刻对应逻辑，或明确找出 Android 当前实现与 iOS 的差异后再改。
- 后续开发路线和任务状态记录在 `docs/TODO.md`。每完成一个需求，必须先更新该文件里的状态，再把代码和状态更新放在同一个 commit 中。
- 每个完成的功能切片都需要通过 Android 模拟器做实际验证后再 commit；如果功能无法验证或遇到暂时无法解决的问题，在 `docs/TODO.md` 标为 `blocked` 并继续下一个可行切片。
- iOS singleton 和 `@Observable` 只作为行为参考；Android 中应映射为 repository、ViewModel 和不可变 UI state。
- 按垂直切片推进：model/storage、bookshelf import、reader、dictionary popup、Anki、sync、settings。
- 不要从完整设置页开始。主路径是 bookshelf -> import EPUB -> open reader -> select text -> lookup。
- 不要把 Swift 源码复制到 `app/src/main` 或任何 Android package。

## 阅读器方向

- EPUB 解析优先沿用 `../Hoshi-POC/app/src/main/rust/hoshiepub` 的 Rust/UniFFI 方案；这部分是当前 Android 版对齐 iOS `EPUBKit` 行为的主要基础。
- 不再优先接入 Readium。Readium 对当前目标过重，并且容易暴露多余能力导致 Android 行为与 iOS 版不一致。
- 可以借鉴 `../Hoshi-POC` 的 EPUB 解析库和 Rust/UniFFI 构建方式，但不要借鉴它的阅读器 UI/交互实现，原作者已说明那部分不可靠。
- EPUB parser 对外能力应尽量收敛到 iOS `EPUBKit` 已使用的模型：manifest、spine、toc、章节内容、资源读取、封面路径等。不要为了方便加入搜索、全文索引、额外导航 API 等 iOS 当前没有的能力。
- Rust/UniFFI 集成时，Android 生成绑定需要 `uniffi.toml` 中的 `[bindings.kotlin] android = true`，JNA 在 Android 打包侧使用 AAR，在 JVM 单测侧需要 jar 运行时。
- 使用 `cargo-ndk` 构建 Android native library 时只构建库目标，避免把 UniFFI bindgen 这类 host binary 也拿去交叉编译。
- EPUB 导入应使用 Android Storage Access Framework，把 zip 解压到 app-specific storage 后交给 Rust parser；不要依赖外部存储 file URI 可读性。
- Android WebView 可通过 Compose `AndroidView` 嵌入。
- 本地章节内容优先使用 `WebViewAssetLoader` 或 `loadDataWithBaseURL()`。
- 不要为了省事启用宽泛的 file URL 访问，例如 `allowUniversalAccessFromFileURLs`。
- 阅读器实现必须考虑日文竖排、自定义 CSS、字体/主题变化、进度恢复、文本选择、高亮和辞典弹窗定位。
- 阅读器渲染必须保留 WebView，因为后续查词依赖 WebView 和 JavaScript 侧选择、坐标、DOM 逻辑。
- 翻页交互以 iOS `ReaderWebView` / `reader.js` 行为为准：页内翻页由 WebView 内 JS 滚动完成，只有到章节边界后才切换章节；反向跨章节时应进入上一章末尾，而不是上一章开头。
- 竖排分页需要特别关注 Android WebView 与 WKWebView 的差异。图片页应使用稳定的 CSS 尺寸约束，必要时对基于页面宽高计算出的 CSS 变量取整，避免 fractional column overflow 导致章节末尾多出空白页。

## 阅读器调试经验

- 修复阅读器问题前，先查看 iOS 参考实现：`reference/Hoshi-Reader-iOS/Features/Reader/ReaderWebView/ReaderWebView.swift` 和对应的 reader JavaScript/CSS。不要每遇到一个症状就堆单点兼容逻辑；除非已经证明 iOS 行为在 Android WebView 上无法直接复刻，否则修复方向应是消除与 iOS 实现的差异。
- 排查“滑动直接换章节”时，先确认 WebView 内 `scrollTop` / `scrollHeight` / `clientHeight` 是否真的还能继续页内滚动；如果还能滚动却切章节，说明 native 手势和 JS 分页边界判断不一致。
- 排查“往后跨章节多出空白页”时，重点看当前章节末尾的 `scrollHeight` 是否比整页高度多出极小尾差，图片、封面、spacer、column gap 都可能触发 Android WebView 额外生成一页。
- 图片渲染异常不要只看是否加载成功，还要检查竖排列宽下的 `max-width` / `max-height` / `object-fit` / physical size 是否会撑出额外 column。
- 使用模拟器验证 EPUB 导入时，必须走真实 Android Storage Access Framework 流程：通过系统文件选择器/DocumentsUI 选择 EPUB，或使用等价的已授权 `content://` URI。不要用 `file:///sdcard/...` 触发导入；shell 拼出的 `content://com.android.externalstorage.documents/...` 也不会自动给 app 授权，常见结果是 `EACCES` 或 `SecurityException`，不能代表真实导入行为。
- 手工验证阅读器时，至少覆盖：封面图片页、包含多张图的图版页、长文本章节页内翻页、章节末尾往后翻、章节开头往前翻、反向跨章节落点。
- 调试 WebView 分页可用 Chrome DevTools Protocol 或 WebView inspection 读取当前 DOM 状态，记录章节 id、`scrollTop`、`scrollHeight`、`clientHeight`，不要只凭截图判断。
- EPUB reader 的手工验证样本使用 `testdata/test.epub`；不要再假设测试 EPUB 位于仓库根目录。

## 测试数据

- EPUB 测试书籍：`testdata/test.epub`、`testdata/test2.epub`
- Yomitan 测试辞典：`testdata/JMdict_english.zip`、`testdata/MK3.zip`、`testdata/freq.zip`、`testdata/pitch.zip`
- 字体导入测试文件：`testdata/KleeOne-SemiBold.ttf`
- 频率和音调辞典验证时，应参考 iOS `DictionaryView` 的 Term/Frequency/Pitch 类型选择逻辑，分别以 Frequency 和 Pitch 类型导入；不要把 meta dictionaries 当成 Term 兜底导入。
- 调试导入流程时，应通过 Android 系统文件选择器选择 `testdata` 中的文件；需要命令行辅助时，先把样本推送到模拟器 Downloads，再通过 DocumentsUI 选择。

## 集成注意事项

- Anki 行为不能直接照搬 iOS。iOS app 使用 AnkiMobile x-callback 和 AnkiConnect 风格路径；Android 实现前必须调查 AnkiDroid API、intent 或 Android 可用的 AnkiConnect 路径。
- Google Drive sync 必须使用 Android 合适的 Google Sign-In/OAuth/Drive API，不要直接复用 iOS token/keychain 思路。
- Audio 和 Sasayaki 播放切片开始时，应使用 AndroidX Media3/ExoPlayer 做原型验证。

## 许可证

- 本项目采用 GPLv3。保留根目录 `LICENSE`。
- 添加面向发布的元数据时，应保留 Hoshi Reader 和 GPLv3 `hoshidicts` 的许可证/版权说明。
- 添加第三方依赖前，先检查许可证。

## 验证

声明实现完成前，运行相关 Gradle 检查，通常是：

```bash
./gradlew test
./gradlew assembleDebug
```

如果修改影响资源、manifest、UI 或打包，运行 Android lint：

```bash
./gradlew lint
```
