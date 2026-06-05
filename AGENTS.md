# Hoshi Reader Android Agent 指南

Hoshi Reader Android 是 Hoshi Reader 的 Android/Kotlin/Jetpack Compose 原生复刻项目。目标是按聚焦的垂直切片复刻 iOS 用户可见行为。

## 工作原则

- iOS 用户可见行为和 UI 是唯一真源。开始功能切片或行为修复前，先查看 `reference/Hoshi-Reader-iOS` 中的对应实现，并总结交互、状态流和边界行为。
- 修复问题时不要叠补丁。先对齐 iOS 行为和状态流；只有平台差异确实需要时，才加入最小 Android 侧适配。
- 涉及 Android 平台能力、权限、SAF、WebView、Media3、WorkManager、Google/Jetpack API、打包、安装或后台行为时，优先查 Android/Google/Jetpack 官方文档确认当前推荐做法。

## 架构基线

### Android 官方推荐

- UI：Jetpack Compose 和 Material 3。
- Navigation：使用当前 Navigation3 typed route/back-stack 模式，接入 `AppShell`、`NavDisplay` 和 route key。
- State：ViewModel 暴露不可变 UI state，并通过 `StateFlow` 推送；屏幕 UI 按 state down / events up 组织。
- Data：repository 负责文件、数据库、辞典、EPUB、网络，以及阻塞工作的 dispatcher 边界；异步使用 Kotlin coroutines，避免阻塞主线程。
- DI：新增依赖优先通过构造函数注入，不要在 Composable 中扩散对象图创建逻辑；Hilt 迁移目标见 `docs/ARCHITECTURE_REFACTORING.md`。
- Platform：Android API 按平台语义和官方文档选择，不机械映射 iOS API。
- Settings：小型设置和偏好使用 DataStore-backed repository，不在 Composable 中直接读写 DataStore。
- Background work：需要跨进程、重启或离开可见状态后仍可靠执行的任务使用 WorkManager。
- EPUB import：通过 Android Storage Access Framework 导入到 app-specific storage。

### Hoshi 项目约束

- JSON：Kotlin Serialization 或 Moshi。
- Storage：已有 iOS 兼容 sidecar JSON 必须保持兼容。
- Reader：保留 WebView 阅读和查词；本地 WebView 资源优先使用 `WebViewAssetLoader` 或仓库已有安全加载路径；不要启用宽泛 file URL 访问，例如 `allowUniversalAccessFromFileURLs`。
- Reader JS/CSS：不要新增大段 Kotlin 字符串脚本。长期 JS/CSS 应放入独立 web asset 或专门资源边界；Kotlin 侧只保留小型 typed command、参数转义和桥接调用。

## 真源文档

- `docs/VALIDATION.md`：必跑构建/测试命令、模拟器数据安全规则、测试数据和长期有效的手工验证矩阵。
- `docs/CHANGELOG.md`：只记录用户可见 App 变化。用户可见变更需要更新 `[Unreleased]`。
- `docs/ARCHITECTURE.md`：当前 Android 架构事实。
- `docs/ARCHITECTURE_REFACTORING.md`：未来架构债务和重构方向。
- `docs/IOS_UPSTREAM_SYNC_QUEUE.md`：当前 iOS upstream 对齐队列。

只有任务改变了对应文档的真源内容时，才更新该文档。

## 经验沉淀

- 如果 agent 犯错后定位到已验证的正确做法，并且该问题可能在未来新会话中复发，应把最小可执行规则沉淀到对应真源文档。
- 需要所有会话常驻的仓库级规则才写入 `AGENTS.md`；验证步骤写入 `docs/VALIDATION.md`；当前架构事实写入 `docs/ARCHITECTURE.md`；未来重构方向写入 `docs/ARCHITECTURE_REFACTORING.md`。
- 沉淀内容必须具体、可执行、低歧义；不要写一次性调查过程、长日志、任务状态、临时 workaround 或只对当前问题成立的细节。
- 沉淀前先确认现有文档是否已有等价规则；若已有，更新原规则而不是新增重复条目。

## 用户可见 UI

- 所有用户可见 UI 字符串必须使用 Android 本地化资源。
- Compose 使用 `stringResource()` / `pluralStringResource()`。
- 非 UI 层发出的可见消息应使用 `UiText` 或等价资源引用，不应持有 `Context`。
- 新增或修改可见文案时，同时更新 `app/src/main/res/values/strings.xml` 和 `app/src/main/res/values-zh-rCN/strings.xml`。保持占位符、plural quantity、CDATA/转义和 `translatable="false"` 语义一致。
- 新增或替换图标时使用 Material Icons、Material vector asset 或明确的品牌资产；不要手绘、自造或临时拼接图标。
- 用户可见错误应通过既有弹窗、Snackbar 或明确错误状态展示；不要把原始异常文本直接渲染到主 UI 内容里。

## 领域边界

- 领域事实和当前模块边界以 `docs/ARCHITECTURE.md` 为准；未来拆分方向以 `docs/ARCHITECTURE_REFACTORING.md` 为准。
- 改动 dictionary、reader、parser、Anki、sync、audio 或 Sasayaki 前，先查对应当前架构边界；除非先记录缺口，否则不要绕过既有 bridge、repository、backend 或 controller 边界。
- Reader、parser、sync、audio 等用户可见行为先对齐 iOS；Android 平台机制使用 Android/Google/Jetpack 官方做法。

## 测试与提交

- 声明实现完成前，按 `docs/VALIDATION.md` 验证。
- 除非用户明确允许一次性设备，否则不要运行会清除、重装或卸载 app 数据的 connected Android instrumentation task。
- 单元测试 fixture 不要依赖被 `.gitignore` 忽略的 `testdata/` 本地文件；CI 需要的样本必须 tracked，或由测试自行构造。
- 优先写行为、API、state-flow 测试，不写生产源码字符串实现细节测试。只有 manifest、resources、Gradle、权限或 provider 声明等结构化配置，才允许在解析结构后断言。
- Commit message 使用 Conventional Commits。
- 完成开发并通过必要验证后，自行提交本次任务相关改动；用户明确要求不提交或保留未提交改动时才不要提交。
- Changelog 只记录普通用户可感知的 App 变化；不要记录 CI、agent workflow、构建脚本、依赖管理或内部重构。
- `[Unreleased]` 中未发布功能的后续调整应合并到原 Added/Changed 条目，只有已发布版本中用户可遇到的问题才写 Fixed。
- 如果实现或修复 GitHub issue，用户可见时在 changelog 条目中引用 issue，并在 commit message 中使用 closing keyword。
- 小型低风险 issue 修复可直接在 `main` 完成；较大功能、跨模块重构或高风险变更使用 `codex/` 分支。
