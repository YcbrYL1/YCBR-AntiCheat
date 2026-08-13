# 2026-08-10 误报修复计划（第二轮：Scaffold 三连炸 + Speed 跑跳 + ban 消息乱码）

## 用户实测日志证据（真实玩家账号 xiaoye_1，床战模拟）

| 场景 | 日志 | 根因 |
|------|------|------|
| 向上搭路 | Rotation not looking at placed block vl=2 | 垂直放置（头顶/脚下块）时 yaw 与放置方向无因果关系 |
| 斜上搭一次看背后 | Rotation vl=2、AimStep dYaw=30.6 vl=1 | 单次背离即判罪；转身环顾合法 |
| 走路右键防砍 7 连报 | Rotation vl=2 x7（同秒） | 放贴身块时 dx/dz≈0，yawToTarget 退化 → 必背离 |
| 高 cps 搭路 | Average avg=40ms/101ms/90ms < min=238ms | 人类连点 10-25 cps 合法；"慢于 238ms"假设错误 |
| 走路防砍 | MovePlace speed=0.506/0.383 > 0.35 | 疾跑/跳跃位移合法 |
| 跑跳（有概率） | Speed xZ=0.326 > max=0.296 | sprint-jump 空中水平 0.326 合法；air momentum 衰减至 0.29-0.31 |
| 封禁消息 | kick 消息乱码（锟斤拷/浣犲凡琚） | config.yml UTF-8 中文被 Bukkit FileReader 按平台 GBK 读 → mojibake |

## 修复方案（karpathy：最小改动、零误报优先）

### 1. Scaffold Rotation —— 改为"宏特征"检测
- 垂直放置豁免：`blockY != feetY`（塔/斜上梯 合法核心需求，yaw 无因果）
- 贴身豁免：水平距离 `hypot(dx,dz) <= 0.75`（防砍/脚下 合法；搭桥宏 dx≈1.0 保留可检测）
- 背离需**连续 5 次**才 bump（`consecutive-away: 5`），中途朝向正确立即清零 —— 看背后一眼单次背离不再判
- 移除 underFeet（pitch<-60）分支——垂直豁免已覆盖

### 2. Scaffold Average —— 从"平均速度"改为"均匀性"（按键宏黄金特征）
- 窗口 6 个间隔：`avg <= 150ms 且 (maxGap-minGap) <= 12ms`
- 人类连点抖动 ≥15ms 不满足均匀；宏间隔恒定（50/100/238ms 均能抓）
- 删 straight/diagonal-delay 逻辑 → `avg=40ms`（人类狂点）因 spread 大不判

### 3. Scaffold MovePlace —— 空中豁免 + 阈值 0.55
- `!onGround` 直接豁免（跳放/楼梯）
- 地面阈值 0.35→0.55：疾跑 0.28、疾跑横跳 0.35-0.41 放行；0.506 放行

### 4. Speed 跑跳 —— 调宽容忍面
- `ground.limit: 0.29→0.34`、`air.momentum: 0.36→0.42`、`air.momentum-decay: 0.985→0.99`
- sprint-jump 0.326 全程放行；速度作弊（0.5+）仍可判

### 5. ban kick 消息乱码 —— config.yml 强制 UTF-8 读取
- YCBRConfig 改用 `YamlConfiguration.loadConfiguration(InputStreamReader(FileInputStream, UTF_8))`
- pom 已有 `project.build.sourceEncoding=UTF-8`（Java 源码默认值安全）

### 6. AimStep dYaw=30.6
- 单次 vl=1（阈值 8），未达标；本轮不动，观察后续

## 验证
- mvn package 构建通过
- 部署测试：向上塔/斜上梯/看背后/防砍连放均不应报；搭桥宏（匀速 50ms）应触发 Average