package com.tcordeiro.horizonte.client;

import static org.lwjgl.opengl.GL33C.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.tcordeiro.horizonte.Horizonte;
import com.tcordeiro.horizonte.HorizonteConfig;
import com.tcordeiro.horizonte.LodMath;
import com.tcordeiro.horizonte.MeshBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/**
 * Desenha o terreno ao longe só com OpenGL 3.3 core (funciona no 4.1 do Mac): um VAO+VBO por mosaico, um índice partilhado, um
 * shader simples com neblina. Corre no início do passo principal do mundo (depois do céu, antes dos chunks normais): desenha com a
 * sua própria projeção (plano longínquo maior), depois limpa a profundidade para os chunks normais ficarem sempre por cima. Guarda e
 * repõe todo o estado do OpenGL em que mexe.
 */
public final class LodRenderer {
	private LodRenderer() {}

	static final class Tile {
		final int tx, tz;
		int vao, vbo, quads, step = -1, version = -1, minY, maxY;
		long bytes;
		boolean building;

		Tile(int tx, int tz) {
			this.tx = tx;
			this.tz = tz;
		}
	}

	private static final String VERT = """
			#version 330 core
			layout(location = 0) in vec3 aPos;
			layout(location = 1) in vec4 aColor;
			uniform mat4 uProj;
			uniform mat4 uView;
			uniform vec3 uOffset;
			out vec4 vColor;
			out vec3 vRel;
			void main() {
			    vec3 rel = aPos + uOffset;
			    vRel = rel;
			    vColor = aColor;
			    gl_Position = uProj * uView * vec4(rel, 1.0);
			}
			""";
	/** Neblina igual à do jogo (include/fog.glsl): máximo entre a de ambiente (esférica) e a da distância (cilíndrica). */
	private static final String FRAG = """
			#version 330 core
			in vec4 vColor;
			in vec3 vRel;
			uniform float uHole;
			uniform vec4 uFogColor;
			uniform vec4 uFog; // ambiente início/fim, distância início/fim
			out vec4 fragColor;
			float lin(float d, float s, float e) {
			    if (d <= s) return 0.0;
			    if (d >= e) return 1.0;
			    return (d - s) / (e - s);
			}
			void main() {
			    float dxz = length(vRel.xz);
			    if (dxz < uHole) discard;
			    float sph = length(vRel);
			    float cyl = max(dxz, abs(vRel.y));
			    float f = max(lin(sph, uFog.x, uFog.y), lin(cyl, uFog.z, uFog.w));
			    fragColor = vec4(mix(vColor.rgb, uFogColor.rgb, f * uFogColor.a), 1.0);
			}
			""";

	private static boolean initTried, ready;
	private static int program, uProj, uView, uOffset, uHole, uFogColor, uFog;
	private static int ebo, eboQuads;
	private static int fbo, fboColorId = -1, fboDepthId = -1;
	private static final Map<Long, Tile> tiles = new HashMap<>();
	private static final ConcurrentLinkedQueue<MeshBuilder.Mesh> done = new ConcurrentLinkedQueue<>();
	private static int inFlight;
	/** Sobe ao largar tudo: malhas pedidas antes disso são ignoradas quando chegam. */
	private static volatile int generation;
	private static List<long[]> order = List.of();
	private static int orderTx = Integer.MIN_VALUE, orderTz, orderRadius;
	private static LodStore orderStore;

	// estatísticas (para /horizonte e os testes)
	public static volatile int drawnTiles, drawnQuads, liveTiles;
	public static volatile long gpuBytes, lastNanos, frames;
	public static volatile String lastError = "";
	public static volatile float lastFogStart, lastFogEnd, lastEnvStart, lastEnvEnd, lastHole;

