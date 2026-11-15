package com.tcordeiro.horizonte.test;

import java.util.Arrays;

/** Tempos entre frames reais (ns), gravados quando {@link #recording}. */
public final class FrameClock {
	private FrameClock() {}

	static volatile boolean recording;
	private static long last;
	private static long[] deltas = new long[1 << 18];
	private static int n;

	public static void frame() {
		long now = System.nanoTime();
		if (recording && last != 0 && n < deltas.length) deltas[n++] = now - last;
		last = now;
	}

	static synchronized void start() {
		n = 0;
		last = 0;
		recording = true;
	}

	/** {fps médio, 1% low (fps), ms/frame médio, frames} */
	static double[] stop() {
		recording = false;
		long[] d = Arrays.copyOf(deltas, n);
		if (d.length == 0) return new double[] {0, 0, 0, 0};
		long sum = 0;
		for (long x : d) sum += x;
		double avgMs = sum / 1e6 / d.length;
		Arrays.sort(d);
		int k = Math.max(1, d.length / 100);
		long worst = 0;
		for (int i = d.length - k; i < d.length; i++) worst += d[i];
		double lowMs = worst / 1e6 / k;
		return new double[] {1000 / avgMs, 1000 / lowMs, avgMs, d.length};
	}
}
