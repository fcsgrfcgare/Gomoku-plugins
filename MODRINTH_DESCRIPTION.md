Gomoku（五子棋）是一款面向 **Paper 1.20.4** 的 Minecraft 小游戏插件。玩家在各自房间的虚空世界中、在自定义搭建的棋盘上进行经典的五子连珠对弈。

**当前版本：1.1.4**

## 功能特性

- 每个房间拥有独立的虚空世界，棋盘异步分批生成 —— 不会造成主线程卡顿
- 羊毛边框预览系统（异步、区块安全）
- 五连获胜判定，每手统一计时（默认 60 秒，可配置）
- 轮到的玩家会看到绿色「该你了」标题，以及动作栏中的实时倒计时
- 超时则该局作废 —— 双方都不得分
- 对局中死亡会在配置的大厅处重生
- 玩家战绩：胜场 / 负场 / 平局 / 积分 / 胜率 / 等级
- 双语支持：中文（zh_cn）与英文（en），语言文件带版本号并可自动刷新
- 侧边栏计分板，大厅与对局使用不同布局，完全可配置且由占位符驱动
- 提供 PlaceholderAPI 扩展，可用于 TAB、计分板、全息影像等
- 兼容 Multiverse-Core，便于世界管理
- 红石物品用于取消匹配，支持观战模式

## 配置流程

所有房间相关命令都位于 `/gomoku admin` 之下。

1. `/gomoku setlobby` —— 设置主大厅。同时会把该位置同步为所在世界的原版重生点。执行其他任何命令前必须先完成此步。
2. `/gomoku admin add <room>` —— 创建一个虚空世界并将其注册为房间（会将你传送进去，并切换到创造模式）
3. `/gomoku admin playerspawn` —— 站在玩家应当出生的位置执行此命令（必须在房间世界内）
4. `/gomoku admin dish <size>` —— 在将要建造棋盘的位置显示羊毛边框预览（15–64，间隔 3）
5. `/gomoku admin fill <line> <fill> <border>` —— 在预览边框内生成棋盘。每次建造都会连同 XYZ 坐标和一个创建编号（1、2、3……）一起缓存，因此放错位置的棋盘可以用 `/gomoku admin delete <no>` 删除 —— 即使删除了前面的编号，其余编号也保持稳定。
6. `/gomoku admin check <black> <white>` —— 设置棋子名称，并从快捷栏第 1–2 格读取材质
7. `/gomoku admin save <room>` —— 对玩家开放该房间（必须在房间世界内）。保存会保留**最后一次**建造的棋盘，并丢弃其余缓存 —— 在此之前，坐标只存在于内存中。

### 自动识别手动搭建的棋盘（木斧）

`/gomoku admin add` 与 `/gomoku admin build` 会清空你的背包，并给你一把**木斧**（第 1 格）。任意木斧都可以 —— 包括从创造模式物品栏中拿出的；当你手持它时，会自动打上用法说明。

| 输入 | 效果 |
|-------|--------|
| 手持木斧 | 动作栏显示用法；禁止破坏方块和方块交互 |
| **左键点击**某格中间的填充方块 | 识别整块棋盘并写入房间 |
| **右键** | 取消（同时取消进行中的 `admin dish` 预览） |

识别会从棋盘层向外逐层搜索，直到遇到空气，然后测量网格线之间的间距，并将其换算为房间的 `size` / `interval` / `line` / `fill` / `center` / `boardY`。扫描成功后，只需设置玩家出生点并保存即可。

这个最初版本的限制：棋盘必须是虚空中独立、正方形、单层的结构，每格为**统一的 3×3 填充**，且格子之间有网格线 —— 即格子宽度 ≥ 3（`interval` 为 3 或 5）。用 `admin fill` 且 `interval 1`（1 格宽的格子）生成的棋盘无法通过这种方式识别。

## 命令

### 管理员（`gomoku.admin`）

