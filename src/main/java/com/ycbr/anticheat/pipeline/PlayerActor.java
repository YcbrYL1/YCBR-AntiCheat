package com.ycbr.anticheat.pipeline;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PlayerActor {

    private static ExecutorService pool;

    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<Runnable>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public static void configure(ExecutorService executor) {
        pool = executor;
    }

    public void submit(Runnable task) {
        tasks.add(task);
        if (running.compareAndSet(false, true)) {
            pool.execute(this::drain);
        }
    }

    private void drain() {
        try {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
        } finally {
            running.set(false);
            if (!tasks.isEmpty() && running.compareAndSet(false, true)) {
                pool.execute(this::drain);
            }
        }
    }
}