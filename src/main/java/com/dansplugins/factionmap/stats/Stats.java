package com.dansplugins.factionmap.stats;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe sample recorder. Each series keeps count, mean and max over its whole life and the most
 * recent {@link #WINDOW} samples for percentiles. {@code main.tick.us} at 20 ticks a second is a
 * 30-minute window.
 *
 * <p>The main thread records into this, so it must never wait long: each series has its own lock, and
 * {@link #summary()} only copies a series under that lock (tens of microseconds) and sorts the copy
 * outside it.
 */
public final class Stats {
    public static final int WINDOW = 36_000;

    private static final class Series {
        long[] buf = new long[256];
        long count;
        long total;
        long max;

        synchronized void add(long v) {
            if (count < WINDOW && count == buf.length) {
                buf = Arrays.copyOf(buf, Math.min(WINDOW, buf.length * 2));
            }
            buf[(int) (count % WINDOW)] = v;
            count++;
            total += v;
            max = Math.max(max, v);
        }

        synchronized long[] snapshot(long[] head) {
            head[0] = count;
            head[1] = total;
            head[2] = max;
            return Arrays.copyOf(buf, (int) Math.min(count, WINDOW));
        }
    }

    private final Map<String, Series> series = new ConcurrentHashMap<>();
    private final Map<String, Long> order = new ConcurrentHashMap<>();
    private long nextOrder;

    public void record(String name, long value) {
        Series s = series.get(name);
        if (s == null) {
            s = series.computeIfAbsent(name, k -> {
                synchronized (order) {
                    order.put(k, nextOrder++);
                }
                return new Series();
            });
        }
        s.add(value);
    }

    public void reset() {
        series.clear();
        synchronized (order) {
            order.clear();
        }
    }

    /** name -> {count, mean, p50, p95, p99, max}; percentiles over the recent window. First-recorded first. */
    public Map<String, Map<String, Object>> summary() {
        String[] names = series.keySet().toArray(new String[0]);
        Arrays.sort(names, (a, b) -> Long.compare(order.getOrDefault(a, Long.MAX_VALUE), order.getOrDefault(b, Long.MAX_VALUE)));
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        long[] head = new long[3];
        for (String name : names) {
            Series s = series.get(name);
            if (s == null) {
                continue;
            }
            long[] v = s.snapshot(head);
            Arrays.sort(v);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", head[0]);
            m.put("mean", head[0] == 0 ? 0.0 : Math.round(10.0 * head[1] / head[0]) / 10.0);
            m.put("p50", percentile(v, 0.50));
            m.put("p95", percentile(v, 0.95));
            m.put("p99", percentile(v, 0.99));
            m.put("max", head[2]);
            out.put(name, m);
        }
        return out;
    }

    /** Nearest-rank percentile of sorted values; 0 when empty. */
    static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }
}