| 命令 | 说明 |
|---------|-------------|
| `/gomoku setlobby` | 将大厅出生点设置在你当前的位置（同时同步该世界的重生点） |
| `/gomoku admin add <room>` | 新建一个带独立虚空世界的房间 |
| `/gomoku admin build <room>` | 进入房间编辑模式（清空背包、发放木斧、传送进入、停用该房间），并打印就绪报告 |
| `/gomoku admin dish <size>` | 羊毛边框预览（gap=3，异步且区块安全） |
| `/gomoku admin fill <line> <fill> <border>` | 在预览边框内建造棋盘（异步分批） |
| `/gomoku admin check <black> <white>` | 设置棋子名称 + 从快捷栏第 1–2 格读取材质 |
| `/gomoku admin delete [no\|list]` | 删除缓存的棋盘建造记录（默认删除最新的）；`list` 列出全部记录 |
| `/gomoku admin playerspawn` | 设置玩家出生点（必须在房间世界内） |
| `/gomoku admin save <room>` | 对玩家开放该房间（必须在房间世界内），并把整个世界备份到 `plugins/Gomoku/backups/<room>` |
| `/gomoku admin clear <room>` | 删除房间及其世界备份 |
| `/gomoku admin reload` | 热重载语言、房间、大厅、战绩与配置（含注释语言） |

### 玩家（`gomoku.use`，默认 true）

| 命令 | 说明 |
|---------|-------------|
| `/gomoku join <room>` | 加入房间 |
| `/gomoku join auto` | 自动匹配（优先级：等待中的房间 > 空闲房间） |
| `/gomoku spectate <room>` | 观战当前正在对局的房间（若尚未开局则报错） |
| `/gomoku leave` | 退出当前对局或停止观战 |
| `/gomoku stats [player]` | 查看战绩 |
| `/gomoku gui` | 打开房间列表界面（所有玩家可用） |
| `/gomoku pieceshop` | 打开棋子商店界面（所有玩家可用） |
| `/gomoku help` | 显示帮助（管理员部分仅对 `gomoku.admin` 可见）—— 输出与直接输入 `/gomoku` 相同 |

### 限制规则

| 规则 | 说明 |
|------|-------------|
| 必须先设大厅 | 除 `setlobby` 和 `help` 外，所有命令都要求先设置大厅 |
| 禁止在大厅建造 | `add` 与 `build` 不能以大厅世界为目标 |
| 禁止在大厅编辑棋盘 | `dish` / `fill` / `check` / `delete` 不能在大厅世界中使用 |
| 禁止在大厅设出生点 | `playerspawn` 不能在大厅世界中使用 |
| 禁止删除大厅 | `clear` 不能删除大厅世界 |
| 在房间内保存 | `save` 必须在房间自己的世界中使用 |
| 先保存再新建 | 如果正站在一个未保存的房间世界中，需先 `save` 该房间，再 `add` 新房间 |

## 玩法

- 瞄准棋盘格子并左键点击即可落子。
- 双方玩家在出生点都会被设为创造模式，快捷栏装满各自棋子的方块。
- 每一手都有相同的思考时间（`turn-seconds`，默认 60 秒）。轮到的玩家会看到绿色**「该你了」**标题，以及快捷栏上方动作栏中不断减少的倒计时。
- **时间耗尽则该局作废：双方都不计分**（不算胜、不算负、也不算平局）。
- 前 5 手不进行胜负判定（这么早不可能出现五连）。
- 棋盘下满则为平局。积分为 `胜场 × 3 + 平局`。
- 观战者通过 `/gomoku spectate <room>` 加入 —— 只有真正处于对局中的房间才接受观战，否则会提示「未在对局中」。观战者会以旁观模式进入该房间。
- 一局结束时，房间内的所有人 —— 包括双方玩家、所有观战者，以及仍站在该房间世界中的其他人 —— 都会被传送回大厅。
- 快捷栏中的红石物品可取消匹配。
- 在房间世界或大厅世界中死亡，都会在配置的大厅处重生。

## 配置

`plugins/Gomoku/config.yml`：

```yaml
locale: "zh_cn"     # "zh_cn" 或 "en"
turn-seconds: 60    # 每一手的时间预算
lobby:
  set: true         # 由 /gomoku setlobby 自动写入
  world: world
  x: -36.5
  y: 76.0
  z: -23.5
  yaw: 0.0
  pitch: 0.0
```

大厅快捷栏物品可以分别开关：

