<div align="center">

# Hoshi Reader Android

![Platform](https://img.shields.io/badge/platform-Android-lightgrey)
![License](https://img.shields.io/badge/license-GPLv3-blue)
[![Download](https://img.shields.io/github/downloads/HuangAntimony/Hoshi-Reader-Android/total?label=download)](https://github.com/HuangAntimony/Hoshi-Reader-Android/releases)
[![Latest download](https://img.shields.io/github/downloads/HuangAntimony/Hoshi-Reader-Android/latest/total?label=latest%20download)](https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/latest)

[English](README.md) | **简体中文**

Hoshi Reader Android 是一款面向 Android 日语沉浸学习的轻量 EPUB 阅读器 App，支持 Yomitan 辞典查词、Anki 制卡、有声书跟读，以及墨水屏专用的模式选项。

本项目是 [Hoshi Reader](https://github.com/Manhhao/Hoshi-Reader) 的 Android 原生复刻版。

<table>
  <tr>
    <td><img src="docs/images/readme/bookshelf.jpg" alt="书架" width="100%"></td>
    <td><img src="docs/images/readme/reader-lookup-popup.jpg" alt="阅读器查词弹窗" width="100%"></td>
    <td><img src="docs/images/readme/reader-dark-theme.jpg" alt="深色阅读器" width="100%"></td>
    <td><img src="docs/images/readme/reader-eink-mode.jpg" alt="墨水屏阅读器" width="100%"></td>
  </tr>
  <tr>
    <td><img src="docs/images/readme/sasayaki-audiobook.jpg" alt="Sasayaki 有声书" width="100%"></td>
    <td><img src="docs/images/readme/reader-statistics.jpg" alt="阅读统计" width="100%"></td>
    <td><img src="docs/images/readme/reader-highlights.jpg" alt="阅读器高亮" width="100%"></td>
    <td><img src="docs/images/readme/dictionary-recursive-lookup.jpg" alt="辞典递归查词" width="100%"></td>
  </tr>
  <tr>
    <td><img src="docs/images/readme/reader-appearance-settings.jpg" alt="阅读器外观设置" width="100%"></td>
    <td><img src="docs/images/readme/dictionary-management.jpg" alt="辞典管理" width="100%"></td>
    <td><img src="docs/images/readme/anki-card-settings.jpg" alt="Anki 制卡设置" width="100%"></td>
    <td><img src="docs/images/readme/sync-settings.jpg" alt="同步设置" width="100%"></td>
  </tr>
</table>

</div>

## 功能

### 书架

- 导入单本或多本 EPUB，并在书架查看阅读进度。
- 使用自定义书架整理书籍。

### 阅读

- 以竖排或横排阅读日文书籍，并在分页和连续滚动之间切换。
- 自定义主题、字体、间距。
- 支持专注模式、音量键翻页和墨水屏显示选项。

### 查词

- 导入和管理 Yomitan 辞典，支持一键更新。
- 在阅读器中点击文本查词，在辞典页搜索，或从其他 Android App 选中文本查词。
- 点击释义中的生词进行递归查询。
- 注入自定义 CSS 样式。
- 使用在线或本地单词音频。

### 标注与统计

- 阅读时添加五色高亮标注，并随时进行跳转。
- 阅读数据统计，包括阅读的字符数、时长、阅读速度，可在阅读时实时显示。

### Anki 制卡

- 通过 AnkiDroid 或 AnkiConnect 制卡。
- 使用 [Lapis](https://github.com/donkuri/lapis) 兼容字段、支持重复检查。

### 有声书跟读

- 将有声书字幕文件匹配到书籍以高亮当前句子。
- 跟随高亮自动翻页。
- 控制播放速度、跳转动作和 Android 系统媒体控制。

### 数据同步与迁移

- 通过 Google Drive 同步阅读进度和统计数据，兼容 ッツ Reader。
- 使用 `.hoshi` 归档备份或恢复书籍和辞典，兼容 Hoshi Reader iOS。

## 下载 Hoshi Reader Android APK

请从 [GitHub Releases](https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/latest) 下载最新的 Hoshi Reader Android APK。

Hoshi Reader Android 需要 Android 9 或更高版本。

## 使用指南

App 交互指南可以参考这篇 Hoshi Reader iOS 使用文档：[Hoshi Reader 使用文档](https://my.feishu.cn/wiki/SXzUw9F6AiPw99kdzwac5Cv8n0f)。

日语学习方法可以参考这篇基于二语习得理论的指南：[基于二语习得理论的日语学习指南](https://my.feishu.cn/wiki/YeOSwsG7giLuQxkcDFscUXVZn2f)。

## 开发状态

已完成与 iOS 版的功能对齐，当前的开发重点是打磨 UI 与用户交互。

已发布的用户可见变化见 [docs/CHANGELOG.md](docs/CHANGELOG.md)。

## 功能请求

通用功能请求请优先提交到 iOS 仓库。

若为 Android 特有功能，或因 iOS 系统限制无法实现的功能（例如墨水屏主题、音量键翻页等），请在本仓库提交 Issue。

## 隐私与数据

Hoshi Reader Android 会把导入的书籍、辞典、字体、有声书数据、阅读进度、高亮、统计和设置保存在 App 本地存储中。

Google Drive 同步使用由用户配置的 Google Cloud OAuth 设备代码流程。Anki 制卡会与 AnkiDroid 或已配置的 AnkiConnect 地址通信。更新检查会读取 GitHub Release 元数据。

## 鸣谢

Hoshi Reader Android 基于以下生态：

- [Hoshi Reader iOS](https://github.com/Manhhao/Hoshi-Reader)：参考实现。
- [hoshidicts](https://github.com/Manhhao/hoshidicts) 及 [hoshidicts-kotlin-bridge](https://github.com/Manhhao/hoshidicts-kotlin-bridge)：Yomitan 辞典支持。
- [Yomitan](https://github.com/yomidevs/yomitan)：辞典格式和查词体验参考。
- [AnkiDroid](https://github.com/ankidroid/Anki-Android)：Android 制卡集成。
- [Ankiconnect Android](https://github.com/KamWithK/AnkiconnectAndroid)：本地音频行为和 AnkiDroid 重复范围 / checksum 查询参考。
- [ッツ Ebook Reader](https://github.com/ttu-ttu/ebook-reader)：阅读器、统计和同步兼容性参考。

## 许可证

本项目基于 GNU General Public License v3.0 发布。详情见 [LICENSE](LICENSE)。

## Star History

如果 Hoshi Reader Android 对你的阅读有帮助，欢迎顺手点一个 Star。

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=HuangAntimony/Hoshi-Reader-Android&type=date&theme=dark&legend=top-left" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=HuangAntimony/Hoshi-Reader-Android&type=date&legend=top-left" />
    <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=HuangAntimony/Hoshi-Reader-Android&type=date&legend=top-left" />
  </picture>
</p>
