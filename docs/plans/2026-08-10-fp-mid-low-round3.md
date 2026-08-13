# 2026-08-10 中低危误报修复实施记录（第三轮）

## 变更清单

| # | 项 | 修复 | 文件 |
|---|----|------|------|
| 5 | 活塞推人 Rise 误报 | `rise.max-vertical: 0.9→1.25`（活塞推 1.0<1.25）+ 单 tick 脉冲豁免（`lastRiseOver`：首次超限只记录，**连续** 2 tick 超限才 bump） | FlyCheck.java、config.yml |
| 6 | Criticals 梯战/顶点攻击误报 | 悬停攻击与挂梯蹲守在纯运动层面不可区分 → `criticals.enabled: false`（实现保留，可随时开启） | config.yml |
| 7 | 低 TPS 位移放大（Speed/MovePlace/Rise/Criticals） | MovementTracker 时间归一：`motionY/distanceXZ × 50ms/elapsed`（elapsed clip [5,250]ms）——TPS 掉档位移还原为 50ms 基准，一处修复全部 | MovementTracker.java |
| 8 | 实体快照 0.5s 滞后（Reach/Angle 错位） | `entity-snapshot-interval-ticks: 10→4`（250ms），纯 config | config.yml |
| 9 | Velocity 站立击退顶墙/地形吸收垂直 | 地面分支新增：实际水平位移 ≥ 期望水平×0.5（击退被水平吃掉=地形吸收）→ 豁免"no knockback taken"；新增 `VelocityState.expectedHorizontal()` | VelocityCheck.java、VelocityState.java、config.yml |

## 未改动项及理由

- **#10 跨世界/重生突变**：已被第一轮突变自愈覆盖（位移>3.0/2.5 自动豁免）
- **#11 Rotation 旋转楼梯**：塔式旋转视角必跟随放置方向，连续 5 次背离不可能由合法操作达成；宏搭路（视角无关联）保留检测

## 验证

- mvn package 通过，`target\YCBR.jar` 69,505B @ 22:08:04
- 待服务器实测：活塞电梯乘坐、挂梯攻击、10 TPS 压测下疾跑/搭路、被击退顶墙边、快速点击搭路

## 风险

- 时间归一的 250ms clip：GC 停顿/卡顿 >250ms 仍按 250ms 计（最坏情形 = 位移×0.2 低估，向放行方向偏移，安全）
- 快速飞行宏 0.9-1.25 blocks/tick 档位漏检（1.8.9 此档飞行宏稀有，可接受）