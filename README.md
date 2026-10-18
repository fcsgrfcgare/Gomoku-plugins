# Gomoku（五子棋）· Paper 1.20.4

一个面向 **Paper 1.20.4** 的 Minecraft 五子棋小游戏插件：每个房间拥有独立虚空世界，支持房间管理、棋盘扫描、AI、战绩、棋子商店、计分板、整世界备份与重置。

**当前版本：1.1.4**

[使用说明 / Documentation](MODRINTH_DESCRIPTION.md)（English first, 中文见下方）

---

## 功能

- 每个房间独立虚空世界，异步分批建造棋盘
- 木斧自动识别手动搭建的棋盘
- 15×15 / 其他尺寸、计时、观战、AI 对战
- 玩家战绩、等级、积分与棋子商店
- 大厅/对局/观战三套计分板布局，支持 PlaceholderAPI
- 房间世界整世界备份：`/gomoku admin save` 备份，对局结束后自动恢复，清除 TNT、庆祝方块与棋子
- 中文 / 英文双语，`locale: en` 时消息、配置注释、房间棋子默认名自动切换
- SQLite / JSON 战绩存储，SQLite 驱动已内置
- 可配置的赢家庆祝（TNT / 其他实体或方块 / 四角烟花及生成间隔）

## 环境

- Paper 1.20.4
- Java 17+
- 可选：PlaceholderAPI、Multiverse-Core

## 构建

```bash
./gradlew build
```

产物：`build/libs/Gomoku.jar`（已内置 Gson 与 SQLite JDBC，无需服务器再下载依赖）。

## 安装

1. 将 `Gomoku.jar` 放入 `plugins/`；
2. 启动服务器；
3. 在游戏内执行 `/gomoku setlobby` 设置大厅；
4. 用 `/gomoku admin add <房间>`、`/gomoku admin build <房间>`、`/gomoku admin save <房间>` 创建并保存房间。

详细流程见 [使用说明](MODRINTH_DESCRIPTION.md)（英文在上，中文在下）。

## 常用命令

| 命令 | 说明 |
|---|---|
| `/gomoku gui` | 打开房间列表界面（所有玩家） |
| `/gomoku pieceshop` | 打开棋子商店界面（所有玩家） |
| `/gomoku join <房间\|auto>` | 加入 / 自动匹配 |
| `/gomoku spectate <房间>` | 观战 |
| `/gomoku stats [玩家]` | 查看战绩 |
| `/gomoku admin save <房间>` | 保存房间并整世界备份 |
| `/gomoku admin reload` | 重载语言、配置、房间等 |

## 配置

- `config.yml`：语言、大厅、数据库、保护、庆祝、快捷栏开关等；
- `config_en.yml`：英文注释模板；
- `pieces.yml` / `pieces_en.yml`：棋子商店；
- `languages/zh_cn.yml` / `en.yml`：消息与计分板；
- 配置注释随 `locale` 自动中英切换，配置值不变，切换前备份为 `*.bak`。

大厅快捷栏物品可以分别关闭：

```yaml
hotbar:
  menu:
    enabled: true   # false = 不显示房间菜单物品
    slot: 0
  shop:
    enabled: true   # false = 不显示棋子商店物品
    slot: 7
```

## 目录说明

- `src/main/java`：插件源码
- `src/main/resources`：默认配置、语言文件、`plugin.yml`
- `MODRINTH_DESCRIPTION.md`：完整使用说明（上方英文，下方中文）
- `LICENSE`：AGPL-3.0
- `THIRD_PARTY_NOTICES.md`：内置依赖许可说明

> 本仓库不包含测试服务器目录、世界数据、第三方引擎源码或本地构建产物。

## 许可证

本项目使用 [GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）。

https://modrinth.com/plugin/gomoku
