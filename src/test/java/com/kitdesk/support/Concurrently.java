package com.kitdesk.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Runs tasks on separate threads, all released at the same instant, and collects the outcomes. */
public final class Concurrently {

    private Concurrently() {
    }

    /** What one task did: returned a value or threw. */
    public record Outcome<T>(T value, Throwable error) {
        public boolean succeeded() {
            return error == null;
        }
    }

    public static <T> List<Outcome<T>> run(List<Callable<T>> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                go.await();
                return task.call();
            }));
        }
        go.countDown();
        List<Outcome<T>> outcomes = new ArrayList<>();
        for (Future<T> f : futures) {
            try {
                outcomes.add(new Outcome<>(f.get(60, TimeUnit.SECONDS), null));
            } catch (java.util.concurrent.ExecutionException e) {
                outcomes.add(new Outcome<>(null, e.getCause()));
            } catch (java.util.concurrent.TimeoutException e) {
                outcomes.add(new Outcome<>(null, e));
            }
        }
        pool.shutdownNow();
        return outcomes;
    }
}
