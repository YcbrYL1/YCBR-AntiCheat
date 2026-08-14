# YCBR AntiCheat

为 Minecraft 1.8.8 Paper 服务端打造的现代化高精度轻量级反作弊插件（Java 8）。

物理引擎预测 + 事务时钟 + 战斗统计/ML + 跨检测融合，覆盖移动 / 协议 / 战斗三大类检测。

## 核心特性

### 移动检测（物理引擎）

- 基于 1.8.8 原版 NMS 物理公式的预测引擎：重力、摩擦、液体、蜘蛛网、梯子、疾跑、药水效果全复刻
- 碰撞盒级 Simulation 检测：Speed / Fly / NoFall / NoSlow
- 阶梯 / 半砖 / 灵魂沙等已知豁免注册表，复杂地形低误判

### 协议与时间线

- Transaction 事务时钟：精确测量客户端 RTT 并与服务端 tick 对齐
- Timer（TPS 归一化）/ Blink（事务 pong 判定）/ Sprint（6 类状态合规）/ WrongTurn / BadPacket / FastThrow

### 战斗检测

- KillAura：16+ 启发式 + Aim 统计（熵 / KS / IQR / 峰度 / Z-score）+ SimpleMLP + 交叉验证门控
- Reach：多帧射线-AABB + 实时取消不可能攻击
- AutoClicker / FastClick / Criticals / NoSwing / AutoTool / InstantBow / Scaffold

### 跨检测融合

- Improbable：各检测亚阈值小违规喂入统一频率桶，短窗 + 长窗同时超阈且覆盖多类别才升级——抓"每类只犯一点错"的持续作弊器

### 管理与防护

- 11 页面图形化管理界面（玩家详情 / 检测配置 / 违规日志 / DDoS / 融合分析）
- DDoS 连接级防护、全服误报风暴熔断
- 登录 / 注册认证、临时封禁、幽灵观战、人工检测
- 130+ 单元测试覆盖物理引擎与核心逻辑

## 环境要求

- Paper 1.8.8（Spigot 兼容）
- ProtocolLib 5.0.0（depend）

## 构建

```bash
mvn -q -DskipTests package
```

产物位于 `target/YCBR.jar`。

## 安装

1. 将 `YCBR.jar` 放入 `plugins/` 目录
2. 替换 jar 前删除旧的 `plugins/YCBR/config.yml`（配置结构变更时自动生成新配置）
3. 重启服务器（配置或插件变更后 `/ycbr reload` 无法重载全部内容，需重启）

## 指令

| 指令 | 说明 |
| --- | --- |
| `/ycbr help` | 查看全部指令帮助（别名 `/yc`、`/ycbrac`） |
| `/ycbr reload` | 重载配置文件 |
| `/ycbr alerts` | 切换反作弊警报开关 |
| `/ycbr gui` | 打开管理界面（检测开关 / 配置 / DDoS / 融合分析） |
| `/ycbr toggle <检测[.子检测]> [on\|off]` | 开关单个检测，如 `/ycbr toggle killaura.angle off` |
| `/ycbr list` | 查看所有检测的开关状态 |
| `/ycbr debug [玩家\|半径]` | 查看玩家实时数据 |
| `/ycbr premium add\|remove\|list <名字>` | 正版白名单管理（免注册 / 登录） |
| `/ycbr strict [on\|off]` | 严格模式开关 |
| `/timeban <玩家>` | 临时封禁（默认 1 小时） |
| `/untimeban <玩家>` | 解除封禁 |
| `/register <密码> <密码>` | 注册账号（密码 4-32 位） |
| `/login <密码>` | 登录账号 |
| `/ycbrop <玩家\|remove\|list\|gui>` | 反作弊 OP 管理 |

权限：`ycbr.admin`（默认 OP）、`ycbr.alerts`（接收警报）。

## 配置

所有检测阈值、严格模式开关（`settings.strict-mode`）、认证、DDoS、封禁消息等均可在 `plugins/YCBR/config.yml` 中调整。修改后用 `/ycbr reload` 或重启生效。

## 数据文件

`accounts.yml`（密码哈希）、`bans.yml`（封禁记录）、`sessions.yml`（登录会话）、`botchecks.yml`（机器人验证）存于插件数据目录，请勿外泄。

## 许可证

见 [LICENSE](LICENSE)。
