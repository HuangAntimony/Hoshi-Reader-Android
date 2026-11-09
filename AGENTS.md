# Hoshi Reader Android Agent 指南

Hoshi Reader Android 是使用 Kotlin 和 Jetpack Compose 开发的原生 Android 阅读器。
本文件负责 agent 的工作方式和文档维护；代码入口与实现契约见架构文档。

## 工作入口

- 开始任务先检查 Git 状态，保留无关改动。修改 App 代码前，读取 [通用实现契约](docs/ARCHITECTURE.md#common-implementation-contracts)，再按架构索引查相关领域约束、owner、代码与测试，通过对应 owner 实现改动。涉及边界调整时先在当前任务说明。
- 日常开发以本次需求和 Android 架构契约为依据，结合当前代码与测试梳理交互、状态流和边界行为；修复问题先定位根因。
- iOS 功能迁移通过 `sync-ios-upstream` 流程及 `docs/IOS_UPSTREAM_SYNC_QUEUE.md` 推进；迁移和行为对齐任务以 `reference/Hoshi-Reader-iOS` 的对应实现为依据。
- Android 平台能力、权限、SAF、WebView、Media3、WorkManager、Google/Jetpack API、打包、安装和后台机制，优先查官方文档确认当前推荐做法。

## 文档分工

以下是仓库跟踪的文档。每类信息集中在对应文档，其他位置通过链接引用。

| 文档 | 读者与职责 | 更新触发 |
| --- | --- | --- |
| [AGENTS.md](AGENTS.md) | Agent 工作入口、文档治理和仓库级操作规则 | 工作方式、文档分工或维护规则变化 |
| [README.md](README.md) / [README.zh-CN.md](README.zh-CN.md) | 人类的项目介绍、安装与使用入口 | 用户开始使用项目所需的信息变化 |
| [CHANGELOG.md](docs/CHANGELOG.md) / [CHANGELOG_ARCHIVE.md](docs/CHANGELOG_ARCHIVE.md) | 人类可快速浏览的版本变化；archive 保存历史 | 用户可感知的 App 行为变化或正式发布 |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Agent 的当前代码地图、职责和实现契约，包括错误处理、状态/并发、存储、生命周期与安全边界 | owner、跨层约束、数据/API 契约或检索入口变化 |
| [VALIDATION.md](docs/VALIDATION.md) | Agent 的验证入口、设备数据保护、证据判定和跨工具操作步骤 | 符合下方 [Validation 维护规则](#validation-维护规则) 时 |
| [ARCHITECTURE_REFACTORING.md](docs/ARCHITECTURE_REFACTORING.md) | 尚未解决的架构债务与未来方向 | 确认新缺口，或已有目标完成/缩小 |
| [IOS_UPSTREAM_SYNC_QUEUE.md](docs/IOS_UPSTREAM_SYNC_QUEUE.md) | 当前 iOS upstream 对齐队列 | upstream 评估或切片状态变化 |
| [TRANSLATING.md](docs/TRANSLATING.md) | 本地化贡献操作说明 | 翻译流程变化 |

## 文档维护规则

- 按上表的更新触发维护对应文档，优先原位修订、合并重复和删除失效内容。
- 长期信息应具体、已验证且可跨会话复用。局部实现和行为用例在代码与测试中维护；单次调查、执行结果和未验证项留在当前任务或 PR。
- 重构与同步队列保留剩余工作和影响后续决策的取舍，完成项移出队列。
- iOS 同步队列的 `Covered Or No Android Action` 只保留本轮对照结论。每次刷新替换旧记录，待办清空后只保留 Git 历史提示。
- 调整文档结构时同步更新导航和引用；中英文 README 的产品事实保持一致。

### Validation 维护规则

- 仅在可跨任务复用的验证要求发生变化，或遗漏已验证的限制会造成数据风险、无法复现或误判时，更新 `VALIDATION.md`。新增功能、回归测试或跑完一轮验证本身都不触发更新。
- 测试清单、断言、参数、样本规模和输出路径由测试源码或脚本维护。跨工具步骤或安全前置条件确实需要说明时，只写入口、必要步骤和证据判定。单次手工清单、设备型号、截图和跑分留在当前任务或 PR。
- 先找已有规则并原位合并。提交前删掉已被源码覆盖、重复或失效的说明，避免按功能追加验证日志。

### Changelog 写法

- 记录普通用户能感知的 App 变化；每条用一句话说明用户得到什么或哪个问题被修复，同一功能合并表达。
- 用户可见变更更新 `[Unreleased]`。未发布功能的后续调整合并到原 Added/Changed 条目；只有已发布版本中用户可遇到的问题才写 Fixed。
- 实现/修复 issue 且变化对用户可见时，在条目中引用 issue。

## 工作区、验证与提交

- 默认在当前工作区操作；Git worktree 仅在用户明确要求时使用。小型低风险修复可在 `main` 完成；较大功能、跨模块重构或高风险变更使用 `codex/` 分支。
- 计划默认保留在对话或内置计划状态中；用户明确要求时才创建或提交计划文档。
- 声明完成前按 `docs/VALIDATION.md` 执行匹配的检查与设备数据保护要求，并报告结果及未验证项。设备/UI 验证由 agent 使用可用工具执行。
- 完成开发并通过必要验证后，自行提交本次任务相关改动；用户要求不提交或保留未提交改动时除外。Commit message 使用 Conventional Commits；实现或修复 GitHub issue 时使用 closing keyword。
