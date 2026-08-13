package com.ycbr.anticheat.data;

import java.util.ArrayDeque;

public final class PlacePoints {

    private static final int BATCH = 6;

    private final ArrayDeque<long[]> points = new ArrayDeque<long[]>();

    public void add(long time, int x, int z) {
        points.add(new long[] { time, x, z });
        while (points.size() > BATCH) {
            points.removeFirst();
        }
    }

    public int size() {
        return points.size();
    }

    public long[][] copy() {
        return points.toArray(new long[points.size()][]);
    }

    public void clear() {
        points.clear();
    }
}