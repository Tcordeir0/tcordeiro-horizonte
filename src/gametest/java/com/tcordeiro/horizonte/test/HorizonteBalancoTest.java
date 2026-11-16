package com.tcordeiro.horizonte.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import com.tcordeiro.horizonte.Horizonte;
import com.tcordeiro.horizonte.HorizonteConfig;
import com.tcordeiro.horizonte.client.FarScanner;
import com.tcordeiro.horizonte.client.HorizonteClient;
import com.tcordeiro.horizonte.client.LodRenderer;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * Balanço da visão: a andar/correr no chão com bobView ligado, compara em cada frame a projeção que o LOD usou com a que o jogo
 * usa para os chunks normais (refeita aqui à mão a partir do estado da câmara, como em GameRenderer.bobView) e mede o desvio em
 * píxeis de pontos na costura. Faz o percurso duas vezes: «antes» (projeção antiga, sem balanço) e «depois». Um fotograma por
 * tick em build/clientgametest/screenshots (balanco_antes_*, balanco_depois_*) para o vídeo. Só corre com
 * -PclientOnly=HorizonteBalancoTest.
 */
public class HorizonteBalancoTest implements FabricClientGameTest {
	static final Path OUT = Path.of(System.getProperty("user.home"), "Downloads", "Mods_Tcordeiro_testes", "horizonte");
	static final int TICKS = 100;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
			mc.options.renderDistance().set(8);
			mc.options.bobView().set(true);
			mc.options.autoJump().set(true);
			HorizonteConfig c = HorizonteConfig.get();
			c.enabled = true;
			c.distance = 40;
			c.generate = true;
			c.generateRadius = 40;
			c.generateInFlight = 8;
			c.generateMaxMspt = 80;
		});
		ctx.getInput().resizeWindow(1280, 720);
		ctx.waitTicks(20);
		List<String> report = new ArrayList<>();
		try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("tcordeiro-horizonte")).create()) {
			for (String c : new String[] {"time set 6000", "gamerule advance_time false", "gamerule spawn_mobs false", "gamerule advance_weather false",
					"gamerule send_command_feedback false", "gamerule show_advancement_messages false", "weather clear", "gamemode creative @a"})
				sp.getServer().runCommand(c);
			// passadiço de pedra no ar (y=110), 90 blocos para norte: vista livre da costura enquanto se corre
			double y = 111;
			sp.getServer().runCommand("fill -1 110 -4 1 110 -94 stone");
			sp.getServer().runCommand("fill -1 111 -4 1 116 -94 air");
			sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a 0.5 %.1f -7.5 180 12", y));
			try {
				sp.getConnection().waitForChunksRender();
			} catch (AssertionError e) {
				ctx.waitTicks(200);
			}
			long t0 = System.currentTimeMillis();
			while (!FarScanner.finished && System.currentTimeMillis() - t0 < 300_000) ctx.waitTicks(100);
			ctx.runOnClient(mc -> HorizonteConfig.get().generate = false);
			ctx.waitTicks(100);
			for (boolean legacy : new boolean[] {true, false}) {
				String tag = legacy ? "antes" : "depois";
				ctx.runOnClient(mc -> LodRenderer.legacyProjection = legacy);
				sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a 0.5 %.1f -7.5 180 12", y));
				ctx.waitTicks(40);
				ctx.getInput().holdKey(o -> o.keyUp);
				ctx.getInput().holdKey(o -> o.keySprint);
				double max = 0, sum = 0, maxBob = 0;
				int n = 0;
				for (int i = 0; i < TICKS; i++) {
					ctx.waitTick();
					double[] e = ctx.computeOnClient(HorizonteBalancoTest::seamError);
					if (e[1] > 0.001) {
						max = Math.max(max, e[0]);
						sum += e[0];
						n++;
					}
					maxBob = Math.max(maxBob, e[1]);
					ctx.takeScreenshot(String.format(Locale.ROOT, "balanco_%s_%04d", tag, i));
				}
				ctx.getInput().releaseKey(o -> o.keyUp);
				ctx.getInput().releaseKey(o -> o.keySprint);
				String line = String.format(Locale.ROOT, "%-6s: %d frames com balanço (bob máx %.3f); desvio LOD vs chunks na costura: máx %.2f px, médio %.2f px",
						tag, n, maxBob, max, n == 0 ? 0 : sum / n);
				report.add(line);
				Horizonte.LOG.info("[balanço] {}", line);
			}
			ctx.runOnClient(mc -> LodRenderer.legacyProjection = false);
		}
		try {
			Files.createDirectories(OUT);
			Files.write(OUT.resolve("balanco_medicao.txt"), report);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * {desvio máximo em píxeis, intensidade do balanço}. A projeção «do jogo» refaz-se aqui à mão (câmara × bobHurt × bobView,
	 * como no GameRenderer) para não depender do que o mod apanhou.
	 */
	static double[] seamError(Minecraft mc) {
		CameraRenderState cam = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		CameraEntityRenderState es = cam.entityRenderState;
		Matrix4f game = new Matrix4f(cam.projectionMatrix);
		Matrix4f bob = new Matrix4f();
		if (es.isPlayer && mc.options.bobView().get()) {
			float w = es.backwardsInterpolatedWalkDistance, b = es.bob;
			bob.translate(Mth.sin(w * (float) Math.PI) * b * 0.5f, -Math.abs(Mth.cos(w * (float) Math.PI) * b), 0f);
			bob.rotateZ((float) Math.toRadians(Mth.sin(w * (float) Math.PI) * b * 3.0f));
			bob.rotateX((float) Math.toRadians(Math.abs(Mth.cos(w * (float) Math.PI - 0.2f) * b) * 5.0f));
		}
		game.mul(bob);
		Matrix4f lod = new Matrix4f(LodRenderer.lastProj);
		float d = HorizonteClient.holeRadius();
		int wpx = mc.getWindow().getWidth(), hpx = mc.getWindow().getHeight();
		double max = 0;
		for (float fx = -0.5f; fx <= 0.5f; fx += 0.25f) {
			for (float fy = -0.1f; fy <= 0.1f; fy += 0.1f) {
				Vector4f p = new Vector4f(fx * d, fy * d, -d, 1);
				Vector4f a = game.transform(new Vector4f(p)), c = lod.transform(new Vector4f(p));
				double dx = (a.x / a.w - c.x / c.w) * wpx / 2, dy = (a.y / a.w - c.y / c.w) * hpx / 2;
				max = Math.max(max, Math.hypot(dx, dy));
			}
		}
		return new double[] {max, es.bob};
	}
}
