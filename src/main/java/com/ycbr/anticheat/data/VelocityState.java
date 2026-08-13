package com.ycbr.anticheat.data;

public final class VelocityState {

    private double vx;
    private double vy;
    private double vz;
    private boolean pending;
    private int ticksSince;
    private boolean airborneSeen;
    private boolean verticalKnockback;
    private long issuedAtMillis;

    public synchronized void issue(double x, double y, double z) {
        if (y == -0.04D) {
            y = -0.039875D;
        }
        this.vx = x;
        this.vy = y;
        this.vz = z;
        this.pending = true;
        this.ticksSince = 0;
        this.airborneSeen = false;
        this.verticalKnockback = y >= 0.05D;
        this.issuedAtMillis = System.currentTimeMillis();
    }

    public long issuedAtMillis() {
        return issuedAtMillis;
    }

    public synchronized void expire() {
        this.pending = false;
        this.ticksSince = 0;
        this.airborneSeen = false;
        this.verticalKnockback = false;
    }

    public boolean pending() {
        return pending;
    }

    public int ticksSince() {
        return ticksSince;
    }

    public void tickAge() {
        if (!pending) {
            return;
        }
        synchronized (this) {
            if (pending) {
                ticksSince++;
            }
        }
    }

    public void markAirborne() {
        if (pending) {
            airborneSeen = true;
        }
    }

    public boolean airborneSeen() {
        return airborneSeen;
    }

    public boolean hasVerticalKnockback() {
        return verticalKnockback;
    }

    public double x() {
        return vx;
    }

    public double y() {
        return vy;
    }

    public double z() {
        return vz;
    }

    public double horizontal() {
        return pending ? Math.sqrt(vx * vx + vz * vz) * Math.pow(0.91D, Math.min(20, ticksSince)) : 0D;
    }

    public double expectedHorizontal() {
        return Math.sqrt(vx * vx + vz * vz) * Math.pow(0.91D, Math.min(20, ticksSince));
    }

    public double vertical() {
        return verticalAt(ticksSince);
    }

    public double verticalAt(double t) {
        if (!pending) {
            return 0D;
        }
        double tt = Math.max(1D, t);
        double decayed = vy * Math.pow(0.98D, tt - 1D);
        double gravity = 0.08D * (1D - Math.pow(0.98D, tt - 1D)) / 0.02D;
        return Math.max(0D, decayed - gravity);
    }
}