	/** Chamado no início do passo principal do mundo (fio de desenho). */
	public static void render(CameraRenderState cam, RenderTarget target) {
		LodStore store = HorizonteClient.store();
		if (store == null || cam == null || !cam.initialized || target == null || !HorizonteClient.renderActive()) {
			drawnTiles = 0;
			drawnQuads = 0;
			return;
		}
		long t0 = System.nanoTime();
		if (!initTried) init();
		if (!ready) return;
		if (orderStore != store) clearTiles();
		HorizonteConfig cfg = HorizonteConfig.get();
		double px = cam.pos.x, py = cam.pos.y, pz = cam.pos.z;
		store.setCamera(px, pz);
		float hole = HorizonteClient.holeRadius();
		float lodEnd = cfg.distance * 16f;
		FogData fog = cam.fogData;
		float envStart = Float.MAX_VALUE, envEnd = Float.MAX_VALUE, fogStart = lodEnd * 0.6f, fogEnd = lodEnd;
		Vector4f fogColor = new Vector4f(0.7f, 0.8f, 1f, 1f);
		if (fog != null) {
			envStart = fog.environmentalStart;
			envEnd = fog.environmentalEnd;
			fogStart = fog.renderDistanceStart;
			fogEnd = fog.renderDistanceEnd;
			if (fog.color != null) fogColor.set(fog.color);
		}
		lastFogStart = fogStart;
		lastFogEnd = fogEnd;
		lastEnvStart = envStart;
		lastEnvEnd = envEnd;
		lastHole = hole;
		if (fogEnd <= hole || envEnd <= hole) { // debaixo de água, cegueira, lava…: não se vê nada ao longe
			drawnTiles = 0;
			drawnQuads = 0;
			return;
		}
		uploadDone(2_000_000L);
		schedule(store, px, pz, lodEnd, hole, cfg.qualityFactor());
		// projeção: x/y/w como o jogo (campo de visão, proporção, balanço da câmara), profundidade normal com plano longínquo maior
		Matrix4f proj = new Matrix4f(cam.projectionMatrix);
		float near = 4f, far = lodEnd + 1024f;
		float[] row = LodMath.depthRow(near, far);
		proj.m02(0).m12(0).m22(row[0]).m32(row[1]).m23(-1).m33(0);
		Matrix4f view = new Matrix4f(cam.viewRotationMatrix);
		FrustumIntersection frustum = new FrustumIntersection(new Matrix4f(proj).mul(view));
		List<Tile> visible = new ArrayList<>();
		int quads = 0;
		for (Tile t : tiles.values()) {
			if (t.quads == 0 || t.vao == 0) continue;
			double x0 = t.tx * (double) LodMath.TILE_BLOCKS - px, z0 = t.tz * (double) LodMath.TILE_BLOCKS - pz;
			double x1 = x0 + LodMath.TILE_BLOCKS, z1 = z0 + LodMath.TILE_BLOCKS;
			// mosaico todo dentro do buraco (chunks normais): não desenhar
			double fx = Math.max(Math.abs(x0), Math.abs(x1)), fz = Math.max(Math.abs(z0), Math.abs(z1));
			if (Math.sqrt(fx * fx + fz * fz) < hole) continue;
			if (LodMath.distToRect(0, 0, x0, z0, x1, z1) > lodEnd) continue;
			if (!frustum.testAab((float) x0, (float) (t.minY - py), (float) z0, (float) x1, (float) (t.maxY + 1 - py), (float) z1)) continue;
			visible.add(t);
			quads += t.quads;
		}
		draw(target, proj, view, visible, px, py, pz, hole, new float[] {envStart, envEnd, fogStart, fogEnd}, fogColor);
		drawnTiles = visible.size();
		drawnQuads = quads;
		frames++;
		lastNanos = System.nanoTime() - t0;
	}

