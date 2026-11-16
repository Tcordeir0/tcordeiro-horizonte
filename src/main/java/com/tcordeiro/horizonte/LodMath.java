package com.tcordeiro.horizonte;

/**
 * Contas do LOD (Java puro, testado em JUnit).
 * <p>
 * Unidades: uma «amostra base» cobre 2×2 blocos, um chunk tem 8×8 amostras base. Um «mosaico» (tile) de desenho tem 8×8 chunks
 * (128×128 blocos) e é desenhado com um passo de 2, 4, 8 ou 16 blocos por célula consoante a distância. Uma «região» de disco tem
 * 32×32 chunks (4×4 mosaicos).
 */
public final class LodMath {
	private LodMath() {}

	/** Altura «sem dados». */
	public static final short NONE = Short.MIN_VALUE;
	/** Blocos por amostra base. */
	public static final int BASE = 2;
	/** Amostras base por lado de chunk. */
	public static final int CHUNK_SAMPLES = 16 / BASE;
	public static final int TILE_CHUNKS = 8;
	public static final int TILE_BLOCKS = TILE_CHUNKS * 16;
	public static final int REGION_CHUNKS = 32;
	/** Amostras base por lado de região. */
	public static final int REGION_SAMPLES = REGION_CHUNKS * CHUNK_SAMPLES;
	/** Passos possíveis (blocos por célula). */
	public static final int[] STEPS = {2, 4, 8, 16};

	public static long key(int x, int z) {
		return ((long) x << 32) | (z & 0xffffffffL);
	}

	public static int keyX(long k) {
		return (int) (k >> 32);
	}

	public static int keyZ(long k) {
		return (int) k;
	}

	/**
	 * 16×16 colunas → 8×8 amostras: em cada 2×2 fica a coluna mais alta (com a cor dela), para os picos e as copas das árvores não
	 * desaparecerem ao longe. Colunas sem dados ({@link #NONE}) só ganham se as quatro estiverem vazias.
	 */
	public static void downsample(short[] h16, int[] c16, short[] h8, int[] c8) {
		for (int z = 0; z < CHUNK_SAMPLES; z++) {
			for (int x = 0; x < CHUNK_SAMPLES; x++) {
				short best = NONE;
				int col = 0;
				for (int dz = 0; dz < 2; dz++) {
					for (int dx = 0; dx < 2; dx++) {
						int i = (z * 2 + dz) * 16 + x * 2 + dx;
						if (h16[i] != NONE && (best == NONE || h16[i] > best)) {
							best = h16[i];
							col = c16[i];
						}
					}
				}
				h8[z * CHUNK_SAMPLES + x] = best;
				c8[z * CHUNK_SAMPLES + x] = col;
			}
		}
	}

	/**
	 * Passo do mosaico (blocos por célula) para a distância horizontal {@code dist} (blocos) do centro do mosaico à câmara.
	 * {@code quality} 0.5 (baixa), 1 (média), 2 (alta).
	 */
	public static int stepFor(double dist, double quality) {
		if (dist < 96 * quality) return 2;
		if (dist < 256 * quality) return 4;
		if (dist < 640 * quality) return 8;
		return 16;
	}

	/**
	 * Passo do mosaico pela distância (blocos) da parte mais perto do mosaico à costura com os chunks normais. Junto à costura usa-se
	 * o passo mais fino (2 blocos, a resolução guardada) numa faixa de ~3 chunks, e depois engrossa-se aos poucos, sempre ×2 de um
	 * nível para o seguinte. {@code quality} 0.5 (baixa), 1 (média), 2 (alta).
	 */
	public static int stepForSeam(double distFromSeam, double quality) {
		if (distFromSeam < 48 * quality) return 2;
		if (distFromSeam < 192 * quality) return 4;
		if (distFromSeam < 512 * quality) return 8;
		return 16;
	}

	/**
	 * Troca só a linha da profundidade (z do clip) de uma projeção do jogo {@code m} (coluna-maior, 16 floats, JOML) pela de uma
	 * perspetiva com {@code near}/{@code far}, mantendo x, y e w. Funciona também com o balanço da visão, a inclinação do dano e a
	 * náusea já multiplicados (o jogo faz P × B, com B afim): a linha w de P × B é −(linha z de B), por isso a linha z nova é
	 * −a·w + b·(0,0,0,1), com {a, b} = {@link #depthRow}.
	 */
	public static void replaceDepthRow(float[] m, float near, float far) {
		float[] r = depthRow(near, far);
		float a = r[0], b = r[1];
		m[2] = -a * m[3];
		m[6] = -a * m[7];
		m[10] = -a * m[11];
		m[14] = -a * m[15] + b;
	}

