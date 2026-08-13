# 移动类预测引擎（SimulationCheck）设计说明

## 背景与目标

YCBR-AC 现有 Speed/Fly 检测使用经验公式（magic-number 容差 + 手工列举加成），对新介质/版本/组合容易失准。借鉴 Grim 的"物理重演"思路，引入基于 1.8.8/1.8.9 NMS 源码的纯 Java 预测引擎，用精确物理公式枚举候选输入，与客户端实际位移对比。

目标：用源码公式取代经验拟合，提升 Speed/Fly 抓取力并降低误判。

## 现状与约束

- 服务器版本：Paper 1.8.8 build 445（v1_8_R3，与 1.8.9 完全相同）
- 客户端物理与服务器公式一致（EntityLiving.g + Entity.move）
- 现有 SpeedCheck/FlyCheck 保留（默认开启兜底）
- 新 SimulationCheck 默认关闭，稳定后可切换
- 协议层：客户端驱动（PlayerConnection.a(PacketPlayInFlying) 直接应用增量，EntityLiving.g() 也每 tick 运行）
- 潜行减速（×0.3）由客户端施加，服务器通过 delta 体现
- 无 NMS 依赖（PredictionEngine 纯 Java，可单测）
- 异步安全：PredictionEngine 无状态静态方法，不触碰 Bukkit API

## 方案对比

### 方案一：纯 Java 参数化模拟器（推荐）

- 优点：无 NMS 依赖、主线程安全、可 JUnit 单测、公式可精确控制
- 缺点：不模拟方块碰撞（用容差 + 现有 tick 标志兜底）

### 方案二：NMS 全模拟（最接近 Grim）

- 优点：精确方块碰撞、误差最小
- 缺点：依赖 NMS、异步线程用 NMS 有崩溃风险、实现量大

### 选型结论

方案一（纯 Java 参数化），后续必要时可升级。

## 详细设计

### 架构

三个新组件：
- `PredictionEngine`（纯 Java 静态工具）：输入物理状态 → 输出预测候选位移集
- `ShadowPlayer`（每玩家一个，存 PlayerData）：保存模拟状态（motionX/Y/Z、onGround、摩擦、yaw）
- `SimulationCheck`（新 CheckType，配置路径 `simulation`）：子检测 `sim-speed`、`sim-fly`

### 物理模型（从 1.8.8 NMS 源码转写）

**地面移动**（EntityLiving.g → Entity.a → Entity.move）：
```
f5 = onGround ? blockBelow.frictionFactor × 0.91 : 0.91
f6 = 0.16277136 / f5³
f3 = onGround ? bI() × f6 : aM (0.02)
a(fwdInput, strafeInput, f3) → motX += ..., motZ += ...
move(motX, motY, motZ)  // 碰撞：轴碰撞 → 该轴 motX/motZ = 0
motY -= 0.08  // 重力（move 之后）
motY *= 0.98  // 垂直拖拽
motX *= f5    // 水平摩擦
motZ *= f5
```

**跳跃**（EntityLiving.bF）：
```
motY = 0.42
if JUMP effect: motY += (amplifier + 1) × 0.1
if sprinting: motX -= sin(yaw·π/180) × 0.2; motZ += cos(yaw·π/180) × 0.2
```

**输入施加**（Entity.a(f,f1,f2)）：
```
f3 = sqrt(fwd² + strafe²)
if f3 < 1: f3 = 1
f3 = speed / f3  // speed = f3 from g() computation
f4 = sin(yaw·π/180); f5 = cos(yaw·π/180)
motX += (fwd×f5 − strafe×f4)  // 无 0.98 系数（源码确认）
motZ += (strafe×f5 + fwd×f4)
```

**速度属性**：
- 基础：0.1（EntityHuman.initAttributes:272）
- 疾跑：属性修饰符 +0.3 乘算 → 0.13（EntityLiving:160）
- 速度药水：×(1 + 0.2 × 等级)

**方块摩擦**：
- 普通：0.6
- 冰/浮冰：0.98
- 史莱姆：0.8

**碰撞后**（Entity.move:683-688）：轴碰撞 → 该轴 motX/motZ = 0；未碰撞保留实际增量。

### 候选输入枚举

地面：{走 1.0, 疾跑 1.3, 潜行 0.3} × {跳, 不跳} = ≤6 个候选（sprint jump 额外 +0.2 冲量）
空中：单一轨迹（动量继承，无加速度，f5 = 0.91）
垂直候选：{上包ΔY − 0.08（未跳轨迹）} + {0.42 + sprint impulse（跳了）} + {0（落地）}

### 数据流

1. 客户端发移动包 → PlayerConnection 处理
2. SimulationCheck.onMove(MoveContext) 触发
3. PredictionEngine 根据 ShadowPlayer 当前状态 + yaw + 摩擦 → 生成候选预测位置集
4. 实际位移落在任一候选 ± 容差盒 → 匹配 → drain VL，resync shadow；都不匹配 → bump VL
5. 预测只推一步（每次包后 resync）

### 状态重同步（防漂移）

- 每包匹配成功：shadow.motion/motY/onGround = 实际值（resync）
- 必须重同步：传送、重生、换世界、登入、被击退（velocity 注入 shadow.motion）、放床/回城
- 每包模拟 tick 数 = ceil(实际间隔/50ms)，上限 4 tick（处理高 ping 一包多 tick）
- ping 超限 → 走现有 max-ping 豁免（不模拟）

### 判定逻辑

**sim-speed**：
- 水平容差默认 0.03（严格 0.01）
- 偏移量 = 实际与最近候选的距离差
- 连续偏移超过 vl-before-flag（现有 bump/drain 缓冲）才 flag
- strict 模式：容差 × 0.7、VL 阈值不变

**sim-fly**：
- 垂直容差默认 0.05（严格 0.03）
- 上升快于跳跃轨迹或下落慢于重力轨迹 → 偏移
- 客户端声称 onGround 但 ΔY≠0 由 NoFall 管，sim-fly 不重复

### 豁免/边界

- 液体/蜘蛛网/梯子：不进入模拟（沿用现有 nearLiquidTicks/inWebTicks/ladderTicks 豁免）
- boxedIn（头顶方块）：跳跃包络 +0.3
- 严格模式：容差、f4 门槛用 sd/si 成对收严

### 配置

```yaml
simulation:
  enabled: false
  sim-speed:
    enabled: false
    horizontal-tolerance: 0.03
    strict:
      horizontal-tolerance: 0.01
  sim-fly:
    enabled: false
    vertical-tolerance: 0.05
    strict:
      vertical-tolerance: 0.03
```

### 测试策略

- PredictionEngine 纯函数 → JUnit 单测：走/跑/冲刺/跳/下坡落体/冰/史莱姆逐 tick 位移断言
- 实机验证：合法跑跳、高 ping 玩家、TPS 波动、传送/击退后不误判
- 旧 Speed/Fly 保持开启对照

## 风险与待确认项

- 潜行 0.3 因子在客户端施加，服务器通过 delta 体现 → 预测用 {0.3} 候选即可，不需要服务器端感知
- 碰撞边界（台阶/楼梯）不精确模拟，靠容差覆盖，若有误判再加 tick 标志
- 1.8.8 与 1.8.9 无差异（v1_8_R3）
