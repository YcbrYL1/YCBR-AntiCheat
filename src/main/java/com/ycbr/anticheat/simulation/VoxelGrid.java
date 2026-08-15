package com.ycbr.anticheat.simulation;

/**
 * 体素快照（Voxel Grid）：主线程（MainThreadHandler）每玩家快照周期采集的
 * 玩家周围方块碰撞信息，供包线程的预测引擎做"真实碰撞重演"（替代
 * {@link PredictionEngine#applyCollision} 的三方向墙距 hack 与台阶/粘液豁免清单）。
 *
 * <p>布局：以玩家脚底格（floor(feet)）为原点，XZ 各 ±{@link #RANGE_XZ} 格，
 * Y 向下 {@link #RANGE_Y_BELOW} 格、向上 {@link #RANGE_Y_ABOVE} 格。
 * 每格存位标志（见 {@link #SOLID} 等）——标志即碰撞语义：
 * <ul>
 *   <li>{@link #SOLID}：完整碰撞盒，顶高 1.0；</li>
 *   <li>{@link #STEP}：台阶/楼梯（自动步进 0.5），碰撞顶高 0.5；</li>
 *   <li>{@link #SLIME}：粘液块（完整碰撞盒 + 弹跳语义）；</li>
 *   <li>{@link #LIQUID}/{@link #WEB}/{@link #LADDER}：非固体，只影响物理参数；</li>
 *   <li>{@link #PISTON}：活塞移动方块，位移外部驱动。</li>
 * </ul>
 * 网格是"原子上一次快照"的只读拷贝（volatile 引用替换），不跨线程改共享结构。
 * 网格过期/越界 → {@link #flagAt}/{@link #topAt} 返回未知，调用方回退旧路径。
 */
public final class VoxelGrid {

    /** XZ 各方向覆盖格数（半宽 2 格 + 1 = 5 格宽）。 */
    public static final int RANGE_XZ = 2;
    /** 脚底向下覆盖格数。 */
    public static final int RANGE_Y_BELOW = 1;
    /** 脚底向上覆盖格数（覆盖 1.8 身高 + 跳跃抬升）。 */
    public static final int RANGE_Y_ABOVE = 3;

    public static final int W = 2 * RANGE_XZ + 1;
    public static final int H = RANGE_Y_BELOW + RANGE_Y_ABOVE + 1;

    // ---- 格标志 ----
    public static final int SOLID = 1;
    public static final int STEP = 2;
    public static final int SLIME = 4;
    public static final int LIQUID = 8;
    public static final int WEB = 16;
    public static final int LADDER = 32;
    public static final int PISTON = 64;
    /** 灵魂沙：碰撞盒高 7/8（0.875），玩家站上去脚底沉入 0.125（1.8 实际行为）。 */
    public static final int SOUL = 128;

    /** 灵魂沙碰撞盒高度（相对格底）。 */
    public static final double SOUL_SAND_HEIGHT = 0.875;

    /** 原点格坐标 = 采集时刻 floor(脚底位置)。 */
    public final int originX;
    public final int originY;
    public final int originZ;
    /** 采集时刻（ms），用于新鲜度判断。 */
    public final long capturedAt;
    /** 网格体素（长度 W*W*H）。 */
    public final int[] cells;

    public VoxelGrid(int originX, int originY, int originZ, long capturedAt) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.capturedAt = capturedAt;
        this.cells = new int[W * W * H];
    }

    private int index(int bx, int by, int bz) {
        int ix = bx - originX + RANGE_XZ;
        int iy = by - originY + RANGE_Y_BELOW;
        int iz = bz - originZ + RANGE_XZ;
        if (ix < 0 || ix >= W || iy < 0 || iy >= H || iz < 0 || iz >= W) {
            return -1;
        }
        return (iy * W + iz) * W + ix;
    }

    /**
     * 在指定格叠加标志（主线程采集用）。
     *
     * @return 越界返回 false（调用方应保证采集坐标在覆盖范围内）
     */
    public boolean setFlag(int bx, int by, int bz, int flags) {
        int i = index(bx, by, bz);
        if (i < 0) {
            return false;
        }
        cells[i] |= flags;
        return true;
    }

    /**
     * 读取格标志。
     *
     * @return -1 = 网格外（未知）；否则为标志位组合（可为 0 = 空气）
     */
    public int flagAt(int bx, int by, int bz) {
        int i = index(bx, by, bz);
        return i < 0 ? -1 : cells[i];
    }

    /** 是否已过期（快照距今超过 maxAgeMs）。 */
    public boolean isFresh(long now, long maxAgeMs) {
        return now - capturedAt <= maxAgeMs;
    }

    /**
     * 格顶碰撞高度（相对格底，单位格）：SOLID/SLIME → 1.0；STEP → 0.5；
     * SOUL → 0.875（灵魂沙 7/8 盒）；其余 → 0.0。
     *
     * @return 顶高；网格外返回 -1
     */
    public double topAt(int bx, int by, int bz) {
        int f = flagAt(bx, by, bz);
        if (f < 0) {
            return -1;
        }
        if ((f & (SOLID | SLIME)) != 0) {
            return 1.0;
        }
        if ((f & SOUL) != 0) {
            return SOUL_SAND_HEIGHT;
        }
        if ((f & STEP) != 0) {
            return 0.5;
        }
        return 0.0;
    }

    /** 是否固体（可碰撞）。 */
    public boolean isSolid(int bx, int by, int bz) {
        return topAt(bx, by, bz) > 0.0;
    }
}