```yaml
hotbar:
  menu:
    enabled: true   # false = 不显示房间菜单物品（已发放的也会移除）
    slot: 0
  shop:
    enabled: true   # false = 不显示棋子商店物品（已发放的也会移除）
    slot: 7
```

`turn-seconds` 会在每一手开始时重新读取，因此修改后甚至不需要执行 `/gomoku admin reload`。

`config.yml` 的注释会随 `locale` 自动在中文/英文之间切换，配置值保持不变；启动和 `/gomoku admin reload` 时会检查缺失或类型错误的配置项并尝试补默认值。

计分板布局**不在**这里 —— 它们位于语言文件中（见下文）。

## 计分板

为每位玩家显示侧边栏，共三套布局：未在对局中时使用 **lobby**，对局进行中使用 **game**，观战时使用 **spectator**。这些布局位于**语言文件**（`languages/zh_cn.yml`、`languages/en.yml`）的 `scoreboard:` 段下，因此每种语言都可以带自己的文案，内容也会随 `lang-version` 刷新一起更新。

- `scoreboard.enabled` —— 设为 `false` 将完全禁用侧边栏（玩家恢复使用原版计分板）
- `scoreboard.update-interval` —— 刷新间隔（单位：tick），默认 `20`（1 秒）
- `scoreboard.<layout>.title` —— 侧边栏标题（最多 32 个字符）；`<layout>` 为 `lobby`、`game` 或 `spectator`
- `scoreboard.<layout>.lines` —— 各行内容，自上而下；`""` 渲染为空白行（每行最多 64 个字符）

缺失的键会回退到内置默认值，因此手工精简过的语言文件依然可用。仍在 `config.yml` 中保留旧 `scoreboard:` 段的服务器也能继续工作，但一旦语言文件定义了这些键，配置中的副本就会被忽略，并在启动时记录一条警告。

之所以需要观战布局，是因为观战者没有对手 —— 对他们而言 `%gomoku_opponent%` 会返回 `—`。可以改用 `%gomoku_black_player%` / `%gomoku_white_player%` 来列出双方。

每一行都会经过 `%player%` 以及所有 `%gomoku_xxx%` 占位符的替换。PlaceholderAPI **不是必需的** —— 插件自有的占位符由内部解析。如果*确实*安装了 PlaceholderAPI，其他插件（TAB、Vault……）的占位符也会一并解析。

修改布局后只需执行 `/gomoku admin reload`；它会重新读取配置并刷新所有在线玩家。

## 棋子商店

玩家可以花费积分购买黑/白棋子外观的**成对**组合。配置位于单独的 `pieces.yml`（首次启动时生成）。

```yaml
menu:
  slot: 7                # 快捷栏倒数第二格
  material: CHEST
  name: "&e&l棋子商店"
  lore:
    - "&7可用积分: &e{points}"
  size: 27
  title: "&8棋子商店  &7可用: &e{points}"

pieces:
  concrete:
    cost: 20             # 0 = 所有人已拥有
    black:
      material: BLACK_CONCRETE
      name: "&0墨石"
    white:
      material: WHITE_CONCRETE
      name: "&f白瓷"
```

- 一个箱子物品会放在配置的快捷栏格位（默认 `7` = 倒数第二格），点击即可打开商店界面。该物品带有标签，无法移动或丢弃，并且玩家每次回到大厅时都会重新发放。
- 每个条目都是**一对**：`black` 与 `white` 必须同时存在，且必须使用不同材质 —— 重复使用同一种方块的条目会被跳过，并在控制台输出警告。
- 购买需要消耗**积分** = `胜场 × 3 + 平局 − 已花费`。已花费积分按玩家单独记录，因此历史累计的 `%gomoku_score%` 永远不会减少；`%gomoku_points%` 显示当前仍可用的积分。
- 点击已拥有的条目即可免费装备；点击未解锁的条目会一次性完成购买并装备。
- 选定的组合只会在**未固定自身棋子的房间**中用于该玩家的棋子。`/gomoku admin check` 会将房间标记为已固定，而固定过的房间始终优先于商店设置。在此功能之前配置过的房间，会通过其非默认的材质/名称被识别出来。
- 游戏在轮次提示、`%gomoku_turn_piece%` 占位符以及玩家快捷栏物品中显示的，是所选组合的棋子**名称**。
- 玩家的选择保存在 `pieces-data.json` 中；`/gomoku admin reload` 会重新读取这两个文件。

