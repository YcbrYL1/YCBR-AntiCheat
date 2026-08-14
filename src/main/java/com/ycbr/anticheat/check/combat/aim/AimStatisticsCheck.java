package com.ycbr.anticheat.check.combat.aim;

import java.util.ArrayList;
import java.util.List;

import com.ycbr.anticheat.check.Check;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.core.SensitivityProcessor;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.util.MathUtil;

/**
 * 统计层 Aim 检测（借鉴 MX AimStatisticsCheck/AimComplexCheck）。
 *
 * <p>在启发式 GCD/步长检测之上叠加：Shannon 熵、IQR、KS、Jiff、Z-score、峰度。
 * 与启发式信号交叉后才 punish（不独立误判）：本检测只投"aim-stat"交叉信号，
 * 由 KillAuraCheck 在 flag 前检查该信号。默认关闭。</p>
 *
 * <p>灵敏度校准：SensitivityProcessor 有效区间 [20,150] 之外不执行，
 * 避免高/低 DPI 玩家被统计层误伤。</p>
 */
public final class AimStatisticsCheck extends Check {

    /** 攻击后收集视角增量的窗口。 */
    private static final long WINDOW_MS = 3500L;
    /** 样本环形缓冲上限。 */
    private static final int MAX_SAMPLES = 50;

    private final AimStatsLogic logic = new AimStatsLogic();
    private final SensitivityProcessor sensitivity = new SensitivityProcessor();

    public AimStatisticsCheck(AntiCheatManager manager) {
        super(CheckType.AIMSTAT, manager);
    }

    /** 由 CheckRegistry.onRotation 调用（每次 LOOK/POSITION_LOOK）。 */
    public void onRotation(PlayerData data, float yaw, float pitch) {
        if (!isEnabled()) {
            return;
        }
        if (data.creative || data.flying || data.inVehicle || data.ping > cfg.maxPing()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - data.lastAttackTime > WINDOW_MS) {
            flushRecording(data, now);
            return; // 攻击窗口外不采集
        }

        double dY = Math.abs(MathUtil.normalizeYaw(yaw - data.lastAimYaw));
        double dP = Math.abs(pitch - data.lastAimPitch);
        data.lastAimYaw = yaw;
        data.lastAimPitch = pitch;
        if (dY <= 0.1D || dY >= 30D || dP >= 30D) {
            return; // 过滤静止/大转，与启发式 aimDeltas 相同的过滤口径
        }

        data.aimDeltasStat.addLast(dY);
        data.aimPitchDeltasStat.addLast(dP);
        while (data.aimDeltasStat.size() > MAX_SAMPLES) {
            data.aimDeltasStat.pollFirst();
            data.aimPitchDeltasStat.pollFirst();
        }
        data.statSampleCount++;

        if (data.aimDeltasStat.size() < AimStatsLogic.MIN_SAMPLES) {
            return;
        }
        // 灵敏度区间外跳过统计判定（保持样本继续累积，待区间内再判）
        double sens = sensitivity.calculateSensitivity(toList(data.aimDeltasStat));
        if (!sensitivity.inRange(sens)) {
            return;
        }

        List<String> hits = logic.evaluate(toList(data.aimDeltasStat),
                sd("entropy-max", 1.5D, 1.0D),
                sd("iqr-min", 0.0005D, 0.001D),
                sd("ks-min-uniform", 0.15D, 0.2D),
                si("jiff-pattern-len", 3, 3),
                si("jiff-max", 2, 1),
                sd("zscore-threshold", 4.0D, 3.5D),
                sd("kurtosis-max", -0.5D, -0.5D));
        if (hits.isEmpty()) {
            return;
        }
        // 任一统计信号命中 → 投交叉信号（带时间戳供新鲜度校验）
        addSignal(data, "aim-stat");
        data.aimStatSignalTime = now;
    }

    /** 攻击窗口结束后，把累积样本写入数据集（若该玩家正在录制）。 */
    private void flushRecording(PlayerData data, long now) {
        if (data.aimDeltasStat.size() < AimStatsLogic.MIN_SAMPLES) {
            return;
        }
        String player = playerName(data);
        if (player == null || !manager.getDatasetManager().isRecording(player)) {
            return;
        }
        manager.getDatasetManager().recordAimWindow(player, toList(data.aimDeltasStat));
        data.aimDeltasStat.clear();
        data.aimPitchDeltasStat.clear();
        data.statSampleCount = 0;
    }

    private String playerName(PlayerData data) {
        org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(data.getUuid());
        return p == null ? null : p.getName();
    }

    private static List<Double> toList(java.util.ArrayDeque<Double> deque) {
        return new ArrayList<Double>(deque);
    }
}