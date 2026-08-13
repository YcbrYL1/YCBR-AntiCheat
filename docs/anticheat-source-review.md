# 反作弊源码调研报告（KillAura 专项）

> 调研时间：2026-08-12 ｜ 目标：为 YCBR-AC（1.8.8 Spigot 反作弊）提供 killaura 检测参考，原则：**可抄代码，但绝不误判绿玩**。

## 目录

1. [TakaAntiCheat](#1-takaanticheat)
2. [Matrix-AntiCheat-full（基岩版）](#2-matrix-anticheat-full基岩版)
3. [Vulcan 2.6.5（反编译）](#3-vulcan-265反编译)
4. [此前已调研项目摘要](#4-此前已调研项目摘要)
5. [YCBR 落地状态对照表](#5-ycbr-落地状态对照表)

---

## 1. TakaAntiCheat

- **位置**：`TakaAntiCheat-master`（Java，1.8 时代老牌反作弊，`bg.dani02`）
- **质量**：低～中。旧式"超阈值即报"风格，WallHit 作者自注释 `// DONT ACCURATE !!!`

### 检测列表（combat 目录）

| 检测 | 逻辑 | 评价 |
|---|---|---|
| `ReachCombat` | XZ 平面距离（Y 置 0）> 3.5（生存）/4.6（创造），sprint 修正 + lag 修正；史莱姆/守卫者/巨人/末影龙/凋灵等实体黑名单；mcMMO 豁免 | 比我们的点对盒 3.05 宽松；黑名单思路有价值（巨型实体 hitbox 特殊） |
| `WallHit` | 眼→目标 `BlockIterator` 步进，`isPassable` 白名单（雪/梯/藤蔓/告示牌/草/玻璃板/地毯/栅栏/门/玻璃/铁栏/床/台阶/楼梯/箱子/液体/易碎）之外皆阻挡 | 与我们 ThroughWalls 同思路；1.8 语义下我们的 `isOccluding \|\| isSolid` 等价覆盖（玻璃 isSolid=true 已挡） |
| `InvalidInteractionEntity` | 开背包时攻击 / 攻击自己载具 / 距离 ≥8m → 报 | 开背包攻击 = Vulcan KillAuraH 同类（有误报面） |
| `Criticals` / `FastBow` / `AutoSoup` | 暴击/弓速/药水汤 | 非 killaura 范畴 |
| `inventory/FastClick` | 背包快速点击 | 非 killaura 范畴 |

### 可借鉴
- 巨型/特殊实体黑名单（我们 reach 对马等宽实体已用 `width/2` 精确盒，黑名单可作补充）
- mcMMO 类插件伤害豁免思路

---

## 2. Matrix-AntiCheat-full（基岩版）

- **位置**：`Matrix-AntiCheat-full`（**Minecraft Bedrock 行为包**，TS/JS 栈，非 Java；`bp/src/check/`）
- **质量**：中上。BE 生态最完善的开源反作弊之一；检测设计（缓冲、防 spike 误报）值得参考

### killaura.ts（11 个子检测 A–L）

| 编号 | 检测 | 逻辑 | 对照 YCBR |
|---|---|---|---|
| A | Multi-aura | 100ms 内命中 ≥3 个不同实体，或 70ms 内 ≥2 → **12 秒内触发 2 次才 flag**（防 lag spike 误报）；长矛豁免 | 我们 MultiInteract（位置包计数法）更严格精确 |
| B | Reach | **历史位置双线最小距离**（双方各记 20 个 tick 位置，lineDistance 两线段间最短距离）→ 取最小可能距离；阈值 3.6 / 高度差≥2 且 pitch<50 放宽 4.6 / 长矛 4.3–5.3 | 我们点对盒+外推 cap 已近似（外推即"历史线"的简化） |
| C | 打人看天/地 | 水平距离 >3 且 \|pitch\|>60 → 3 次 flag | Angle 的射线打盒更精确，已覆盖 |
| D | HitBox | 水平距离 >2.5 时，视线相对角度 >50°（触摸屏 160°）→ 3 次 flag | **角度阈值法**（比射线法宽松，BE 触摸瞄准难）；Java 我们保留射线法 ✓ |
| E | GhostHand | 当前位置线 + **tick 回推位置线**（`getTickPos` = 位置-速度）两条路径都被阻挡 → flag | 与 ThroughWalls 同思路；"双路径"可借鉴（我们只用当前快照） |
| F | GCD | yaw 或 pitch 为整数（%1===0）→ 2 次 flag | 我们 gcd/gcdgrid（默认关）更严格 |
| G | GCD 变体 | 1.5s 静止后 pitch 变化，800ms 攻击窗口内 pitch 整数 → flag | 同上 |
| I | No Swing | 命中后 1s 内无挥臂 → 3 次 flag | 我们 400ms 窗口 + 首发豁免，更紧 |
| J/K/L | 使用中攻击/睡觉攻击/攻击自己 | 协议级 | J/K 无意义；**L = 我们 SelfInteract 同款** ✓ |

### 可借鉴
- **防 spike 双触发模式**（12s 内 2 次才 flag）——比我们的 `vl-before-flag` 缓冲多了时间窗概念
- **双路径穿墙判定**（当前位置 + 回推 tick 位置）——对高速目标可减少快照误差

---

## 3. Vulcan 2.6.5（反编译）

- **位置**：`Vulcan-main\Vulcan-2.6.5.jar`（**无源码，CFR 反编译**，重度混淆：DES 字符串加密 + 控制流平坦化，逻辑需人工提取）
- **质量**：商业级。检测全：combat 下 65 个类

### 检测全景（combat）

| 组 | 检测 | 说明 |
|---|---|---|
| Aim（21 个） | Slope/Modulo/Repeated/Straight×2/Ratio×2/Negative/Constant/Linear/Direction/Small Yaw×2/Yaw Acceleration/**GCD Modulo/Divisor X/Divisor Y/GCD Flaw**/Analysis×2/Rotation | 旋转统计类：GCD、除数、灵敏度、方向切换、恒速旋转——与我们默认关闭的 gcd/gcdgrid/conststep/axisasym/aimstep 全部同类 |
| AutoClicker（20 个） | Limit/Deviation/Rounded/Skewness/Variance/Distinct/Outliers/Average Deviation/Kurtosis×2/Range/Impossible consistency/Average Difference/**Spikes/Identical** 等 | 点击时序全统计类（与我们 cps/interval 策略一致） |
| KillAura（9 个） | **Post**（<10ms 连击）/Acceleration/Head Snap/Multi Aura/**Switch**/Inventory/Frequency/Pattern/Strafe | 见下表 |
| Hitbox（2 个） | History/Simple："Attacked while not looking at target" | = 我们 Angle 同款 ✓ |
| Reach（2 个） | History/Simple：点到盒距离 | = 我们 Reach 同款 ✓ |
| Velocity（4 个） | Vertical/Horizontal×2/Ignored Vertical | 与我们反击退同款 |

### 关键实现细节（反编译提取）

| 检测 | 逻辑 | 结论 |
|---|---|---|
| **Multi Aura**（KillAuraD） | **两个 Flying（位置）包之间攻击 2+ 个不同实体** → 直接 flag（协议级：客户端每 tick 只能处理一次鼠标） | **已抄**：我们 checkMultiInteract 从 50ms 时间桶改为位置包计数法（旧法：30ms 间隔切目标会误报绿玩） |
| Inventory（KillAuraH） | 攻击后同批打开背包包 → flag | 弃用：攻击后快速按 E 开背包的绿玩误报面 |
| Post（KillAuraA） | 攻击间隔 <10ms（100+CPS，物理不可能）+ 40–100ms 检查 | 弃用：服务器 lag 包排队时误报 |
| Head Snap/Acceleration/Frequency/Pattern/Strafe | 转头速度/加速度/频率统计 | 统计类，默认关闭原则 |
| Switch | 快速切换目标 | 与 multitarget 同类 |

---

## 4. 此前已调研项目摘要

| 项目 | 质量 | 结论 |
|---|---|---|
| **Grim-2.0** | 顶级 | GCD 严格前提（delta∈(0,5°)、80 样本、15 次置信）、点到盒距离、保守外推、旋转不可信豁免。**ReachInterpolationData/AimProcessor 部分文件 GPL 不可抄**，只借鉴数学判据 |
| **AntiCheatAddition（ACA）** | 顶级 | **NCP 重写版刻意不做 killaura 检测**（作者认为误报高）；AimStep 数学判据（dYaw<0.00001 + dPitch>1° + pitch±90° 豁免）；精确 hitbox（0.3×1.8、蹲 1.65） |
| **NoCheatPlus（NCP）** | 经典 | Direction=射线到盒（=我们 Angle）；NoSwing 单次挥臂清零；WrongTurn=我们已有；Reach 4.4 眼-中心 + 动态 reachMod；Critical 依赖伤害结算易误报不加 |
| **AntiCheatReloaded（ACR）** | 中等 | **ThroughWalls 已抄**（NMS isOccluding 步进采样 + 6 重豁免）；Aimbot GCD 作者自注释 "falses sometimes?" |
| **MX-Project（Matrix 系）** | 高 | 灵敏度感知分组、熵/随机化/ML——全统计类，默认关闭决策验证 |
| **AQ3 Anti-Cheat** | 玩具 | yawDelta>100 直接 flag 的粗糙风格（正是要避免的），无可抄 |

---

## 5. YCBR 落地状态对照表

### 默认开启（强证据）

| 检测 | 来源 | 关键参数（config.yml） |
|---|---|---|
| Reach | Grim + NCP + Vulcan 同款 | max-distance 3.05、ping-comp 0.002、extrapolate-cap-ticks 10、vl-before-flag 5 |
| Angle | Grim/ACA 思路 + ACR 参考 | hit-expand 0.5、ping-expand 0.002、turn-exempt-degrees 25、vl-before-flag 6 |
| ThroughWalls | ACR 设计 + Taka isPassable 验证 | min-distance 1.5、ray-length 5.0、sample-step 0.35、max-target-speed 0.6、tps-exempt 15、vl-before-flag 3 |
| NoSwing | NCP/ACR/Matrix 同款 | window-ms 400、首发 >500ms 豁免、vl-before-flag 3 |
| SelfInteract / MultiInteract | Grim + Vulcan（MultiInteract 已改位置包计数法） | vl-before-flag 1 / 2 |
| WrongTurnCheck | NCP WrongTurn | \|pitch\|>90 协议级 |

### 默认关闭（统计类，GUI 可逐个开）

gcd、gcdgrid、conststep、axisasym、aimstep、cps、interval、multitarget、bigrot、modulo360

### 明确弃用（误报面）

- Vulcan Inventory（开背包攻击）、Post（lag 时包排队）
- NCP Critical（伤害结算依赖，1.8 onGround 同步差）
- ACR RepeatedAim（1.8 角度量化致绿玩等量旋转）
- AQ3 全部（粗糙阈值）
- Matrix C/D 角度阈值法（Java 射线法更精确）

### 部署铁律

换 jar 必须**删旧 config.yml 重新生成**（isSubEnabled 默认 true，旧配置缺 enabled:false 键会让统计检测仍运行）。
误封处理：`/untimeban xiaoye_1`。

---

## 6. 第二轮深挖（2026-08-12 二轮：Vulcan 其余 / Grim rotation / NCP fight 全量 / ACR·MX·Matrix-BE 其余）

### 6.1 Vulcan 其余 killaura/hitbox/reach/autoblock（反编译提取）

| 检测 | 判据 | 误报面 | 评分 |
|---|---|---|---|
| **AutoBlockA/B/C** | 攻击时 Combat 状态 `BlockPlace==true` 或 `BlockDig==true`（C 再加手持剑约束）→ fail。1.8 格挡/挖掘/放置状态下攻击是协议矛盾 | ≈0（绿玩挖掘/放置时不可能攻击） | **5** |
| **KillAuraH Inventory** | 本 tick 攻击包 + 随后收到 OPEN_INVENTORY_ACHIEVEMENT → flag。背包打开时鼠标被 GUI 拦截，攻击包发不出 | ≈0（重新评估：此前弃用过"攻击后按 E"，实际误报面极小） | **5** |
| **KillAuraA Post** | 攻击包与相邻包间隔 <10ms 置标记；后续包到达间隔 ∉(40ms,100ms) → buffer。等价"攻击后必须隔位置包" | 低（tps 抖动/网络 jitter 需豁免） | **4** |
| **KillAuraF Switch** | 窗口内攻击数>20 且攻击占比>85% 且切目标后 5ms 内再攻击 且旋转速率>15° → flag | 低（三重门槛） | **4** |
| **ReachB Simple 动态阈值** | `距离 = 水平距离-0.565`；`阈值 = 基线+0.05 + \|Y差\|×0.9 + 加速度×0.66 + 0.35(攻击≥3) + 0.125(SPEED药水) + 1.25(背对>100°) + 1.0(1500ms内攻击过)`；距离<6.0 | 极低（所有合法放大因子均折算入阈值） | **4** |
| **ReachA History** | 攻击者位置 → 目标历史位置(±3tick) 最小水平距离 -0.52 半宽 > 阈值(+1.15 连击) | 中低（目标被击飞时） | 4 |
| HitboxA/B | 攻击后下一位置包校验视线水平角（y 置 0）；历史版本 ±3tick 窗、1500ms 上车 +0.125 | 中（快速转身/甩视角） | 3 |
| KillAuraB Acceleration | <1.9 + 目标 Player + deltaY<0.0025 + 实际速度-理论摩擦(0.21)>0 + ping<500 | 中（移动模型误判） | 3 |
| KillAuraC Head Snap | 静止(<0.001) + 旋转>10° + 另一轴>26.5° + 垂直加速度>0 | 中（偷袭转身+跳跃） | 3 |
| KillAuraJ Frequency | 30 包窗口内 28+ 连续攻击无间隙 | 中（高 cps 绿玩） | 3 |
| KillAuraK Pattern | 10 个攻击间隔 span<50ms（脚本定时器） | 中低（人手极限达不到） | 3 |
| AutoBlockD Order | 攻击缺 INTERACT 且处格挡状态 | 中（状态模型未还原） | 3 |
| KillAuraL Strafe | 0.275 摩擦模型 + MNDT≥18（官方 experimental） | 官方明示实验性 | 2 |

### 6.2 Grim 2.3.74 rotation（多模块化后 rotation 包并入 checks/impl/aim，SensitivityAnalyzer 已删除）

| 发现 | 判据 | 评分 |
|---|---|---|
| **GCD 轴内迭代** | 同轴相邻 delta 迭代 gcd（非两轴一步 gcd）：`divisorX = gcd(deltaXRot, lastXRot)`；采样窗 delta∈(0,5°)；**MINIMUM_DIVISOR = ((0.2³)×8)×0.15 − 1e-3 = 0.0086**（MC 最小灵敏度下限，防 gcd 除到极小值） | **5**（升级现有 gcd） |
| **RunningMode 众数** | 80 样本环形队列，容差 1e-3 聚类，样本>15 且众数>15 才确认 modeX | 5（配套 GCD） |
| **灵敏度换算** | `sens = (cbrt(divisor/0.15/8) − 0.2)/0.6`；`deltaDots = deltaX/modeX`（整数倍判定，仅叠加） | 4 |
| **1.8 look 向量公式** | 1.8：`y = sin(−pitch)`（pitch 取负弧度）；1.9+：`y = sin(pitch)`——**同方向两版符号相反**。1.8 需 (yaw,pitch)+(lastYaw,pitch) 双 look 容差（客户端跳 tick 落后一帧） | **4**（修正我们 Angle/Reach 的射线，必做） |
| **flagBuffer 衰减节奏** | RotationPlace：违规置 1 并 flag；合法行为 max(0, buf−0.1) 衰减；**buf>0 才允许二次 flag**（≈先 flag 一次，10 次合法后才能再 flag） | 5（通用节奏） |
| **AimModulo360** | `yaw∈(−360,360) && \|deltaX\|>320 && \|lastDelta\|<30`（前一小步后突然 320°+） | 4（升级 modulo360） |
| BadPacketsD | pitch>90 硬判据（=我们 WrongTurn 已有） | 已有 |
| 服务端旋转验证 | transaction 匹配强制旋转包（BadPacketsB），1.8.8 需 ping 补偿替代 | 3 |
| Baritone 检测 | Grim 自己已禁用（cinematic 误报） | 弃用 |

### 6.3 NCP fight 全量（15 类，NCPCore 而非 NCPPlugin）

| 检测 | 判据 | 可借鉴点 |
|---|---|---|
| Direction | 视线延长到目标距离后逐轴偏差 off（≠射线打盒）；普通精度 2.6 极宽松、loop 0.5；**strict 模式 80° 角度闸门**（目标距离>半宽且夹角>80° 直接大违规，防近距离转头打人） | 80° 闸门可选；trace 回看 15tick 历史任一位置合法即放行（低误报核心） |
| Reach | `reachMod` 动态压缩：持续极限距离攻击→阈值逐步缩至 0.795；两级：硬违计 VL，软违静默取消+半惩罚+Improbable.feed；y 方向参考点在 [脚, 脚+身高] 间向视线收缩；实体修正（末影龙+6.5、巨人+1.5）；**VL 仅 lag<1.5 时累加** | 动态压缩 + 软违区静默取消 |
| NoSwing | PlayerAnimationEvent 置 flag 每击消费，没挥 +1、挥了 *0.9 | 极简，与现有等效 |
| Angle | 1s 窗口四因子加权：avgMove<0.2、avgTime<150ms、avgYaw>50°、切目标 yaw 突变>30°；VL 阈值 50 | 统计类，默认关起步 |
| **lost-sprint 启发式** | 目标距玩家<4.5 且上一移动点水平位移≥0.23 且 sprintingGrace 窗内 → 判定客户端丢疾跑状态，通知移动系统豁免（防"边跑边打"速度误报） | 防误报基建 |
| **PvP 击退注入** | `kb = 1.0 + isSprinting(1.0) + KNOCKBACK附魔等级`，转 vx=vz=kb/√8, vy=0.462 注入被击者 velocity 来源 | 防 VelocityCheck 误报 |
| 豁免族 | TNT 同 tick 后续近战放行；1.9 sweep 同 tick 同位置 damage=1.0；thorns 反击 damage≤4 同实体；死者规则（死后未受伤禁止攻击）；`isBlocking()` 无权限取消攻击；非法附魔热修；NPC/fake 防护 | 细节豁免 |
| **统一攻击惩罚窗 PenaltyTime** | reach/direction 违规、切物品各叠加 500ms 禁攻击（合并取 max） | cancel 机制（我们目前只 flag 不 cancel） |
| Speed | 6 桶×333ms + 7tick 短窗取 max，limit 15/s | 非 killaura |
| GodMode/FastHeal/SelfHit/WrongTurn | delta 记账（keepalive 时钟）、4s 间隔、自伤、\|pitch\|>90 | GodMode 非 killaura 范畴 |
| Critical | 确认弃用：不是独立检测，一半逻辑长在 SurvivalFly 移动状态机，现代客户端绕法多 | 弃用成立 |

### 6.4 ACR / MX / Matrix-BE 其余

| 来源 | 检测 | 判据 | 评分 |
|---|---|---|---|
| ACR | Reach/Angle/PacketOrder | 圆心距+双向 ping/lag/速度补偿（补偿模型最全）；60°/3 次角度；**PacketOrder elapsed<5ms 误报不可控弃用** | 2 / 2 / 1 |
| ACR | RepeatedAim / Variance | \|Δ−lastΔ\|<1e-5 严格重复；pitch 模 EvictingQueue 8 样本方差<0.25 | 3（辅助层） |
| ACR | GCD-Aimbot | gcd(Δpitch×2²⁴)∈(0,131072) + mod≤8e-4 + 加速度>5.5 + Δpitch∈(5,20)（作者自注释 falses sometimes） | 3（参数参考） |
| **MX** | **战斗窗口骨架** | 所有旋转统计**仅在上次攻击后 3500ms 内生效**（NoRotation 补零），ignoreCinematic 豁免——统计类检测的公共底盘 | **5（架构）** |
| **MX** | **灵敏度分组闸门** | pitch<0.31 时 1e-3 匹配 200 档 MCP 常量表×10 次取最小索引；40 样本众数；**sens∈[50,175] 才启用** | **4（基础设施）** |
| MX | AimConstantCheck type2/3 | delta/gcd 余数 >60 且非整，双轴同违规（人类不可能稳定命中） | 3 |
| MX | AimComplexCheck | 10 样本 Shannon 熵与上窗差<1e-5 或双轴熵差<1e-5；Randomizer flaw：min(var(gcd))<0.09 && max>35（单轴方差坍缩） | 3 |
| MX | AimStatisticsCheck | 25 样本 jiff 差分重复计数>2（排除 4/6/12）、IQR∈(12.5,96) 含 inf、K-S 检验>10 | 3 |
| MX | AimInvalidCheck | deltaPitch 指数小值（<1e-3 含 "E" 表示）且 deltaYaw>0.5 → 精度不符（协议级） | 3 |
| MX | AimPatternCheck | 100 样本交叉差分 <1e-4 数量>3；3 长度 float 位级相等子序列 | 3 |
| MX | AutoClickerCheck | 挥动间隔 100 样本 kurtosis<0 或熵 2 阶 jiff min<0.04 && max<0.06（节拍器特征） | 3（默认关） |
| Matrix-BE | aimAssist A-D | 三帧单调性关系（yaw 加速+pitch 减速等），抄自 Azure AntiCheat | 1-2（触摸专属） |
| Matrix-BE | autoclicker 伤害确认制 | **仅服务器确认伤害才计数**，avgCps>14（BE 系数，Java 需重标定 16-18） | 3-4 |
| Matrix-BE | GhostHand 全点采样 | 9×3=27 碰撞点对全被遮挡（比步进采样更严） | 3（升级项） |
| Matrix-BE | Reach 轨迹法 | 双方 20tick 位置线 lineDistance 最小间距（≈我们外推的推广） | 4（已有近似） |

### 6.5 二轮落地候选总表（按优先级）

| # | 候选 | 来源 | 评分 | 说明 |
|---|---|---|---|---|
| 1 | **AutoBlock**（攻击时 BlockPlace/BlockDig 状态） | Vulcan A/B/C | 5 | 协议级铁证，2 个 boolean 状态 + 10 行判据 |
| 2 | **Inventory 攻击+开背包** | Vulcan H | 5 | 重新评估后误报面≈0 |
| 3 | **1.8 look 向量修正 + (lastYaw,pitch) 双 look** | Grim | 4 | 修正现有 Angle/Reach 射线（误报源） |
| 4 | **Reach 动态阈值**（y差/药水/背对/连击补偿） | Vulcan ReachB | 4 | 升级 checkReach 固定 3.05 |
| 5 | **KillAuraA Post**（攻击后位置包窗口） | Vulcan A | 4 | 需 tps/网络豁免 |
| 6 | **GCD 轴内迭代 + 0.0086 下限 + 众数** | Grim | 5 | 升级现有 gcd（默认关） |
| 7 | **flagBuffer 衰减节奏**（flag 后 10 次合法才再 flag） | Grim | 5 | 通用节奏，作用于全部检测 |
| 8 | **modulo360 升级**（\|Δ\|>320 && lastΔ<30） | Grim AimModulo360 | 4 | 升级现有 modulo360 |
| 9 | **战斗窗口骨架**（旋转统计仅攻击后 3500ms 内） | MX | 5 | 新统计检测的前置底盘 |
| 10 | KillAuraF Switch（切目标 5ms+85%） | Vulcan F | 4 | 可选 |
| 11 | NCP lost-sprint / 击退注入 / TNT 豁免 | NCP | - | 防移动检测误报（非 killaura） |
| 12 | NCP PenaltyTime 禁攻击窗 | NCP | - | cancel 机制决策 |

### 6.6 二轮落地记录（用户选择"仅协议级铁证"）

**已落地（YCBR.jar 123,007B @ 2026-08-12 11:53）**：

| 检测 | 来源 | 实现要点 | 防误报设计 |
|---|---|---|---|
| **AutoBlock** | Vulcan AutoBlockA/B/C | 监听 BlockPlace/BlockDig 维护 `usingItem`/`digging` 状态；攻击时任一为 true → flag。1.8 vanilla 攻击前必先发 RELEASE_USE_ITEM 或 STOP/CANCEL_DIGGING | stale-ms 150（usingItem 过期防残留）；vl-before-flag 1 |
| **InventoryCombo** | Vulcan KillAuraH | 攻击 + CLIENT_COMMAND(OPEN_INVENTORY_ACHIEVEMENT) 同 tick（positionCount 相等）→ flag。背包打开时鼠标被 GUI 拦截，攻击包无法发出 | 攻击后 100ms 窗 + positionCount 同 tick 双重约束 |
| **Post** | Vulcan KillAuraA | 攻击距上一位置包 <10ms 置标记；下一位置包间隔 ∉(40,100)ms → buffer | tps-exempt 15；40-100ms = 合法 1 tick 窗口；vl-before-flag 3 |
| **Switch** | Vulcan KillAuraF | 窗口内攻击数>20 且攻击占比>85% 且切目标后 5ms 内攻击 且 yaw 速率>15° → flag | min-samples 20 + min-ratio 0.85 + window-ms 5 + min-yaw-rate 15；useEntityCount>40 重置 |
| 包监听扩展 | - | incoming 注册 BLOCK_DIG、CLIENT_COMMAND；handleUseEntity 对非攻击 action 也计 useEntityCount（actor 串行） | - |
| 基础设施 | - | Check 基类 + CheckRegistry 新增 `onClientCommand(data, action)` 回调链 | - |

**状态时序说明**（Actor 串行保证）：BlockPlace 包 → `usingItem=true` + `lastItemUseTime`；BlockDig(0 START)→`digging=true`、BlockDig(1/2 CANCEL/STOP)→`digging=false`、BlockDig(5 RELEASE)→`usingItem=false`。vanilla 挖掘/格挡中攻击必先发解除包，故攻击时状态为 true 即协议矛盾。

**未落地（保留候选）**：1.8 look 向量修正、Reach 动态阈值、GCD 轴内迭代、flagBuffer 节奏、modulo360 升级、战斗窗口骨架（用户本轮只选协议级铁证）。

---

## 7. 第三轮深挖（移动/协议类：Grim Timer+BadPackets / Vulcan movement+player / NCP moving+net / Matrix-BE 全量）

### 7.1 Grim（Timer/BadPackets/Setback/移动模拟）

| 发现 | 判据 | 评分 |
|---|---|---|
| **Timer 事务锚定** | 不用墙钟，用 transaction（1.8 = Window Confirmation 0xFF）锚定：每个 tick 包累计 `timerBalanceRealTime += 50e6`；首个事务到达时校验，`balance > 现在时间` → flag + setback，然后 -50ms 重新武装；`drift=120ms` 防掉 ping | 5（TimerCheck 升级：防 lag spike 核心） |
| **BadPacketsE 位置提醒** | ≤1.8 客户端最大 20 tick 无 POSITION/POSITION_AND_ROTATION 包 → 违规（载具豁免） | 5（BlinkCheck 对应升级） |
| 移动模拟数学 | 地面摩擦 `blockFriction(0.6)×0.91`、速度倍率 `0.16277136/friction³`、sprint ×1.3、跳 0.42、重力 0.08 每 tick ×0.98 阻尼、`isSlowedByUsingItem`（进食/格挡减速）、`didLastMovementIncludePosition`（1.8 专属） | 4（Speed/Fly 模型校准） |
| Setback 系统 | `executeViolationSetback` → 模拟一 tick 摩擦后发 transaction 标记的 PositionAndLook；1.8 需 Y+COLLISION_EPSILON（1.8 会被推进方块）；`shouldBlockMovement` = 未加载区块/偏移/未完成回退 | 3（teleport 回退基建） |
| Check 基座 | violations + decay 奖励、setbackvl 阈值、权限门 grim.exempt/nosetback/nomodifypacket | 概念 |

### 7.2 Vulcan movement/player（25 个 BadPackets 全解码 + 移动族）

**协议级直接 flag（零误报）**：

| 检测 | 判据 | 现状 |
|---|---|---|
| BadPacketsC | \|pitch\|>90 直接 flag | = WrongTurn 已有 ✓ |
| BadPacketsY | **x/y/z 为 NaN 或 ≥MAX_INT 直接 flag** | **新增候选** |
| BadPacketsE | UseEntity 目标==自身 | = SelfInteract 已有 ✓ |
| **BadPacketsH** | **飞行包间 ATTACK 无前置 ARM_ANIMATION → 直接 flag**（同批必须挥臂） | **新增候选**（比 NoSwing 400ms 窗口协议级更强） |
| BadPacketsT/U | KeepAlive id 与上次相同 / id==0 | 新增候选（包层） |
| BadPacketsO/Q | 槽位 <0 或 >8 | 新增候选（包层） |
| BadPacketsZ | 非 SPECTATOR 模式收 Spectate 包 | 新增候选 |
| BadPacketsJ | 放置状态下发 HeldItemSlot / BadPacketsI 攻击状态下发 EntityAction | 新增候选 |
| BadPacketsB | 连续飞行包 >20 直接 flag | 与 BlinkCheck 对照 |
| BadPackets1/2 Nuker | START/STOP_DESTROY 间隔 <3ms | 新增候选 |

**搭桥族（Scaffold 14 个）**：

| 检测 | 判据 | 评分 |
|---|---|---|
| **ScaffoldA** | 右键点击**脚下自身下方方块的下表面**（clicked==PlayerY−1 且 face==DOWN 且固体）→ 直接 flag（无 buffer） | **5**（搭桥铁律，正常玩家不可能点到脚下下表面） |
| ScaffoldC「Sprint」 | 桥接上下文 + 放置间隔<300ms + 计数>5 → buffer flag | 4 |
| ScaffoldG「Speed」 | 桥接上下文 + deltaY∈(0,0.001) + 间隔<300ms + speed<2.0 | 4 |
| TowerA「Limit」 | 放置面 UP + XZ 与自身一致 + deltaY>0 + 水平<0.1 + 距上次塔放<250ms → buffer flag | 4 |
| FastBreakA | LEFT_CLICK 记录预期破坏完成时间戳（含挖掘效率模型），BlockBreak 时实际耗时 < 预期 → buffer flag（17 道豁免门） | 4（挖掘模型） |

**移动族**：

| 检测 | 判据 | 评分 |
|---|---|---|
| InvalidC「Y」 | \|deltaY\|>3.921（最大下落速度）→ buffer；>4.0 直接；10s join 宽限 + 巨豁免清单（TELEPORT/VELOCITY/FLIGHT/VEHICLE/珍珠/跳跃提升…） | 4 |
| InvalidD「Acceleration」 | 飞行包 + deltaY<1e-5（平移）+ yaw 差>15° + pitch 差>15° + speed>0.2 → buffer（"大角度转头不减速"） | 3 |
| InvalidG/A | speed > 期望模型（`base + potion×0.0675 + (speedAttr−0.2)×3.5`）且加速度≤0.11（无加速瞬移式跳变） | 3 |
| **EntitySpeedA** | 骑马速度 > 期望累计项+0.15 → buffer（1.8.8 适用） | 4 |
| JesusE | 水面状态机 + 期望 0.166（水下补偿+0.2）+ 28 项豁免（荷叶/台阶/栅栏/船…） | 3 |
| Improbable 家族 | 全局计数熔断：同类违规数 > 阈值（60s 窗口）→ 直接 flag | 3（熔断基建） |
| FastPlaceA | 1s 滑动窗放置次数 > 阈值 | 4 |

### 7.3 NCP moving/net/inventory

| 检测 | 判据 | 评分 |
|---|---|---|
| **MorePackets（Timer 类）** | ActionFrequency（12 桶×500ms=6s 窗）+ burst（12×5s）；EPS 上限 20/22；**relax 摊薄 + burn 空桶预填 + lag 除权**；独立 setBack + 40 步老化；lag<1.0 才累加 VL | **5**（1.8.8 零误判标准答案） |
| **NoFall 自算账本** | 不信任客户端 fallDistance（"its behind"）：自算 maxY + 累积负位移；触地时 `damage = fallDist−3.0`（<0.5 不出手）；跳跃药水校正；反暴击（落点 <0.75 清零）；退出时写回 | **4** |
| InstantBow | `expected = 800 − 800×(1−force)² − 130`，实测 interact→shoot 间隔对比；lag 修正 | 5 |
| FastClick | 5 桶×200ms=1s 窗，短窗 4 次/整窗 15 次；加权（DROP 0.6、COLLECT 堆叠比例）；lag 除权；creative 豁免 | 4 |
| InstantEat | `expected = max(interact, lastClick)+700ms` + FoodLevelChange 校验 | 4 |
| KeepAliveFrequency | 20 桶×1s；首桶>1 即违规（防刷包） | 2 |
| FlyingFrequency | 全 flying 包 >60PPS 违规（1.8.8 用 PlayerMoveEvent 近似） | 2 |
| Velocity 账本 | SimpleAxisVelocity（竖直）+ FrictionAxisVelocity（水平，每 tick ×0.93）；actCount=80 移动事件激活、140 tick 作废；TOL_VVEL=0.0625；**落地才可清账（sfDirty）**；PVP 击退来源带标记（服务端易吞顶值） | 4 |
| SurvivalFly 垂直状态机 | GRAVITY_MAX 0.0834 / MIN 0.0624 / ODD 0.05；LiftOffEnvelope（增益 0.42/高度 1.35/相位 6）；vacc 垂直积分桶（下落必须逐桶变快 3×0.0374）；hacc 水平均值（30 步滑窗上限 1.34/1.1）；LostGround 10+ 种豁免 | 3（整套依赖 LostGround 块扫描，搬需连带） |
| 防坑细节 | **系统时间回拨防护（min 截断全部时间戳）**；空桶 burn；VL 只在 lag<1.5 累加 | 5（必修） |

### 7.4 Matrix-BE 全量（24 个检测）

**可移植（高价值）**：

| 检测 | 判据 | 评分 |
|---|---|---|
| **AutoTool** | **同一 tick 切快捷栏 + 左键打方块** → 二次确认（150s 窗口）后 flag | **4**（1.8.8 `PlayerItemHeldEvent` 1:1） |
| **ChestAura-B** | 清箱实测均速 < 120ms/组（自适应按可堆叠组数）→ ChestStealer | **4** |
| Surround | 自身 1 格内贴脸放黑曜石间隔 <100ms ×3 | 4 |
| Namespoof | 名字长度/ASCII/特殊字符 | 5 |
| IllegalItem | 数量越界/附魔越界/附魔冲突/重复附魔（ItemMeta 校验暴露） | 4 |
| Fly-B | vY>22 垂直瞬移 | 4 |
| EntityFly-D | `isInteger(vY×100)` 速度量化（setVelocity 特征） | 4 |
| Fly-C | 60 帧环形窗同值 vY 重复 ≥6 次（匀速飞行特征） | 4 |
| Speed-A/B | Δv>0.7 豁免清单；位移 vs 速度×1.2^等级 预测对比 | 3（框架可搬，倍率需 Java 标定） |
| BlockReach | 距方块中心 >8（BE 常数，Java 必须改 5-6） | 3 |
| Phase | 0.5 步长射线 + 位移∈[1,16] + 传送复位 + flag 后 500ms 冷却 | 3 |
| **14 项防误报模式** | 缓冲衰减（0.05-0.15/tick）、12s 双确认、150s 长确认、入服 6s 冷却、豁免时间戳清单、环境加权、before 事件 cancel+next-tick 确认、安全点回传、块语义白名单、瞬移自我豁免、并发去重 | **5**（平台无关工程设计全套可搬） |

**不可移植**：Extinguisher（Java 打火合法）、AutoTotem/ElytraFly/ShulkerNesting（1.9+ 物品）、EntityFly-C（BE 船常量 .225/.725）、InvalidSprint-A（BE 失明禁跑）、aimAssist 全族（触摸平滑）、Touch 输入豁免。

### 7.5 三轮落地候选总表（按价值）

| # | 候选 | 来源 | 评分 | 类型 |
|---|---|---|---|---|
| 1 | **ScaffoldA 点脚下下表面** | Vulcan | 5 | 协议级直接 flag |
| 2 | **BadPacketsY NaN/MAX 坐标** | Vulcan | 5 | 协议级直接 flag |
| 3 | **BadPacketsH 攻击同批无挥臂** | Vulcan | 5 | 协议级直接 flag（NoSwing 加强） |
| 4 | **MorePackets 22 EPS 桶计数** | NCP | 5 | TimerCheck 升级 |
| 5 | **NoFall 自算账本** | NCP | 4 | NoFallCheck 升级 |
| 6 | **TowerA 250ms 塔检测** | Vulcan | 4 | Scaffold 新增 |
| 7 | **EntitySpeedA 骑马速度** | Vulcan | 4 | Speed 新增 |
| 8 | **AutoTool 同 tick 切槽+击打** | Matrix | 4 | 新增 |
| 9 | **ChestAura-B 清箱速率** | Matrix | 4 | 新增 |
| 10 | Fly-C 重复速度 / EntityFly-D 量化 / Fly-B vY>22 | Matrix | 4 | Fly 升级 |
| 11 | Timer 事务锚定（Window Confirmation） | Grim | 5 | TimerCheck 升级（1.8 可行） |
| 12 | 系统时间回拨防护 | NCP | 5 | 基建必修 |
| 13 | Improbable 全局熔断 | Vulcan | 3 | 基建 |
| 14 | InstantBow/FastClick/Namespoof/Surround/IllegalItem | NCP/Matrix | 4-5 | 独立新增 |

### 7.6 三轮落地记录（用户选择"协议级+升级批"）

**已落地（YCBR.jar 126,226B @ 2026-08-12 13:12）**：

| 检测 | 来源 | 实现要点 |
|---|---|---|
| **FootClick** | Vulcan ScaffoldA | 放置面==DOWN(0) 且 点击格==floor(lastY)-1（自己站方块下方一格）且 XZ 偏差≤1 且方块 isSolid → 直接 flag（vl 1）；teleport 500ms 豁免 |
| **BadPacket**（NaN/MAX 坐标） | Vulcan BadPacketsY | handlePosition 里 x/y/z 任一 NaN/Infinite/\|v\|≥MAX_INT → 直接 queueVerdict（新 CheckType.BADPACKET，checks.badpacket.kick-at-vl: 20） |
| **NoSwingSame**（同批无挥臂） | Vulcan BadPacketsH | ARM_ANIMATION 改 actor 串行记录 `lastSwingPositionCount`；攻击时 `lastSwingPositionCount != positionCount` 且 120ms 内无挥臂 → bump（vl 2）；首发>500ms 豁免 |
| **TimerBurst**（0.5s 窗） | NCP MorePackets | 现有 6s 窗 eps>22 保留；新增 500ms 桶窗 >11 包/s → bump（vl 3） |
| **NoFall 账本** | NCP NoFall | 自算 maxY/minY（不信任 fallDistance）；`airTicks>0` 累积、`airTicks==0 且上帧>0`（触地）结算 `fall - 3.0 - 0.5×jumpLevel > 0.5` 且 2500ms 内无 FALL 伤害事件 → bump（vl 3）；slime/web/liquid 豁免重置；ladder 保留原豁免 |
| **时间回拨防护** | NCP | 包侧 `mono(data)` 单调时钟（raw < 已记录时复用旧值），位置包提交时更新；ARM/UseEntity 时间均经 mono |
| 基础设施 | - | CheckType 新增 BADPACKET；BukkitListener 新增 EntityDamageEvent(FALL) → `lastFallDamageTime`（NoFall 伤害确认） |

**防误报设计**：FootClick 需要 isSolid（半砖等非实心豁免）+ XZ≤1 宽容；NoSwingSame 120ms 挥臂乱序容错 + 首发豁免；NoFall 账本有 slime/web/liquid/梯豁免 + 伤害事件 2500ms 确认窗 + 跳药 0.5/级折算；TimerBurst 沿用 tps≥15 门。

**未落地（保留候选）**：AutoTool、ChestAura-B、EntitySpeedA、TowerA、Fly-C/EntityFly-D/Fly-B、InstantBow/FastClick、Improbable 熔断、Timer 事务锚定（Window Confirmation 实现成本高）。

## 8. 四轮深挖（Grim prediction/breaking + NCP blockbreak/combined + Vulcan autoclicker）

### 8.1 新发现（按落地价值）

**Grim（prediction/breaking/exploit/vehicle/groundspoof/misc）**：

| 检测 | 语义 | 评分 |
|---|---|---|
| VehicleA 「impossible_input」 | STEER_VEHICLE 包 \|forward\|>0.98 或 \|sideways\|>0.98 → flag（1.8 客户端只发 -1/0/1 与斜向 ±0.707；船内 0.98 合法因此严格 >0.98） | 5 |
| VehicleB 「spoofed_vehicle」 | 无坐骑发 STEER_VEHICLE → flag | 5 |
| VehicleD 「spoofed_jump」 | ENTITY_ACTION=RIDING_JUMP 且坐骑非马类 → flag（experimental） | 4 |
| VehicleE 「spoofed_boat」 | STEER_BOAT 包——1.9+ 才有，1.8.8 无效 | 0（跳过） |
| InvalidBreak | BlockDig faceId<0 或 >5 → flag（1.7.10 的 cancel+255 豁免不适用 1.8.8） | 5 |
| ExploitA | NAME_ITEM 铁砧命名 >30 字符（1.8）→ flag；ProtocolLib 5.0 无 NAME_ITEM 常量，fromID 构造有注册表风险 → 本次跳过 | 4（暂缓） |
| NoSlow | 1.8 输入模型：使用物品 f4×0.2、潜行×0.3、sprint×1.3；预测 offset 阈值 0.001；两 tick 连续确认；1.8 切槽后首 tick 不减速豁免 | 5 |
| GroundSpoof NoFall | 抓 LOOK/ROTATION 包（无位置）的 onGround=true，feetBB(0.6×0.001)+移动阈值做碰撞判定，不近地 → flag；teleport 强制 onGround=false；ghostblock/船豁免 | 4 |
| FastBreak | getBreakingDuration = 1000×5×hardness/multiplier；材质倍率 WOOD=2/STONE=4/IRON=6/DIAMOND=8/GOLD=12；效率附魔÷1.33/级；急迫 0.8^haste；环境（水下/不落地×4） | 4 |
| PositionBreakB / MultiBreak / AirLiquidBreak | cancel 面≠start 面；同 tick 双破坏；挖空气/液体 | 4 |

**NCP（blockbreak/blockplace/blockinteract/combined/generic/access）**：FastBreak 挖掘模型（100ms 延迟+2s grace+120s 衰减桶，bedrock 全豁免）；Reach 三套统一 5.2/5.6；Frequency 挖掘 5tick>7 块；Direction 射线-AABB 外扩 0.1 + flying 队列回放（防误杀核心）；FastPlace 22/2s+6/10tick 双轨；Against 贴空气/液体放置；Combined.checkYawRate 32° 静止窗+平均化+380°/s+250-2000ms 冻结；MunchHausen 钓自己（5 分但价值小）。

**Vulcan AutoClicker A-T（20 类全解码）**：输入=ARM_ANIMATION 间隔（非 UseEntity），delay>5000ms 清窗；统计：CPS=1000/mean、Σdev²、√Σdev²、3(mean-med)/Σdev²（偏度）、峰度 g2、distinct/mode。高价值：C Rounded（\|cps-round(cps)\|<0.08）、F/S Distinct（不同延迟<13 种）、J Range（max-min<50ms）、I/T 低峰度（反直觉最强）、N 高抖动复现、P Identical。全部统计类 → 默认关起步。

### 8.2 四轮落地记录（用户选择"协议级+NoSlow 批"）

**已落地（YCBR.jar 131,398B @ 2026-08-12 14:11）**：

| 检测 | 来源 | 实现要点 |
|---|---|---|
| **VehicleA/B**（不可达输入/无坐骑操纵） | Grim | 新监听 STEER_VEHICLE（sideways,forward float + jump,unmount bool）；\|f\|>0.98 或 \|s\|>0.98 → 直接 flag；否则 !inVehicle 且 unmount=false 连续 3 包 → flag（快照 2tick 延迟防护）；unmount/在车清零 streak |
| **VehicleD**（非骑马跳） | Grim | ENTITY_ACTION ordinal==5（RIDING_JUMP）且 !inVehicle：两次跳间隔>100ms → flag（单次不判防快照延迟） |
| **InvalidBreak**（face 越界） | Grim | BLOCK_DIG modifier.read(2)（EnumDirection→0-5）status==START 且 face<0 或 >5 → flag |
| **NoSlow**（使用物品不减速） | Grim | 新 NoSlowCheck（CheckType.NOSLOW）：onGround+groundTicks≥2、排除 ice/slime/liquid/web/ladder/boxed/kb<20tick、usingItem 且开始>100ms 豁免（1.8 首 tick 不减速），期望 = prevSpeed×0.546 + 0.296（覆盖 sprint×1.3、药水 II×1.4、潜行），连续 2 tick 超 → bump（vl 4） |
| **GroundSpoof 包级**（LOOK 谎称贴地） | Grim | LOOK 包读 onGround → `registry.onLook` → NoFallCheck：airTicks>8、motionY<-0.4、blockBelowUnstandable、teleport 500ms + kb<20tick 豁免 → bump（vl 5） |
| **BadPacket vl 修复** | - | NaN/MAX 坐标从裸 queueVerdict（vl 恒 0、只 alert 不 kick 的 bug）改为 ProtocolCheck.flag()，vl 正确累计并走 kick-at-vl: 20 |

**防误报设计**：VehicleB 需连续 3 包（上马瞬间快照未更新的 100ms 窗口内最多 2 包）+ unmount 包豁免；VehicleD 需两次跳且间隔>100ms；NoSlow 期望值 0.296 高于所有合法组合上限（走路 0.98、食用 0.196、sprint 1.3×、药水 II 1.4×、潜行 0.3× 的积 ≤0.274）且首 100ms 豁免 + kb 窗口豁免；GroundSpoof 与现有 clientOnGround 判定同阈值同豁免。

**本轮跳过**：VehicleE（1.9+ 包，1.8.8 不存在）；ExploitA（ProtocolLib 5.0 无 NAME_ITEM 常量，fromID 方案运行时注册表不确定，价值/风险不成比例）。

**未落地（保留候选）**：AutoTool、ChestAura-B、EntitySpeedA、TowerA、Fly-C/EntityFly-D/Fly-B、InstantBow/FastClick、Improbable 熔断、Timer 事务锚定、FastBreak 挖掘模型、PositionBreakB/MultiBreak/AirLiquidBreak、checkYawRate 联动、AutoClicker 统计族（Rounded/Distinct/Range/低峰度）、ExploitA（需 ProtocolLib fromID 验证）。

### 8.3 五轮落地记录（用户选择"协议小件批"）

**已落地（YCBR.jar 135,575B @ 2026-08-12 15:01）**：

| 检测 | 来源 | 实现要点 |
|---|---|---|
| **BadPacketsO**（负槽位） | Vulcan | HELD_ITEM_SLOT 包 slot<0 → flag（1.8 客户端只发 0-8） |
| **BadPacketsQ**（超界槽位） | Vulcan | HELD_ITEM_SLOT slot>8 → flag |
| **BadPacketsT**（KeepAlive id 重复） | Vulcan | KEEP_ALIVE id == 上次 → flag；lastKeepAliveId 初始 -1（首包豁免，防服务器 id 起始 0） |
| **BadPacketsU**（KeepAlive id=0） | Vulcan | id==0 且 last>0 → flag（同样首包豁免） |
| **AutoTool**（同 tick 切槽+击打） | Matrix | HELD_ITEM_SLOT 记录时间戳；BLOCK_DIG START 距切槽 <50ms（1 tick）→ 冷却 150s + bump（vl 3）→ flag；**时间戳方案**（positionCount 无法区分"站着切槽+挖"，gap 恒 0） |
| **FastThrow**（同 tick 双投） | Matrix | ProjectileLaunchEvent（仅雪球/蛋/珍珠，排除钓竿/箭）间隔 <50ms 连续 3 次 → flag；**1.8 客户端投掷有 50ms/tick 硬下限**（右键连点最快 50ms 间隔），<50ms 严格即作弊铁证；5s 冷却防刷屏 |
| 基建 | - | CheckType +AUTOTOOL/FASTTHROW；Check 基类 +onBlockDigStart/onThrow/onHeldItemSlot/onKeepAlive 分发；AsyncPacketListener 注册 KEEP_ALIVE/HELD_ITEM_SLOT；BukkitListener +ProjectileLaunchEvent |

**五轮深挖要点**：Grim Timer 事务锚定（0x33 负 id 注入 + 0xFF 回显，50e6ns/tick 记账，drift 120ms，TimerLimit 1000ms 封顶——1.8 最干净；TimerA 配置 `drift: 120`）；Grim Velocity 事务三明治（threshold 0.001/immediate 0.1/max-advantage 1/ceiling 4 + 0.999 衰减，1.8 无跳 tick 豁免更好查）；NCP InstantBow（`800×(1-(1-force)²)`−130ms，EntityShootBowEvent.force 零依赖，5 分）；NCP FastClick（ActionFrequency 5×200ms 桶，短窗 4/长窗 15，5 分）；NCP Reach 4.4+reachMod 自适应；Matrix ChestAura-B（120ms/stack+200ms/stack 预算）；Vulcan BadPacketsO/Q/T/U 全解码确认（`l>=0 && slot<0 / slot>8 / id==last / id==0`）；ImprobableA 实为 60s 一次的组合 VL 熔断；Judgement Day 定期全量清零。

**未落地（保留候选）**：InstantBow、FastClick、Timer 事务锚定（0x33 注入基建）、Reach 自适应、ChestAura-B、FastBreak 挖掘模型、checkYawRate 联动、AutoClicker 统计族、EntitySpeedA/TowerA、Improbable 熔断。

### 8.4 六轮落地记录（用户选择"防误判批"：Improbable 熔断 + InstantBow + FastClick）

**已落地（YCBR.jar 141,799B @ 2026-08-13 11:23）**：

| 检测 | 来源 | 实现要点 |
|---|---|---|
| **Improbable 熔断** | Vulcan/NCP | MainThreadHandler 内嵌 300tick(15s) 环形窗口统计全服 flag 数；`>= max(12, online×3)` → 熔断 60s（期间只告警不 kick），触发即清零窗口防叠加，**熔断解除时全服重置 vl/buffer**（防积压连踢，融合 Judgement Day 洗牌） |
| **InstantBow** | NCP | PlayerInteractEvent（BOW 右键）记拉弓时间；EntityShootBowEvent.force → `expected = 800×(1-(1-force)²) − 130ms`，实际耗时 < expected → flag；force<0.15 豁免、冷却 5s、bump 2 才 flag（1.8.8 事件 getEntity() 声明类型陷阱已避开） |
| **FastClick** | NCP | 复用 attackTimes 队列（actor 线程安全）；**5 attack/200ms = 25cps**（vanilla 1.8 每 tick 最多 1 次攻击 = 50ms 硬下限 → 5/200ms 数学铁证）；冷却 5s + bump 2；弃用长窗 15/s（防 jitter clicker 15-20cps 误杀），弃用 4/200（=理论极限 20cps 边界） |

**防误判设计取舍（量化分析后收紧）**：
- **InstantBow**：1.8 的 force 是服务器自算（蓄力时长/1000，线性），与 elapsed 同源 → `expected(e)=800×(1-(1-e/1000)²)−130 = 1.6e−0.0008e²−130 < e` 恒成立 → 正常蓄力数学上永不触发；作弊面也小（force 无法伪造），保留为低成本检测
- **FastClick**：原 5/200ms(=25cps) 有真实误判面——人类 16cps jitter（62ms 间隔）+ RTT 暴跌（TCP 到达间隔=发出间隔+抖动差，抖动差可为负）会把 250ms 压进 200ms 窗。已收紧：**6/200ms=30cps**（人类+vanilla 双重不可能区间）+ **ping>200ms 跳过**（高 ping 玩家网络波动大）+ bump 2 + 冷却 5s
- **Improbable 熔断**：熔断本身不惩罚（只暂停 kick+清零），但**单人作弊也会凑够阈值 → 等于保护作弊者**。已加 **min-players: 3**（5s 去重窗口内 ≥3 名不同玩家违规才熔断）——只有"多人同时异常"（批量误判风暴）才触发