## 语言文件

文件位于 `plugins/Gomoku/languages/` —— 即 `zh_cn.yml` 与 `en.yml`。除所有聊天消息外，它们还包含侧边栏计分板布局。每个文件都有一个 `lang-version`：

- 启动时（以及执行 `/gomoku admin reload` 时），插件会将内置的 `lang-version` 与磁盘上的版本进行比较。
- 如果服务器上的副本较旧，会先备份到 `languages/backup/<name>-v<old>-<timestamp>.yml`，然后替换 —— 这样升级后的文案也能送达那些曾解压过旧版本的服务器。
- 若要保留本地修改，请把你文件中的 `lang-version` 提高到内置值之上。

## 权限

| 权限 | 默认 | 说明 |
|------------|---------|-------------|
| `gomoku.admin` | OP | 所有管理员命令 |
| `gomoku.use` | true | 玩家命令 |

## PlaceholderAPI

### 玩家占位符

| 占位符 | 示例 | 说明 |
|-------------|-----------------------------|-------------|
| `%gomoku_room%` | `a` / `—` | 当前房间名称 |
| `%gomoku_state%` | `WAITING` `PLAYING` `ENDED` | 对局状态 |
| `%gomoku_opponent%` | `player1` / `—` | 对手名称（观战者为 `—`） |
| `%gomoku_color%` | `黑子` `白子` `观战` / `—` | 你的身份（硬编码中文） |
| `%gomoku_black_player%` | `player1` / `—` | 当前对局的黑方 |
| `%gomoku_white_player%` | `player2` / `—` | 当前对局的白方 |
| `%gomoku_turn%` | `player1` / `—` | 当前轮到的玩家 |
| `%gomoku_turn_piece%` | `黑子` / `白子` / `—` | 当前落子方的棋子名称 |
| `%gomoku_timer%` | `43` / `—` | 当前这一手剩余的秒数 |
| `%gomoku_countdown%` | `黑子 player1 剩余43s` | 单行摘要：棋子 + 玩家 + 秒数（格式在 `papi-countdown` 中配置） |
| `%gomoku_board_size%` | `15` / `—` | 棋盘尺寸 |
| `%gomoku_move_count%` | `12` / `—` | 已落子数 |
| `%gomoku_wins%` | `5` | 总胜场 |
| `%gomoku_losses%` | `2` | 总负场 |
| `%gomoku_draws%` | `1` | 总平局 |
| `%gomoku_total_games%` | `8` | 对局总数 |
| `%gomoku_winrate%` | `62.5%` | 胜率 |
| `%gomoku_score%` | `16` | 积分（胜 +3，平 +1） |
| `%gomoku_points%` | `6` | 棋子商店中仍可消费的积分 |
| `%gomoku_level%` | `1` | 玩家等级 |

### 服务器占位符

| 占位符 | 示例 | 说明 |
|-------------|---------|-------------|
| `%gomoku_room_count%` | `3` | 房间总数 |
| `%gomoku_playing_room_count%` | `2` | 当前正在对局的房间数 |
| `%gomoku_enabled_room_count%` | `2` | 数值相同 —— `playing_room_count` 的旧别名 |
| `%gomoku_player_count%` | `4` | 对局中的玩家数 |

## 从源码构建

需要 JDK 17（在 `gradle.properties` 中配置）。

```bash
./gradlew build              # -> build/libs/Gomoku.jar
./gradlew proguard           # -> build/libs/Gomoku-obf.jar（会先执行 build）
./gradlew clean build proguard
```

部署时把 jar 复制到服务器的 `plugins/` 目录并重启即可。只能存在一个 Gomoku jar —— 否则插件名称会冲突。

## 依赖

- **必需：** Paper 1.20.4+
- **可选：** Multiverse-Core（世界管理）
- **可选：** PlaceholderAPI（TAB / HUD / 计分板集成）

## 链接

