# YCBR AntiCheat

为 Minecraft 1.8.9 Paper 服务器打造的轻量级反作弊插件，内置登录/注册认证、临时封禁与 DDoS 防护。

## 功能

- **19 项移动/战斗/协议检测**：KillAura、Scaffold、Speed、Velocity、Fly、Criticals、Timer、NoFall、WrongTurn、NoSwing、Blink、Sprint、BadPacket、NoSlow、AutoTool、FastThrow、InstantBow、FastClick、Reach
- **严格模式**：一键切换收紧全部检测阈值（`/ycbr strict on`），严格模式只叠加 timeTest 违规，不叠违规值
- **认证系统**：离线服务器密码注册/登录，正版白名单（premium）免认证
- **临时封禁**：`/timeban` 封禁 1 小时，北京时间显示到期时间
- **DDoS 防护**：连接频率限制与异常参数踢出
- **管理界面**：`/ycbr gui` 图形化开关检测、修改配置
- **反作弊OP**：`/ycbrop` 授予免检测、幽灵观战、人工检测权限
- **实时调试**：`/ycbr debug` 查看玩家 ping/CPS/移动/违规值

## 环境要求

- Paper 1.8.9（Spigot 兼容）
- ProtocolLib（depend）

## 构建

```bash
mvn -q -DskipTests package
```

产物位于 `target/YCBR.jar`。

## 安装

1. 将 `YCBR.jar` 放入 `plugins/` 目录
2. **替换 jar 前必须删除旧的 `plugins/YCBR/config.yml`**（配置结构变更时会自动生成新配置）
3. 重启服务器（配置或插件变更后 `/ycbr reload` 无法重载全部内容，需重启）

## 指令

| 指令 | 说明 |
| --- | --- |
| `/ycbr help` | 查看全部指令帮助（别名 `/yc`、`/ycbrac`） |
| `/ycbr reload` | 重载配置文件 |
| `/ycbr alerts` | 切换反作弊警报开关 |
| `/ycbr gui` | 打开管理界面（检测开关/配置/DDoS） |
| `/ycbr toggle <检测[.子检测]> [on\|off]` | 开关单个检测，如 `/ycbr toggle killaura.angle off` |
| `/ycbr list` | 查看所有检测的开关状态 |
| `/ycbr debug [玩家\|半径]` | 查看玩家实时数据 |
| `/ycbr premium add\|remove\|list <名字>` | 正版白名单管理（免注册/登录） |
| `/ycbr strict [on\|off]` | 严格模式开关 |
| `/timeban <玩家>` | 临时封禁（默认 1 小时） |
| `/untimeban <玩家>` | 解除封禁 |
| `/register <密码> <密码>` | 注册账号（密码 4-32 位） |
| `/login <密码>` | 登录账号 |
| `/ycbrop <玩家\|remove\|list\|gui>` | 反作弊OP管理 |

权限：`ycbr.admin`（默认 OP）、`ycbr.alerts`（接收警报）。

## 配置

所有检测阈值、严格模式开关（`settings.strict-mode`）、认证、DDoS、封禁消息等均可在 `plugins/YCBR/config.yml` 中调整。修改后用 `/ycbr reload` 或重启生效。

## 数据文件

`accounts.yml`（密码哈希）、`bans.yml`（封禁记录）、`sessions.yml`（登录会话）、`botchecks.yml`（机器人验证）存于插件数据目录，请勿外泄。