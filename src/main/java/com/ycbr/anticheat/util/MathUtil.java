package com.ycbr.anticheat.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MathUtil {

    public static final double EXPANDER = Math.pow(2, 24);

    private MathUtil() {
    }

    public static double horizontal(double dx, double dz) {
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static double distance3D(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static double distanceToAabb(double ex, double ey, double ez, double cx, double cy, double cz,
            double halfW, double halfH) {
        double dx = Math.max(0D, Math.abs(ex - cx) - halfW);
        double dy = Math.max(0D, Math.abs(ey - cy) - halfH);
        double dz = Math.max(0D, Math.abs(ez - cz) - halfW);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static double yawToTarget(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    public static double pitchToTarget(double dy, double distXZ) {
        return Math.toDegrees(Math.atan2(dy, distXZ));
    }

    public static boolean rayIntersectsBox(double ox, double oy, double oz, float yaw, float pitch,
            double bx, double by, double bz, double expand) {
        double yawR = Math.toRadians(yaw);
        double pitchR = Math.toRadians(pitch);
        double dx = -Math.sin(yawR) * Math.cos(pitchR);
        double dy = -Math.sin(pitchR);
        double dz = Math.cos(yawR) * Math.cos(pitchR);
        return rayIntersectsAabb(ox, oy, oz, dx, dy, dz, bx - expand, by - expand, bz - expand, bx + 1D + expand,
                by + 1D + expand, bz + 1D + expand);
    }

    public static double distanceRayToAabb(double ox, double oy, double oz, double dx, double dy, double dz,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double best = Double.MAX_VALUE;
        double[][] points = new double[][] {
                { minX, minY, minZ }, { minX, minY, maxZ }, { minX, maxY, minZ }, { minX, maxY, maxZ },
                { maxX, minY, minZ }, { maxX, minY, maxZ }, { maxX, maxY, minZ }, { maxX, maxY, maxZ },
                { (minX + maxX) / 2D, (minY + maxY) / 2D, (minZ + maxZ) / 2D } };
        for (double[] p : points) {
            best = Math.min(best, distancePointToRay(p[0], p[1], p[2], ox, oy, oz, dx, dy, dz));
        }
        return best;
    }

    public static double distancePointToRay(double px, double py, double pz, double ox, double oy, double oz,
            double dx, double dy, double dz) {
        double t = ((px - ox) * dx + (py - oy) * dy + (pz - oz) * dz)
                / (dx * dx + dy * dy + dz * dz);
        if (t < 0D) {
            t = 0D;
        }
        double cx = ox + dx * t;
        double cy = oy + dy * t;
        double cz = oz + dz * t;
        return Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy) + (pz - cz) * (pz - cz));
    }

    public static double[] directionVector(float yaw, float pitch) {
        double yawR = Math.toRadians(yaw);
        double pitchR = Math.toRadians(pitch);
        return new double[] { -Math.sin(yawR) * Math.cos(pitchR), -Math.sin(pitchR),
                Math.cos(yawR) * Math.cos(pitchR) };
    }

    public static boolean rayIntersectsAabb(double ox, double oy, double oz, float yaw, float pitch,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double expand) {
        double yawR = Math.toRadians(yaw);
        double pitchR = Math.toRadians(pitch);
        double dx = -Math.sin(yawR) * Math.cos(pitchR);
        double dy = -Math.sin(pitchR);
        double dz = Math.cos(yawR) * Math.cos(pitchR);
        return rayIntersectsAabb(ox, oy, oz, dx, dy, dz, minX - expand, minY - expand, minZ - expand,
                maxX + expand, maxY + expand, maxZ + expand);
    }

    private static boolean rayIntersectsAabb(double ox, double oy, double oz, double dx, double dy, double dz,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double tmin = 0D;
        double tmax = Double.MAX_VALUE;
        if (Math.abs(dx) < 1E-9) {
            if (ox < minX || ox > maxX) {
                return false;
            }
        } else {
            double t1 = (minX - ox) / dx;
            double t2 = (maxX - ox) / dx;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
        }
        if (Math.abs(dy) < 1E-9) {
            if (oy < minY || oy > maxY) {
                return false;
            }
        } else {
            double t1 = (minY - oy) / dy;
            double t2 = (maxY - oy) / dy;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
        }
        if (Math.abs(dz) < 1E-9) {
            if (oz < minZ || oz > maxZ) {
                return false;
            }
        } else {
            double t1 = (minZ - oz) / dz;
            double t2 = (maxZ - oz) / dz;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
        }
        return tmax >= tmin;
    }

    public static double normalizeYaw(double yaw) {
        double y = yaw % 360D;
        if (y > 180D) {
            y -= 360D;
        } else if (y < -180D) {
            y += 360D;
        }
        return y;
    }

    public static double round(double value, int places) {
        double factor = Math.pow(10D, places);
        return Math.round(value * factor) / factor;
    }

    public static double angleToTarget(double yaw, double pitch, double dx, double dy, double dz) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-6D) {
            return 0D;
        }
        double cosPitch = Math.cos(pitchRad);
        double ax = -Math.sin(yawRad) * cosPitch;
        double ay = -Math.sin(pitchRad);
        double az = Math.cos(yawRad) * cosPitch;
        double dot = ax * dx + ay * dy + az * dz;
        double cos = dot / len;
        if (cos > 1D) {
            cos = 1D;
        } else if (cos < -1D) {
            cos = -1D;
        }
        return Math.toDegrees(Math.acos(cos));
    }

    public static long gcd(long a, long b) {
        while (b != 0L) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    public static long[] modeCount(java.util.List<Long> values) {
        if (values.isEmpty()) {
            return new long[] { 0L, 0L };
        }
        java.util.Map<Long, Integer> freq = new java.util.HashMap<Long, Integer>();
        long best = 0L;
        int bestCount = 0;
        for (long v : values) {
            int c = freq.containsKey(v) ? freq.get(v) + 1 : 1;
            freq.put(v, c);
            if (c > bestCount) {
                bestCount = c;
                best = v;
            }
        }
        return new long[] { best, bestCount };
    }

    public static double mean(List<Double> data) {
        if (data.isEmpty()) {
            return 0D;
        }
        double total = 0D;
        for (double v : data) {
            total += v;
        }
        return total / data.size();
    }

    public static double variance(List<Double> data) {
        if (data.isEmpty()) {
            return 0D;
        }
        double m = mean(data);
        double total = 0D;
        for (double v : data) {
            double d = v - m;
            total += d * d;
        }
        return total / data.size();
    }

    public static double stdDev(List<Double> data) {
        return Math.sqrt(variance(data));
    }

    public static double shannonEntropy(List<Double> data) {
        if (data.isEmpty()) {
            return 0D;
        }
        Map<Double, Integer> freq = new HashMap<Double, Integer>();
        for (double v : data) {
            freq.merge(v, 1, Integer::sum);
        }
        double entropy = 0D;
        int n = data.size();
        for (int count : freq.values()) {
            double p = (double) count / n;
            entropy -= p * (Math.log(p) / Math.log(2D));
        }
        return entropy;
    }

    public static int distinct(List<Double> data) {
        return (int) data.stream().distinct().count();
    }
}