- [Bilibili](https://space.bilibili.com/3546782107700083)
- QQ群：**1035560684**

## 许可证

本项目使用 MIT License，详见仓库根目录的 LICENSE。


---

# 1.1.3 — 功能总览

## 大厅

进服时**强制清空背包和盔甲**，然后只发两件物品（位置可在 `config.yml` 调）：

```yaml
hotbar:
  menu:                 # 下界之星: 打开房间菜单
    enabled: true
    slot: 0
    material: NETHER_STAR
    name: "&b&l房间菜单"
    lore:
      - "&7左键 / 右键 打开房间列表"
  shop:                 # 箱子: 棋子商店
    enabled: true
    slot: 7
```

- 左键或右键下界之星 → **54 格房间菜单**
  - 空闲房间 = 工作台，点击加入（开始匹配）
  - 对局中房间 = 红石块，点击观战
  - 底部中间铁砧 = 自动匹配
  - 每个房间显示棋盘尺寸和当前人数
- **右键房间 = 直接和 AI 开局**，弹出 9 格等级选择：`空 绿 空 黄 空 橙 空 红 空`

## AI 等级

| 颜色 | 等级 | 后端 |
|---|---|---|
| 绿 | 简单 | 内置 Java AI，40% 不堵你的成五、55% 随机落子 |
| 黄 | 普通 | 内置 AI，25% 从前 5 名候选随机 |
| 橙 | 困难 | 内置 AI，始终最优 |
| 红 | 地狱 | **RapFi 引擎**（需 `pbrain-rapfi-windows-*.exe`，仅 15×15）；未接入时显示灰色羊毛 |

内置 AI 只算已有棋子周围 2 格，按五连/活四/冲四/活三/眠三/活二评分，进攻权重高于防守。

RapFi 走标准 Gomocup 协议（`START` / `BOARD…DONE` / `INFO timeout_turn` / `END`），单步 3 秒上限、20 秒看门狗，失败自动回退内置 AI。

## 匹配

- 左键房间开始匹配，**5 分钟**未等到真人 → 自动转 AI 对局（只对左键生效）
- 匹配中或开局时匹配到真人，计时立刻作废
- 等待匹配时手上有一颗「退出对局」红石，**手持右键**取消匹配

## 对局中

- 第 8 格固定发放带 `gomoku:exit_item` 标签的红石：右键第一次提示确认，**5 秒后**再右键退出；5 秒内点击无效；超过 **10 秒**重新计时
- 手持该红石时不触发射线落子、不能放置方块
- 落子方式：**瞄准格子左键**（520 格射线，命中点需在格子内 0.3 格以上）
- 最近一步棋子四周用**红色羊毛**高亮（同格子内，不会越界）
- 胜负只告诉本人：赢家看到绿色「你赢了」，输家看到红色「你输了」，观战者看中立总结
- 对局结束 / 停服时**棋子层整片清成空气**，棋盘本体保留

## 音效

```yaml
sounds:
  start:
    sound: BLOCK_WOODEN_DOOR_OPEN
    volume: 1.0
    pitch: 1.0
  place:
    sound: BLOCK_STONE_PLACE
    volume: 1.0
    pitch: 0.8      # 0.5 更沉更慢, 2.0 更尖更快; volume 0 = 静音
```

## 建棋盘流程（严格顺序）

```
/gomoku admin add <房间>            建虚空世界并进入编辑模式
/gomoku admin dish 15               生成预览（位置就是最终棋盘位置）
/gomoku admin fill 线 填充 外围      按预览位置铺板，第三个参数是边界，边界及之外不能落子
/gomoku admin playerspawn           站在棋盘外侧设置出生点
/gomoku admin save <房间>           保存并启用（必须带房间名）
```

- `fill` **必须在有预览时执行**，否则提示先 `dish`
- 每次 `fill` 会先清掉这个世界里上一次的棋盘，避免出现多块板
- 开局时插件会从世界**重新扫描井字格**校准尺寸/中心/高度（日志：`Board calibrated for 'x': 15x15 int=3 center=0,6 y=6`），所以配置里的坐标不会影响落子准确性

## 木斧识别（编辑模式）

`admin add` / `admin build` 会清空背包并在第 1 格给一把木斧：

- 左键点**井字线** → 自动扫描整块棋盘（外接框、线间距、网格数），缺失的线自动补齐，结果直接写入房间
- 右键取消（同时取消 `dish` 预览）
- 手持时不破坏方块、不触发方块交互；任意木斧（含创造模式取的）都会被自动打上用法
- 扫描只读已加载区块（不会卡服），半径 240 格，单块棋盘最多 15 万方块

## 命令

| 命令 | 说明 |
|---|---|
| `/gomoku help` | 帮助 |
| `/gomoku setlobby` | 设置大厅 |
| `/gomoku join <房间\|auto>` | 加入房间 / 自动匹配 |
| `/gomoku spectate <房间>` | 观战（房间未开始会提示） |
| `/gomoku leave` | 退出对局 / 取消匹配 |
| `/gomoku stats [玩家]` | 战绩查询 |
| `/gomoku gui` | 打开房间列表界面（所有玩家可用） |
| `/gomoku pieceshop` | 打开棋子商店界面（所有玩家可用） |
| `/gomoku admin add <房间>` | 新建房间世界并进入编辑模式 |
| `/gomoku admin build <房间>` | 进入已有房间的编辑模式 |
| `/gomoku admin dish <大小>` | 生成棋盘预览 |
| `/gomoku admin fill <线> <填充> <外围>` | 按预览位置生成棋盘 |
| `/gomoku admin check <黑子名> <白子名>` | 设置本房间棋子外观 |
| `/gomoku admin delete [编号\|list]` | 删除缓存的棋盘坐标 |
| `/gomoku admin playerspawn` | 设置房间出生点 |
| `/gomoku admin save <房间>` | 保存并启用房间 |
| `/gomoku admin clear <房间>` | 删除房间 |
| `/gomoku admin reload` | 重载语言/棋子/配置 |
| `/gomoku admin debug` | 打印当前状态（locale、文案、房间参数、会话、脚下格子） |

## 权限

| 权限 | 默认 | 说明 |
|---|---|---|
| `gomoku.play` | 所有玩家 | 使用玩家命令 |
| `gomoku.admin` | OP | 管理员命令、木斧识别 |

## 占位符（PlaceholderAPI 可选）

`%gomoku_wins%` `%gomoku_losses%` `%gomoku_draws%` `%gomoku_score%` `%gomoku_points%`
`%gomoku_room%` `%gomoku_state%` `%gomoku_turn%` `%gomoku_opponent%` `%gomoku_black_player%` `%gomoku_white_player%`
`%gomoku_black_score%` `%gomoku_white_score%` `%gomoku_timer%` `%gomoku_countdown%` `%gomoku_turn_piece%`
`%gomoku_spectator%` `%gomoku_spectator_count%` `%gomoku_room_count%` `%gomoku_playing_room_count%`

计分板布局写在语言文件里（`scoreboard:` 段），支持大厅 / 对局 / 观战三套。

---

# 1.1.3 — 功能总览（英文）

> 中文版在上面的章节；下面是同样的内容英文版。
> 上方为中文部分，下面是这段内容的英文翻译。

## 大厅

进入服务器时会**清空整个背包和盔甲**，然后只发放两件物品（格位可在 `config.yml` 中配置）：

```yaml
hotbar:
  menu:                 # 下界之星：打开房间菜单
    enabled: true
    slot: 0
    material: NETHER_STAR
    name: "&b&l房间菜单"
    lore:
      - "&7左键 / 右键 打开房间列表"
  shop:                 # 箱子：棋子商店
    enabled: true
    slot: 7
```

- 左键或右键点击下界之星 → **54 格房间菜单**
  - 空闲房间 = 工作台，点击加入（开始匹配）
  - 对局中的房间 = 红石块，点击观战
  - 底部中间的铁砧 = 自动匹配
  - 每个房间都会显示棋盘尺寸和当前人数
- **右键点击房间 = 直接与 AI 开局**，会弹出一行 9 格选择：`空 绿 空 黄 空 橙 空 红 空`

## AI 等级

| 颜色 | 等级 | 后端 |
|---|---|---|
| 绿 | 简单 | 内置 Java AI：40% 概率不堵你的成五，55% 随机落子 |
| 黄 | 普通 | 内置 AI，25% 概率从前 5 名候选中随机 |
| 橙 | 困难 | 内置 AI，始终最优 |
| 红 | 地狱 | **RapFi 引擎**（需要 `pbrain-rapfi-windows-*.exe`，仅 15×15）；未接入时显示灰色羊毛 |

内置 AI 只考虑已有棋子周围 2 格内的空位，并按五连 / 活四 / 冲四 / 活三 / 眠三 / 活二评分，进攻权重高于防守。

RapFi 使用标准 Gomocup 协议（`START` / `BOARD…DONE` / `INFO timeout_turn` / `END`），单步 3 秒上限、20 秒看门狗，失败时自动回退到内置 AI。

## 匹配

- 左键点击房间开始匹配；若 **5 分钟**内没有真人加入，则自动转为与 AI 对局（仅对左键生效）
- 一旦有真人加入，倒计时立即作废
- 等待期间手中会有一块「退出对局」红石 —— **手持时右键**即可取消匹配

## 对局中

- 第 9 格固定发放带 `gomoku:exit_item` 标签的红石：第一次右键会提示确认，**5 秒后**再右键才退出；5 秒内点击无效；超过 **10 秒**则重新计时
- 手持该红石时不触发射线落子，也不能放置方块
- 落子方式：**瞄准格子并左键点击**（射线 520 格，命中点需位于格子内 0.3 格以上）
- 最近一步棋子的四周会用**红色羊毛**高亮（同格子内，不会越界）
- 胜负只告知本人：赢家看到绿色「你赢了」，输家看到红色「你输了」，观战者则看到中立总结
- 对局结束（或服务器关闭）时，**整个棋子层会被清成空气**，棋盘本体保留

## 音效

```yaml
sounds:
  start:
    sound: BLOCK_WOODEN_DOOR_OPEN
    volume: 1.0
    pitch: 1.0
  place:
    sound: BLOCK_STONE_PLACE
    volume: 1.0
    pitch: 0.8      # 0.5 更沉更慢, 2.0 更尖更快; volume 0 = 静音
```

## 创建棋盘（顺序很重要）

```
/gomoku admin add <room>            建虚空世界并进入编辑模式
/gomoku admin dish 15               生成预览（该位置就是最终棋盘位置）
/gomoku admin fill <line> <fill> <periphery>   按预览位置建造棋盘；第三个参数是边界
/gomoku admin playerspawn           站在棋盘外侧设置玩家出生点
/gomoku admin save <room>           保存并启用（必须带房间名）
```

- `fill` **必须有预览**；没有预览时会拒绝执行，并提示先运行 `dish`
- 每次 `fill` 都会先清除该世界中上一次的棋盘，因此绝不会出现两块棋盘
- 开局时插件会**从世界重新扫描井字格**来校准尺寸 / 中心 / 高度（日志：`Board calibrated for 'x': 15x15 int=3 center=0,6 y=6`），因此过时的坐标不会再导致棋子偏移

## 木斧识别（编辑模式）

`admin add` / `admin build` 会清空你的背包，并在第 1 格放一把木斧：

- 左键点击**井字线** → 自动扫描整块棋盘（外接框、线间距、网格数），缺失的线方块会自动补齐，结果直接写入房间
- 右键取消（同时取消正在进行的 `dish` 预览）
- 手持时不破坏方块、不触发方块交互；任意木斧（包括创造模式取得的）都会被自动打上用法说明
- 扫描只读取已加载的区块（绝不会卡服），半径 240 格，单块棋盘最多 15 万个方块

## 命令

| 命令 | 说明 |
|---|---|
| `/gomoku help` | 帮助 |
| `/gomoku setlobby` | 设置大厅 |
| `/gomoku join <room\|auto>` | 加入房间 / 自动匹配 |
| `/gomoku spectate <room>` | 观战（房间尚未开始时会有提示） |
| `/gomoku leave` | 退出对局 / 取消匹配 |
| `/gomoku stats [player]` | 战绩查询 |
| `/gomoku gui` | Open the room list GUI (all players) |
| `/gomoku pieceshop` | Open the piece shop GUI (all players) |
| `/gomoku admin add <room>` | 新建房间世界并进入编辑模式 |
| `/gomoku admin build <room>` | 进入已有房间的编辑模式 |
| `/gomoku admin dish <size>` | 生成棋盘预览 |
| `/gomoku admin fill <line> <fill> <periphery>` | 按预览位置生成棋盘 |
| `/gomoku admin check <black> <white>` | 设置本房间的棋子外观 |
| `/gomoku admin delete [no\|list]` | 删除缓存的棋盘坐标 |
| `/gomoku admin playerspawn` | 设置房间出生点 |
| `/gomoku admin save <room>` | 保存并启用房间 |
| `/gomoku admin clear <room>` | 删除房间 |
| `/gomoku admin reload` | 重载语言 / 棋子 / 配置 |
| `/gomoku admin debug` | 打印当前状态（locale、文案、房间参数、会话、脚下格子） |

## 权限

| 权限 | 默认 | 说明 |
|---|---|---|
| `gomoku.play` | 所有玩家 | 玩家命令 |
| `gomoku.admin` | OP | 管理员命令、木斧识别 |

## 占位符（PlaceholderAPI 可选）

`%gomoku_wins%` `%gomoku_losses%` `%gomoku_draws%` `%gomoku_score%` `%gomoku_points%`
`%gomoku_room%` `%gomoku_state%` `%gomoku_turn%` `%gomoku_opponent%` `%gomoku_black_player%` `%gomoku_white_player%`
`%gomoku_black_score%` `%gomoku_white_score%` `%gomoku_timer%` `%gomoku_countdown%` `%gomoku_turn_piece%`
`%gomoku_spectator%` `%gomoku_spectator_count%` `%gomoku_room_count%` `%gomoku_playing_room_count%`

计分板布局写在语言文件里（`scoreboard:` 段），支持大厅 / 对局 / 观战三套。
---

# 1.1.4 更新日志

## 新增
- 新增 `/gomoku gui` 与 `/gomoku pieceshop`，所有玩家可打开房间列表和棋子商店界面。
- 大厅快捷栏物品可独立开关显示：`hotbar.menu.enabled`、`hotbar.shop.enabled`；关闭时已发放的物品也会被移除。
- `config.yml` / `pieces.yml` 的注释随 `locale` 自动切换中英文，配置值保持不变；切换前备份为 `config.yml.bak` / `pieces.yml.bak`。
- 启动和 `/gomoku admin reload` 时自动检查配置：缺失或类型错误的键会补默认值并提示。
- 房间世界整世界备份与重置：`/gomoku admin save` 备份到 `plugins/Gomoku/backups/<room>`，对局结束后自动恢复，清除 TNT、庆祝方块、烟花与棋子。
- 赢家庆祝生成间隔：`win-celebration.interval-ticks` 与 `win-celebration.corner-fireworks.interval-ticks`。
- 房间棋子默认名随语言：中文“黑子/白子”，英文“Black Stone/White Stone”。
- 大厅保护 `lobby-protect`：仅管理员+创造模式可破坏/交互；房间沿用原保护逻辑。
- SQLite 本地存储（`storage.type: sqlite`），驱动已内置，无需额外下载。
- 控制台支持英文 `gomoku admin reload`，避免中文乱码。
- 中文/英文配置模板：`config.yml` / `config_en.yml`；棋子模板：`pieces.yml` / `pieces_en.yml`。

## 修复
- 快捷栏“棋子商店 / 房间菜单”物品名不随 `locale` 更新的问题；进服和 reload 会原地重绘已有物品。
- `admin save` 因 `session.lock` 导致整世界备份失败。
- 重置世界时房间内仍有玩家会跳过；现在先把剩余玩家传回大厅再重置。
- 旧配置 `hotbar.menu.name` / `pieces.yml menu.name` 覆盖语言文本的问题。
- 配置注释切换时中英文注释行被错误合并的问题。

## 语言
- `lang-version: 36`，新增 `gui-in-game`，帮助文本加入 `/gomoku gui`、`/gomoku pieceshop`。