	/**
	 * Cor da luz do jogo para um bloco a céu aberto (luz do céu 15, sem luz de blocos), igual ao shader {@code lightmap.fsh}: de dia
	 * dá branco, à noite escurece como o terreno normal. Escreve em {@code out} (RGB 0..1).
	 */
	public static void skyLight(float[] ambient, float[] nightVisionColor, float nightVision, float[] skyColor, float skyFactor, float boss,
			float darkness, float brightness, float[] out) {
		for (int i = 0; i < 3; i++) out[i] = Math.max(ambient[i], nightVisionColor[i] * nightVision) + skyColor[i] * skyFactor;
		float[] bossMul = {0.7f, 0.6f, 0.6f};
		float mx = 0;
		for (int i = 0; i < 3; i++) {
			float c = out[i] + (out[i] * bossMul[i] - out[i]) * boss - darkness;
			out[i] = c < 0 ? 0 : c > 1 ? 1 : c;
			mx = Math.max(mx, out[i]);
		}
		if (mx <= 0) return;
		float inv = 1f - mx;
		float scaled = 1f - inv * inv * inv * inv;
		for (int i = 0; i < 3; i++) out[i] = out[i] + (out[i] * (scaled / mx) - out[i]) * brightness;
	}

	/** Neblina linear como a do jogo: 0 antes de {@code start}, 1 a partir de {@code end}. */
	public static float fogFactor(float dist, float start, float end) {
		if (end <= start) return dist >= end ? 1f : 0f;
		float f = (dist - start) / (end - start);
		return f < 0 ? 0 : f > 1 ? 1 : f;
	}

	/**
	 * Linha da profundidade de uma projeção em perspetiva normal do OpenGL (clip z em −1..1, z de vista negativo à frente): devolve
	 * {m22, m32} (JOML, coluna-maior) para {@code near}/{@code far}.
	 */
	public static float[] depthRow(float near, float far) {
		return new float[] {(far + near) / (near - far), 2f * far * near / (near - far)};
	}

	/** Profundidade NDC de um ponto à distância {@code d} à frente da câmara com {@link #depthRow}. */
	public static float ndcDepth(float near, float far, float d) {
		float[] r = depthRow(near, far);
		float clipZ = r[0] * -d + r[1];
		float clipW = d;
		return clipZ / clipW;
	}

	/**
	 * Posições do anel (quadrado) de raio {@code r} à volta de (0,0), em pares x,z. r = 0 devolve só (0,0). Cada posição aparece uma
	 * única vez; juntando os anéis 0..R cobre-se o quadrado (2R+1)².
	 */
	public static int[] ring(int r) {
		if (r == 0) return new int[] {0, 0};
		int n = 8 * r;
		int[] out = new int[n * 2];
		int k = 0;
		for (int x = -r; x <= r; x++) {
			out[k++] = x;
			out[k++] = -r;
			out[k++] = x;
			out[k++] = r;
		}
		for (int z = -r + 1; z <= r - 1; z++) {
			out[k++] = -r;
			out[k++] = z;
			out[k++] = r;
			out[k++] = z;
		}
		return out;
	}

	/** Distância horizontal do ponto (px,pz) ao retângulo [x0,x1]×[z0,z1] (0 se estiver dentro). */
	public static double distToRect(double px, double pz, double x0, double z0, double x1, double z1) {
		double dx = px < x0 ? x0 - px : px > x1 ? px - x1 : 0;
		double dz = pz < z0 ? z0 - pz : pz > z1 ? pz - z1 : 0;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** Multiplica cada canal RGB de {@code rgb} por {@code f} (0..1+), com saturação a 255. */
	public static int shade(int rgb, float f) {
		int r = Math.min(255, (int) (((rgb >> 16) & 255) * f));
		int g = Math.min(255, (int) (((rgb >> 8) & 255) * f));
		int b = Math.min(255, (int) ((rgb & 255) * f));
		return (r << 16) | (g << 8) | b;
	}

	/** RGB → inteiro com os bytes R,G,B,A pela ordem da memória (little-endian), como o OpenGL lê GL_UNSIGNED_BYTE×4. */
	public static int packRgba(int rgb) {
		int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
		return 0xFF000000 | (b << 16) | (g << 8) | r;
	}
}
