package com.tcordeiro.horizonte;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 32×32 chunks de resumo: altura e cor de cada amostra base (2×2 blocos). Usado só pela linha de trabalho do Horizonte (não é
 * thread-safe). Em disco: «THZ1», versão, máscara dos chunks presentes e, para cada chunk presente, 64 alturas + 64 cores,
 * comprimido com Deflate.
 */
public final class LodRegion {
	public static final int MAGIC = 0x54485A31; // "THZ1"
	public static final int VERSION = 1;
	static final int N = LodMath.REGION_SAMPLES;

	public final int rx, rz;
	final short[] height = new short[N * N];
	final int[] color = new int[N * N];
	final long[] present = new long[LodMath.REGION_CHUNKS * LodMath.REGION_CHUNKS / 64];
	public boolean dirty;
	public long lastUse;

	public LodRegion(int rx, int rz) {
		this.rx = rx;
		this.rz = rz;
		Arrays.fill(height, LodMath.NONE);
	}

	/** Chunk local (0..31) presente? */
	public boolean hasChunk(int lcx, int lcz) {
		int i = lcz * LodMath.REGION_CHUNKS + lcx;
		return (present[i >> 6] & (1L << (i & 63))) != 0;
	}

	public int chunkCount() {
		int n = 0;
		for (long l : present) n += Long.bitCount(l);
		return n;
	}

	/** Grava as 8×8 amostras do chunk local (lcx,lcz). */
	public void setChunk(int lcx, int lcz, short[] h8, int[] c8) {
		int S = LodMath.CHUNK_SAMPLES;
		for (int z = 0; z < S; z++) {
			int row = (lcz * S + z) * N + lcx * S;
			System.arraycopy(h8, z * S, height, row, S);
			System.arraycopy(c8, z * S, color, row, S);
		}
		int i = lcz * LodMath.REGION_CHUNKS + lcx;
		present[i >> 6] |= 1L << (i & 63);
		dirty = true;
	}

	/** Altura da amostra base local (0..255). */
	public short height(int sx, int sz) {
		return height[sz * N + sx];
	}

	public int color(int sx, int sz) {
		return color[sz * N + sx];
	}

	public byte[] encode() throws IOException {
		ByteArrayOutputStream bo = new ByteArrayOutputStream(16 * 1024);
		try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bo))) {
			out.writeInt(MAGIC);
			out.writeInt(VERSION);
			for (long l : present) out.writeLong(l);
			int S = LodMath.CHUNK_SAMPLES;
			for (int lcz = 0; lcz < LodMath.REGION_CHUNKS; lcz++) {
				for (int lcx = 0; lcx < LodMath.REGION_CHUNKS; lcx++) {
					if (!hasChunk(lcx, lcz)) continue;
					for (int z = 0; z < S; z++) {
						int row = (lcz * S + z) * N + lcx * S;
						for (int x = 0; x < S; x++) {
							out.writeShort(height[row + x]);
							out.writeInt(color[row + x]);
						}
					}
				}
			}
		}
		return bo.toByteArray();
	}

	public static LodRegion decode(int rx, int rz, byte[] data) throws IOException {
		LodRegion r = new LodRegion(rx, rz);
		try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
			if (in.readInt() != MAGIC) throw new IOException("ficheiro LOD inválido");
			int v = in.readInt();
			if (v != VERSION) throw new IOException("versão LOD desconhecida: " + v);
			for (int i = 0; i < r.present.length; i++) r.present[i] = in.readLong();
			int S = LodMath.CHUNK_SAMPLES;
			for (int lcz = 0; lcz < LodMath.REGION_CHUNKS; lcz++) {
				for (int lcx = 0; lcx < LodMath.REGION_CHUNKS; lcx++) {
					if (!r.hasChunk(lcx, lcz)) continue;
					for (int z = 0; z < S; z++) {
						int row = (lcz * S + z) * N + lcx * S;
						for (int x = 0; x < S; x++) {
							r.height[row + x] = in.readShort();
							r.color[row + x] = in.readInt();
						}
					}
				}
			}
		}
		return r;
	}

	/** Bytes aproximados em memória. */
	public static long memoryBytes() {
		return (long) N * N * 6 + 256;
	}
}
