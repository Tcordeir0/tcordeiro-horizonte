package com.tcordeiro.horizonte;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;

import org.junit.jupiter.api.Test;

class MeshAndRegionTest {
	/** Terreno de teste definido por funções (coordenadas de amostra base). */
	record Fn(java.util.function.IntBinaryOperator h, java.util.function.IntBinaryOperator c) implements MeshBuilder.SampleSource {
		@Override
		public short height(int gx, int gz) {
			return (short) h.applyAsInt(gx, gz);
		}

		@Override
		public int color(int gx, int gz) {
			return c.applyAsInt(gx, gz);
		}
	}

	@Test
	void flatTerrainMergesRowsAndHasNoWalls() {
		MeshBuilder b = new MeshBuilder();
		for (int step : LodMath.STEPS) {
			MeshBuilder.Mesh m = b.build(new Fn((x, z) -> 64, (x, z) -> 0x55AA55), 3, -2, step);
			int n = LodMath.TILE_BLOCKS / step;
			assertEquals(n * (LodMath.TILE_BLOCKS / MeshBuilder.MAX_RUN_BLOCKS), m.quads(), "faixas de " + MeshBuilder.MAX_RUN_BLOCKS + " blocos no passo " + step);
			// nenhum topo mais comprido do que o limite
			for (int q = 0; q < m.quads(); q++) {
				float xa = Float.intBitsToFloat(m.data()[q * 16]), xc = Float.intBitsToFloat(m.data()[q * 16 + 8]);
				assertTrue(Math.abs(xc - xa) <= MeshBuilder.MAX_RUN_BLOCKS);
			}
			assertEquals(64, m.minY());
			assertEquals(64, m.maxY());
			// a faixa cobre o mosaico de lado a lado
			float maxX = 0;
			for (int v = 0; v < m.quads() * 4; v++) maxX = Math.max(maxX, Float.intBitsToFloat(m.data()[v * 4]));
			assertEquals(LodMath.TILE_BLOCKS, maxX, 1e-6);
		}
	}

	@Test
	void singlePillarHasTopAndFourWalls() {
		MeshBuilder b = new MeshBuilder();
		// pilar de 2×2 blocos (1 amostra) em (10,10) com 80 de altura; resto a 64
		MeshBuilder.Mesh m = b.build(new Fn((x, z) -> x == 10 && z == 10 ? 80 : 64, (x, z) -> 0x808080), 0, 0, 2);
		int rows = LodMath.TILE_BLOCKS / 2;
		// linhas planas em faixas de 32 blocos (4 por linha) + a faixa do pilar parte-se em 3 (antes, pilar, depois) + 4 paredes
		int perRow = LodMath.TILE_BLOCKS / MeshBuilder.MAX_RUN_BLOCKS;
		assertEquals((rows - 1) * perRow + (perRow - 1) + 3 + 4, m.quads());
		assertEquals(80, m.maxY());
	}

	@Test
	void missingDataMakesNoGeometry() {
		MeshBuilder b = new MeshBuilder();
		MeshBuilder.Mesh m = b.build(new Fn((x, z) -> LodMath.NONE, (x, z) -> 0), 0, 0, 4);
		assertTrue(m.empty());
	}

	@Test
	void coarseStepUsesHighestSample() {
		MeshBuilder b = new MeshBuilder();
		// no passo 16 cada célula junta 8×8 amostras: um pico numa delas sobe a célula inteira
		MeshBuilder.Mesh m = b.build(new Fn((x, z) -> x == 3 && z == 5 ? 120 : 60, (x, z) -> 0), 0, 0, 16);
		assertEquals(120, m.maxY());
	}

	@Test
	void regionEncodeDecodeRoundTrip() throws Exception {
		LodRegion r = new LodRegion(-3, 7);
		Random rnd = new Random(42);
		short[] h = new short[64];
		int[] c = new int[64];
		for (int k = 0; k < 50; k++) {
			int lcx = rnd.nextInt(32), lcz = rnd.nextInt(32);
			for (int i = 0; i < 64; i++) {
				h[i] = (short) (rnd.nextInt(400) - 64);
				c[i] = rnd.nextInt(0x1000000);
			}
			r.setChunk(lcx, lcz, h, c);
		}
		byte[] data = r.encode();
		LodRegion d = LodRegion.decode(-3, 7, data);
		assertEquals(r.chunkCount(), d.chunkCount());
		for (int z = 0; z < LodMath.REGION_SAMPLES; z++)
			for (int x = 0; x < LodMath.REGION_SAMPLES; x++) {
				assertEquals(r.height(x, z), d.height(x, z));
				if (r.height(x, z) != LodMath.NONE) assertEquals(r.color(x, z), d.color(x, z));
			}
		assertTrue(data.length < 50 * 64 * 6 + 1024, "comprimido: " + data.length);
	}

	@Test
	void regionChunkPlacement() {
		LodRegion r = new LodRegion(0, 0);
		short[] h = new short[64];
		int[] c = new int[64];
		for (int i = 0; i < 64; i++) h[i] = (short) i;
		r.setChunk(31, 2, h, c);
		assertTrue(r.hasChunk(31, 2));
		assertFalse(r.hasChunk(30, 2));
		assertEquals(0, r.height(31 * 8, 2 * 8));
		assertEquals(63, r.height(31 * 8 + 7, 2 * 8 + 7));
		assertEquals(LodMath.NONE, r.height(0, 0));
	}

	@Test
	void decodeRejectsGarbage() {
		assertThrows(Exception.class, () -> LodRegion.decode(0, 0, new byte[] {1, 2, 3}));
	}
}
