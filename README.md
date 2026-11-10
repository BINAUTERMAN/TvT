<p align="center">
  <img src="assets/tvt.svg" width="128" height="128" alt="TvT 图标">
</p>

<h1 align="center">TvT</h1>

<p align="center">🎧 一个简洁、专注于听歌的第三方 Android 音乐客户端。</p>

<p align="center">Android 8.0+ · Vue 3 · TypeScript · Java · GPL-2.0-only</p>

TvT 基于酷狗音乐的账号与内容服务，结合 Web 界面和 Android 原生媒体能力，提供音乐发现、搜索、播放、歌词、收藏同步与本地缓存。支持手机和平板，适配浅色与深色外观。

**TvT 是独立开源项目，并非酷狗的官方客户端。**

## 🔧 技术原理

TvT 使用 Vue 3 + TypeScript 构建界面，通过原生桥调用 Java 服务。音乐请求直接访问平台，后台播放、系统媒体控制和本地缓存由 Android 原生层处理；账号令牌在设备本地加密保存。

## 🎵 功能介绍

- 个性推荐、精选歌单与排行榜。
- 歌曲、歌手、专辑、歌单及歌词搜索。
- 后台播放、音质切换、队列管理与断点续播。
- 逐字歌词、平台译文与桌面歌词。
- 云端歌单和喜欢同步、本地音乐与离线缓存。
- 手机和平板适配、深浅主题与应用更新。

## 📖 使用教程

在本仓库 Releases 下载 APK，安装后登录酷狗账号即可使用。

详细操作请查看 [功能使用教程.md](功能使用教程.md)。

## 💡 一起完善 TvT

欢迎带着你的点子来参与，让 TvT 变得更好 ( •̀ ω •́ )✧

- 🐛 **发现问题**：欢迎提交 Issue，附上复现步骤、设备型号和应用版本。
- ✨ **有新想法**：欢迎在 Issues 分享功能建议和使用场景，一起讨论怎么实现。
- 🛠️ **想动手改进**：欢迎提交 PR，修复问题、添加功能或完善文档。

提交前可以先看看已有的 Issues 和 PR；较大的功能改动建议先开 Issue 沟通。

每一条反馈、每一个小修复都很有价值。喜欢 TvT，也欢迎点颗 Star ⭐

## 💜 鸣谢

感谢以下开源项目及其贡献者：

| 项目 | 在 TvT 中的用途 |
| --- | --- |
| [KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi) | 平台协议、签名与音乐接口适配的主要来源，MIT 许可 |
| [AirLyrics](https://github.com/AirLyrics/AirLyrics) | 逐字歌词定位及悬浮窗口相对位置算法移植，MIT 许可 |
| [kgcheckin-enhanced](https://github.com/1153683020/kgcheckin-enhanced) | 会员查询、签到状态与接口检查流程参考，MIT 许可 |

功能与界面设计还参考了 [Metrolist](https://github.com/MetrolistGroup/Metrolist)、[Gramophone](https://github.com/FoedusProgramme/Gramophone)、[Namida](https://github.com/namidaco/namida)、[apple-design-skill](https://github.com/dickwu/apple-design-skill) 和 [Liquid Glass UI Plus](https://github.com/agenticluke/liquid-glass-ui-plus)。

完整第三方许可文本保存在 `web/public/licenses`，也可从应用内“开源许可”查看。

## 📌 免责声明

1. 本项目仅供学习使用，请尊重版权，请勿利用此项目从事商业行为及非法用途!
2. 使用本项目的过程中可能会产生版权数据。对于这些版权数据，本项目不拥有它们的所有权。为了避免侵权，使用者务必在 24 小时内清除使用本项目的过程中所产生的版权数据。
3. 由于使用本项目产生的包括由于本协议或由于使用或无法使用本项目而引起的任何性质的任何直接、间接、特殊、偶然或结果性损害（包括但不限于因商誉损失、停工、计算机故障或故障引起的损害赔偿，或任何及所有其他商业损害或损失）由使用者负责。
4. **禁止在违反当地法律法规的情况下使用本项目。** 对于使用者在明知或不知当地法律法规不允许的情况下使用本项目所造成的任何违法违规行为由使用者承担，本项目不承担由此造成的任何直接、间接、特殊、偶然或结果性责任。
5. 音乐平台不易，请尊重版权，支持正版。
6. 本项目仅用于对技术可行性的探索及研究，不接受任何商业（包括但不限于广告等）合作及捐赠。
7. 如果官方音乐平台觉得本项目不妥，可联系本项目更改或移除。

## 📄 开源协议

项目采用 **GPL-2.0-only**，完整协议见 [LICENSE](LICENSE)。第三方代码、算法及依赖保留各自的许可和版权声明。