	private static void draw(RenderTarget target, Matrix4f proj, Matrix4f view, List<Tile> visible, double px, double py, double pz,
			float hole, float[] fogParams, Vector4f fogColor) {
		GpuTexture color = target.getColorTexture(), depth = target.getDepthTexture();
		if (!(color instanceof GlTexture gc) || !(depth instanceof GlTexture gd)) {
			fail("o jogo não está a usar OpenGL (" + RenderSystem.getBackendDescription() + ")");
			return;
		}
		// guardar estado
		int prevDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING), prevRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
		int prevProgram = glGetInteger(GL_CURRENT_PROGRAM), prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
		int prevArray = glGetInteger(GL_ARRAY_BUFFER_BINDING);
		int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
		boolean prevDepthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
		float prevClearDepth = glGetFloat(GL_DEPTH_CLEAR_VALUE);
		boolean prevDepthTest = glIsEnabled(GL_DEPTH_TEST), prevBlend = glIsEnabled(GL_BLEND), prevCull = glIsEnabled(GL_CULL_FACE);
		boolean prevScissor = glIsEnabled(GL_SCISSOR_TEST), prevOffset = glIsEnabled(GL_POLYGON_OFFSET_FILL);
		int[] prevViewport = new int[4];
		boolean[] prevMask = new boolean[4];
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer vp = stack.mallocInt(4);
			glGetIntegerv(GL_VIEWPORT, vp);
			vp.get(prevViewport);
			ByteBuffer cm = stack.malloc(4);
			glGetBooleanv(GL_COLOR_WRITEMASK, cm);
			for (int i = 0; i < 4; i++) prevMask[i] = cm.get(i) != 0;
		}
		try {
			if (!bindFbo(gc.glId(), gd.glId())) return;
			glViewport(0, 0, target.width, target.height);
			glDisable(GL_SCISSOR_TEST);
			glDisable(GL_BLEND);
			glDisable(GL_CULL_FACE);
			glDisable(GL_POLYGON_OFFSET_FILL);
			glColorMask(true, true, true, true);
			glDepthMask(true);
			glClearDepth(1.0);
			glClear(GL_DEPTH_BUFFER_BIT);
			if (!visible.isEmpty()) {
				glEnable(GL_DEPTH_TEST);
				glDepthFunc(GL_LESS);
				glUseProgram(program);
				try (MemoryStack stack = MemoryStack.stackPush()) {
					FloatBuffer m = stack.mallocFloat(16);
					glUniformMatrix4fv(uProj, false, proj.get(m));
					glUniformMatrix4fv(uView, false, view.get(m));
				}
				glUniform1f(uHole, hole);
				glUniform4f(uFogColor, fogColor.x, fogColor.y, fogColor.z, fogColor.w);
				glUniform4f(uFog, fogParams[0], fogParams[1], fogParams[2], fogParams[3]);
				for (Tile t : visible) {
					glUniform3f(uOffset, (float) (t.tx * (double) LodMath.TILE_BLOCKS - px), (float) -py,
							(float) (t.tz * (double) LodMath.TILE_BLOCKS - pz));
					glBindVertexArray(t.vao);
					glDrawElements(GL_TRIANGLES, t.quads * 6, GL_UNSIGNED_INT, 0L);
				}
			}
			// os chunks normais desenham-se por cima de tudo o que é LOD (o jogo usa profundidade invertida: limpa a 0)
			glClearDepth(RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE);
			glClear(GL_DEPTH_BUFFER_BIT);
		} finally {
			glBindVertexArray(prevVao);
			glBindBuffer(GL_ARRAY_BUFFER, prevArray);
			glUseProgram(prevProgram);
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDraw);
			glBindFramebuffer(GL_READ_FRAMEBUFFER, prevRead);
			glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
			glColorMask(prevMask[0], prevMask[1], prevMask[2], prevMask[3]);
			glDepthMask(prevDepthMask);
			glDepthFunc(prevDepthFunc);
			glClearDepth(prevClearDepth);
			set(GL_DEPTH_TEST, prevDepthTest);
			set(GL_BLEND, prevBlend);
			set(GL_CULL_FACE, prevCull);
			set(GL_SCISSOR_TEST, prevScissor);
			set(GL_POLYGON_OFFSET_FILL, prevOffset);
		}
	}

	private static void set(int cap, boolean on) {
		if (on) glEnable(cap);
		else glDisable(cap);
	}

	private static boolean bindFbo(int colorId, int depthId) {
		if (fbo == 0) fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		if (colorId != fboColorId || depthId != fboDepthId) {
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorId, 0);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthId, 0);
			int st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
			if (st != GL_FRAMEBUFFER_COMPLETE) {
				fail("framebuffer incompleto: 0x" + Integer.toHexString(st));
				return false;
			}
			fboColorId = colorId;
			fboDepthId = depthId;
		}
		return true;
	}

	private static void fail(String why) {
		if (!why.equals(lastError)) Horizonte.LOG.error("Horizonte desligado: {}", why);
		lastError = why;
		ready = false;
	}

	private static void init() {
		initTried = true;
		int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING), prevArray = glGetInteger(GL_ARRAY_BUFFER_BINDING);
		int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
		try {
			int vs = compile(GL_VERTEX_SHADER, VERT), fs = compile(GL_FRAGMENT_SHADER, FRAG);
			if (vs == 0 || fs == 0) return;
			program = glCreateProgram();
			glAttachShader(program, vs);
			glAttachShader(program, fs);
			glLinkProgram(program);
			glDeleteShader(vs);
			glDeleteShader(fs);
			if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
				fail("shader não ligou: " + glGetProgramInfoLog(program));
				return;
			}
			uProj = glGetUniformLocation(program, "uProj");
			uView = glGetUniformLocation(program, "uView");
			uOffset = glGetUniformLocation(program, "uOffset");
			uHole = glGetUniformLocation(program, "uHole");
			uFogColor = glGetUniformLocation(program, "uFogColor");
			uFog = glGetUniformLocation(program, "uFog");
			ensureEbo(LodMath.TILE_BLOCKS / 2 * (LodMath.TILE_BLOCKS / 2) * 5);
			ready = true;
			lastError = "";
			Horizonte.LOG.info("Horizonte pronto ({}; GL {})", RenderSystem.getBackendDescription(), glGetString(GL_VERSION));
		} catch (Throwable t) {
			fail("erro a iniciar: " + t);
		} finally {
			glBindVertexArray(prevVao);
			glBindBuffer(GL_ARRAY_BUFFER, prevArray);
			glUseProgram(prevProgram);
		}
	}

	private static int compile(int type, String src) {
		int s = glCreateShader(type);
		glShaderSource(s, src);
		glCompileShader(s);
		if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE) {
			fail("shader não compilou: " + glGetShaderInfoLog(s));
			glDeleteShader(s);
			return 0;
		}
		return s;
	}

	/** Índices partilhados (0,1,2, 2,3,0 por quadrado). Cria-se com um VAO nosso preso, para não mexer no VAO de ninguém. */
	private static void ensureEbo(int quads) {
		if (quads <= eboQuads) return;
		int vao = glGenVertexArrays();
		glBindVertexArray(vao);
		if (ebo == 0) ebo = glGenBuffers();
		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
		IntBuffer ib = MemoryUtil.memAllocInt(quads * 6);
		try {
			for (int q = 0; q < quads; q++) {
				int b = q * 4;
				ib.put(b).put(b + 1).put(b + 2).put(b + 2).put(b + 3).put(b);
			}
			ib.flip();
			glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib, GL_STATIC_DRAW);
		} finally {
			MemoryUtil.memFree(ib);
		}
		glBindVertexArray(0);
		glDeleteVertexArrays(vao);
		eboQuads = quads;
		// os VAOs dos mosaicos guardam o índice: voltar a prendê-lo não é preciso (mesmo nome de buffer)
	}

	/** Envia para a GPU as malhas prontas, até {@code budgetNanos}. */
	private static void uploadDone(long budgetNanos) {
		long t0 = System.nanoTime();
		MeshBuilder.Mesh m;
		while ((m = done.poll()) != null) {
			inFlight = Math.max(0, inFlight - 1);
			Tile t = tiles.get(LodMath.key(m.tx(), m.tz()));
			if (t == null) continue;
			t.building = false;
			upload(t, m);
			if (System.nanoTime() - t0 > budgetNanos) break;
		}
	}

	private static void upload(Tile t, MeshBuilder.Mesh m) {
		t.step = m.step();
		t.minY = m.minY();
		t.maxY = m.maxY();
		if (m.empty()) {
			freeGl(t);
			t.quads = 0;
			return;
		}
		ensureEbo(m.quads());
		if (t.vao == 0) {
			t.vao = glGenVertexArrays();
			t.vbo = glGenBuffers();
			glBindVertexArray(t.vao);
			glBindBuffer(GL_ARRAY_BUFFER, t.vbo);
			glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0L);
			glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, 16, 12L);
			glEnableVertexAttribArray(0);
			glEnableVertexAttribArray(1);
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
		} else {
			glBindVertexArray(t.vao);
			glBindBuffer(GL_ARRAY_BUFFER, t.vbo);
		}
		IntBuffer buf = MemoryUtil.memAllocInt(m.quads() * 16);
		try {
			buf.put(m.data(), 0, m.quads() * 16).flip();
			glBufferData(GL_ARRAY_BUFFER, buf, GL_STATIC_DRAW);
		} finally {
			MemoryUtil.memFree(buf);
		}
		glBindVertexArray(0);
		gpuBytes += (long) m.quads() * 64 - t.bytes;
		t.bytes = (long) m.quads() * 64;
		t.quads = m.quads();
	}

	private static void freeGl(Tile t) {
		if (t.vao != 0) glDeleteVertexArrays(t.vao);
		if (t.vbo != 0) glDeleteBuffers(t.vbo);
		t.vao = 0;
		t.vbo = 0;
		gpuBytes -= t.bytes;
		t.bytes = 0;
	}

	/** Pede malhas novas para os mosaicos que faltam ou mudaram, do mais perto para o mais longe; liberta os que ficaram longe. */
	private static void schedule(LodStore store, double px, double pz, float lodEnd, float hole, double quality) {
		int ctx = Math.floorDiv((int) Math.floor(px), LodMath.TILE_BLOCKS), ctz = Math.floorDiv((int) Math.floor(pz), LodMath.TILE_BLOCKS);
		int radius = (int) Math.ceil(lodEnd / LodMath.TILE_BLOCKS) + 1;
		if (ctx != orderTx || ctz != orderTz || radius != orderRadius || store != orderStore) {
			orderTx = ctx;
			orderTz = ctz;
			orderRadius = radius;
			orderStore = store;
			List<long[]> o = new ArrayList<>();
			for (int r = 0; r <= radius; r++) {
				int[] ring = LodMath.ring(r);
				for (int i = 0; i < ring.length; i += 2) {
					int tx = ctx + ring[i], tz = ctz + ring[i + 1];
					double d = LodMath.distToRect(px, pz, tx * (double) LodMath.TILE_BLOCKS, tz * (double) LodMath.TILE_BLOCKS,
							(tx + 1) * (double) LodMath.TILE_BLOCKS, (tz + 1) * (double) LodMath.TILE_BLOCKS);
					if (d <= lodEnd) o.add(new long[] {tx, tz, (long) d});
				}
			}
			o.sort((a, b) -> Long.compare(a[2], b[2]));
			order = o;
			// largar os que já não estão no alcance
			Iterator<Tile> it = tiles.values().iterator();
			while (it.hasNext()) {
				Tile t = it.next();
				if (Math.max(Math.abs(t.tx - ctx), Math.abs(t.tz - ctz)) > radius + 1 && !t.building) {
					freeGl(t);
					it.remove();
				}
			}
		}
		int maxInFlight = 6;
		for (long[] o : order) {
			if (inFlight >= maxInFlight || store.queued.get() > 64) break;
			int tx = (int) o[0], tz = (int) o[1];
			double cxw = (tx + 0.5) * LodMath.TILE_BLOCKS, czw = (tz + 0.5) * LodMath.TILE_BLOCKS;
			// mosaico todo dentro dos chunks normais: não é preciso
			double fx = Math.max(Math.abs(tx * (double) LodMath.TILE_BLOCKS - px), Math.abs((tx + 1) * (double) LodMath.TILE_BLOCKS - px));
			double fz = Math.max(Math.abs(tz * (double) LodMath.TILE_BLOCKS - pz), Math.abs((tz + 1) * (double) LodMath.TILE_BLOCKS - pz));
			if (Math.sqrt(fx * fx + fz * fz) < hole) continue;
			int step = LodMath.stepFor(Math.hypot(cxw - px, czw - pz), quality);
			long k = LodMath.key(tx, tz);
			Tile t = tiles.computeIfAbsent(k, kk -> new Tile(tx, tz));
			int version = store.tileVersion(tx, tz);
			if (t.building || (t.step == step && t.version == version)) continue;
			t.building = true;
			t.version = version;
			inFlight++;
			int gen = generation;
			store.buildMesh(tx, tz, step).whenComplete((mesh, err) -> {
				if (gen != generation) return;
				if (mesh != null) done.add(mesh);
				else done.add(new MeshBuilder.Mesh(tx, tz, step, new int[0], 0, 0, 0));
			});
		}
		liveTiles = tiles.size();
	}

	/** Larga todas as malhas (troca de mundo/dimensão, ou desligar). */
	public static void clearTiles() {
		generation++;
		for (Tile t : tiles.values()) freeGl(t);
		tiles.clear();
		done.clear();
		inFlight = 0;
		orderTx = Integer.MIN_VALUE;
		orderStore = null;
		gpuBytes = 0;
		liveTiles = 0;
	}

	public static boolean ready() {
		return ready;
	}
}
