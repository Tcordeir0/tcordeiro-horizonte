package com.tcordeiro.horizonte;

import java.util.Arrays;

/**
 * Constrói a malha de um mosaico (128×128 blocos) como «colunas»: um topo plano por célula (células vizinhas iguais na mesma linha
 * juntam-se num só quadrado) e paredes verticais onde a vizinha é mais baixa. Java puro, testado em JUnit.
 * <p>
 * Cada vértice são 4 inteiros: x, y, z (float, x/z relativos ao canto do mosaico, y absoluto) e a cor RGBA8. 4 vértices por quadrado.
 */
public final class MeshBuilder {
	/** Fonte das amostras base (coordenadas globais de amostra: bloco / 2). */
	public interface SampleSource {
		short height(int gx, int gz);

		int color(int gx, int gz);
	}

	public static final float SHADE_TOP = 1.0f, SHADE_NS = 0.80f, SHADE_EW = 0.64f;

	/** Resultado: {@code data} tem {@code quads × 16} inteiros válidos. */
	public record Mesh(int tx, int tz, int step, int[] data, int quads, int minY, int maxY) {
		public boolean empty() {
			return quads == 0;
		}
	}

	private int[] data = new int[16 * 1024];
	private int quads;

	/** Célula (cx,cz) no passo {@code step}: a amostra base mais alta dentro dela. */
	static void cell(SampleSource src, int cx, int cz, int step, short[] hOut, int[] cOut, int idx) {
		int k = step / LodMath.BASE;
		short best = LodMath.NONE;
		int col = 0;
		int gx0 = cx * k, gz0 = cz * k;
		for (int dz = 0; dz < k; dz++) {
			for (int dx = 0; dx < k; dx++) {
				short h = src.height(gx0 + dx, gz0 + dz);
				if (h != LodMath.NONE && (best == LodMath.NONE || h > best)) {
					best = h;
					col = src.color(gx0 + dx, gz0 + dz);
				}
			}
		}
		hOut[idx] = best;
		cOut[idx] = col;
	}

	public Mesh build(SampleSource src, int tx, int tz, int step) {
		quads = 0;
		int n = LodMath.TILE_BLOCKS / step;
		int w = n + 2;
		short[] h = new short[w * w];
		int[] c = new int[w * w];
		int cx0 = tx * n - 1, cz0 = tz * n - 1;
		for (int j = 0; j < w; j++)
			for (int i = 0; i < w; i++) {
				// a moldura (fora do mosaico) só serve para as paredes: os cantos não são precisos
				boolean corner = (i == 0 || i == w - 1) && (j == 0 || j == w - 1);
				if (corner) {
					h[j * w + i] = LodMath.NONE;
					continue;
				}
				cell(src, cx0 + i, cz0 + j, step, h, c, j * w + i);
			}
		int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		// topos (juntando células iguais seguidas em x)
		for (int j = 1; j <= n; j++) {
			int i = 1;
			while (i <= n) {
				short hh = h[j * w + i];
				if (hh == LodMath.NONE) {
					i++;
					continue;
				}
				int col = c[j * w + i];
				int e = i + 1;
				while (e <= n && h[j * w + e] == hh && c[j * w + e] == col) e++;
				float x0 = (i - 1) * step, x1 = (e - 1) * step, z0 = (j - 1) * step, z1 = j * step;
				int rgba = LodMath.packRgba(col);
				quad(x0, hh, z0, x0, hh, z1, x1, hh, z1, x1, hh, z0, rgba);
				minY = Math.min(minY, hh);
				maxY = Math.max(maxY, hh);
				i = e;
			}
		}
		// paredes onde a vizinha é mais baixa (vizinha sem dados: sem parede)
		for (int j = 1; j <= n; j++) {
			for (int i = 1; i <= n; i++) {
				short hh = h[j * w + i];
				if (hh == LodMath.NONE) continue;
				int col = c[j * w + i];
				float x0 = (i - 1) * step, x1 = i * step, z0 = (j - 1) * step, z1 = j * step;
				short hn;
				// norte (−z)
				hn = h[(j - 1) * w + i];
				if (hn != LodMath.NONE && hn < hh) {
					quad(x0, hn, z0, x0, hh, z0, x1, hh, z0, x1, hn, z0, LodMath.packRgba(LodMath.shade(col, SHADE_NS)));
					minY = Math.min(minY, hn);
				}
				// sul (+z)
				hn = h[(j + 1) * w + i];
				if (hn != LodMath.NONE && hn < hh) {
					quad(x0, hn, z1, x1, hn, z1, x1, hh, z1, x0, hh, z1, LodMath.packRgba(LodMath.shade(col, SHADE_NS)));
					minY = Math.min(minY, hn);
				}
				// oeste (−x)
				hn = h[j * w + i - 1];
				if (hn != LodMath.NONE && hn < hh) {
					quad(x0, hn, z0, x0, hn, z1, x0, hh, z1, x0, hh, z0, LodMath.packRgba(LodMath.shade(col, SHADE_EW)));
					minY = Math.min(minY, hn);
				}
				// este (+x)
				hn = h[j * w + i + 1];
				if (hn != LodMath.NONE && hn < hh) {
					quad(x1, hn, z0, x1, hh, z0, x1, hh, z1, x1, hn, z1, LodMath.packRgba(LodMath.shade(col, SHADE_EW)));
					minY = Math.min(minY, hn);
				}
			}
		}
		if (quads == 0) return new Mesh(tx, tz, step, new int[0], 0, 0, 0);
		return new Mesh(tx, tz, step, Arrays.copyOf(data, quads * 16), quads, minY, maxY);
	}

	private void quad(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz,
			int rgba) {
		if ((quads + 1) * 16 > data.length) data = Arrays.copyOf(data, data.length * 2);
		int o = quads * 16;
		o = vert(o, ax, ay, az, rgba);
		o = vert(o, bx, by, bz, rgba);
		o = vert(o, cx, cy, cz, rgba);
		vert(o, dx, dy, dz, rgba);
		quads++;
	}

	private int vert(int o, float x, float y, float z, int rgba) {
		data[o] = Float.floatToRawIntBits(x);
		data[o + 1] = Float.floatToRawIntBits(y);
		data[o + 2] = Float.floatToRawIntBits(z);
		data[o + 3] = rgba;
		return o + 4;
	}
}
