package com.tcordeiro.horizonte;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class LodMathTest {
	@Test
	void keysRoundTripWithNegatives() {
		for (int x : new int[] {0, 1, -1, 123456, -987654, Integer.MAX_VALUE, Integer.MIN_VALUE})
			for (int z : new int[] {0, 7, -7, 30000000, -30000000}) {
				long k = LodMath.key(x, z);
				assertEquals(x, LodMath.keyX(k));
				assertEquals(z, LodMath.keyZ(k));
			}
		assertNotEquals(LodMath.key(1, -1), LodMath.key(-1, 1));
	}

	@Test
	void downsampleKeepsHighestColumnAndItsColor() {
		short[] h = new short[256];
		int[] c = new int[256];
		for (int i = 0; i < 256; i++) {
			h[i] = 64;
			c[i] = 0x00FF00;
		}
		// na célula (0,0) há uma coluna alta em (1,1) com cor própria
		h[1 * 16 + 1] = 90;
		c[1 * 16 + 1] = 0xFF0000;
		// a célula (7,7) está toda vazia
		for (int dz = 0; dz < 2; dz++)
			for (int dx = 0; dx < 2; dx++) h[(14 + dz) * 16 + 14 + dx] = LodMath.NONE;
		// a célula (3,0) tem só uma coluna com dados
		h[6] = LodMath.NONE;
		h[7] = LodMath.NONE;
		h[16 + 6] = LodMath.NONE;
		h[16 + 7] = 70;
		short[] h8 = new short[64];
		int[] c8 = new int[64];
		LodMath.downsample(h, c, h8, c8);
		assertEquals(90, h8[0]);
		assertEquals(0xFF0000, c8[0]);
		assertEquals(64, h8[1]);
		assertEquals(LodMath.NONE, h8[63]);
		assertEquals(70, h8[3]);
	}

	@Test
	void stepGrowsWithDistanceAndQuality() {
		assertEquals(2, LodMath.stepFor(60, 1));
		assertEquals(4, LodMath.stepFor(200, 1));
		assertEquals(8, LodMath.stepFor(500, 1));
		assertEquals(16, LodMath.stepFor(2000, 1));
		assertEquals(4, LodMath.stepFor(60, 0.5));
		assertEquals(2, LodMath.stepFor(150, 2));
		int prev = 0;
		for (int d = 0; d < 5000; d += 10) {
			int s = LodMath.stepFor(d, 1);
			assertTrue(s >= prev, "o passo nunca diminui com a distância");
			prev = s;
		}
	}

	@Test
	void fogIsLinearAndClamped() {
		assertEquals(0f, LodMath.fogFactor(10, 100, 200));
		assertEquals(0.5f, LodMath.fogFactor(150, 100, 200), 1e-6);
		assertEquals(1f, LodMath.fogFactor(500, 100, 200));
		assertEquals(1f, LodMath.fogFactor(100, 100, 100));
	}

	@Test
	void depthRowMapsNearAndFarToClipBounds() {
		float n = 4, f = 2048;
		assertEquals(-1f, LodMath.ndcDepth(n, f, n), 1e-5);
		assertEquals(1f, LodMath.ndcDepth(n, f, f), 1e-4);
		float a = LodMath.ndcDepth(n, f, 500), b = LodMath.ndcDepth(n, f, 1500);
		assertTrue(a < b, "mais longe = mais fundo");
		// precisão: 1000 e 1001 blocos ainda dão profundidades diferentes num depth de 24 bits
		float d0 = LodMath.ndcDepth(n, f, 1000) * 0.5f + 0.5f, d1 = LodMath.ndcDepth(n, f, 1001) * 0.5f + 0.5f;
		assertTrue((d1 - d0) * (1 << 24) >= 1);
	}

	@Test
	void ringsCoverSquareExactlyOnce() {
		int R = 9;
		Set<Long> seen = new HashSet<>();
		for (int r = 0; r <= R; r++) {
			int[] ring = LodMath.ring(r);
			for (int i = 0; i < ring.length; i += 2) {
				assertEquals(r, Math.max(Math.abs(ring[i]), Math.abs(ring[i + 1])));
				assertTrue(seen.add(LodMath.key(ring[i], ring[i + 1])), "posição repetida");
			}
		}
		assertEquals((2 * R + 1) * (2 * R + 1), seen.size());
	}

	@Test
	void distToRect() {
		assertEquals(0, LodMath.distToRect(5, 5, 0, 0, 10, 10));
		assertEquals(5, LodMath.distToRect(-5, 5, 0, 0, 10, 10), 1e-9);
		assertEquals(5, LodMath.distToRect(13, 14, 0, 0, 10, 10), 1e-9);
	}

	@Test
	void colorPacking() {
		assertEquals(0xFF0000FF, LodMath.packRgba(0xFF0000)); // R no byte mais baixo
		assertEquals(0xFFFF0000, LodMath.packRgba(0x0000FF));
		assertEquals(0x404040, LodMath.shade(0x808080, 0.5f));
		assertEquals(0xFFFFFF, LodMath.shade(0xF0F0F0, 2f));
	}

	// ── 0.1.1: projeção com balanço da visão ────────────────────────────────────────────────────

	/** a × b, coluna-maior (como o JOML). */
	static float[] mul(float[] a, float[] b) {
		float[] r = new float[16];
		for (int c = 0; c < 4; c++)
			for (int row = 0; row < 4; row++) {
				float v = 0;
				for (int k = 0; k < 4; k++) v += a[k * 4 + row] * b[c * 4 + k];
				r[c * 4 + row] = v;
			}
		return r;
	}

	static float[] perspective(float fovY, float aspect, float near, float far) {
		float f = (float) (1 / Math.tan(fovY / 2));
		float[] d = LodMath.depthRow(near, far);
		float[] m = new float[16];
		m[0] = f / aspect;
		m[5] = f;
		m[10] = d[0];
		m[11] = -1;
		m[14] = d[1];
		return m;
	}

	/** Balanço da visão como o GameRenderer.bobView: translação em x/y e rotações em z e x. */
	static float[] bob(float tx, float ty, float rzDeg, float rxDeg) {
		float[] t = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, tx, ty, 0, 1};
		double z = Math.toRadians(rzDeg), x = Math.toRadians(rxDeg);
		float cz = (float) Math.cos(z), sz = (float) Math.sin(z), cx = (float) Math.cos(x), sx = (float) Math.sin(x);
		float[] rz = {cz, sz, 0, 0, -sz, cz, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
		float[] rx = {1, 0, 0, 0, 0, cx, sx, 0, 0, -sx, cx, 0, 0, 0, 0, 1};
		return mul(mul(t, rz), rx);
	}

	@Test
	void replaceDepthRowKeepsBobAndSwapsOnlyDepth() {
		float[] b = bob(0.04f, -0.07f, 1.8f, 2.6f);
		float[] game = mul(perspective(1.2f, 16 / 9f, 0.05f, 512), b);
		float[] lod = game.clone();
		LodMath.replaceDepthRow(lod, 4, 4096);
		float[] expected = mul(perspective(1.2f, 16 / 9f, 4, 4096), b);
		for (int i = 0; i < 16; i++) assertEquals(expected[i], lod[i], 1e-4, "elemento " + i);
		// x, y e w (o que decide onde aparece no ecrã) ficam iguais aos do jogo
		for (int c = 0; c < 4; c++) {
			assertEquals(game[c * 4], lod[c * 4], 0);
			assertEquals(game[c * 4 + 1], lod[c * 4 + 1], 0);
			assertEquals(game[c * 4 + 3], lod[c * 4 + 3], 0);
		}
	}

	@Test
	void replaceDepthRowWithoutBobIsThePlainPerspective() {
		float[] m = perspective(1.2f, 1.5f, 0.05f, 512);
		LodMath.replaceDepthRow(m, 4, 2048);
		float[] e = perspective(1.2f, 1.5f, 4, 2048);
		for (int i = 0; i < 16; i++) assertEquals(e[i], m[i], 1e-5);
	}

	@Test
	void stepNearSeamIsFinestAndDoublesGradually() {
		assertEquals(2, LodMath.stepForSeam(0, 1));
		assertEquals(2, LodMath.stepForSeam(40, 1));
		int prev = 2;
		for (int d = 0; d < 3000; d += 8) {
			int s = LodMath.stepForSeam(d, 1);
			assertTrue(s == prev || s == prev * 2, "sem saltos maiores que ×2");
			prev = s;
		}
		assertEquals(16, prev);
	}

	@Test
	void skyLightIsWhiteByDayAndDarkerAtNight() {
		float[] one = {1, 1, 1}, ambNight = {0.04f, 0.04f, 0.04f}, zero = {0, 0, 0}, out = new float[3];
		LodMath.skyLight(ambNight, zero, 0, one, 1f, 0, 0, 0.5f, out);
		assertEquals(1f, out[0], 1e-6);
		LodMath.skyLight(ambNight, zero, 0, one, 0.2f, 0, 0, 0f, out);
		assertEquals(0.24f, out[1], 1e-5);
		float dark = out[1];
		LodMath.skyLight(ambNight, zero, 0, one, 0.2f, 0, 0, 1f, out);
		assertTrue(out[1] > dark, "o brilho (gamma) clareia como no jogo");
	}
}
