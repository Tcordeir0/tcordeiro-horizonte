package com.tcordeiro.horizonte.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import com.tcordeiro.horizonte.HorizonteConfig;
import com.tcordeiro.horizonte.client.FarScanner;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Capturas da costura entre os chunks normais (distância 8) e o terreno ao longe, de pé no chão e de um ponto alto, de dia e de
 * noite. Copia-as para ~/Downloads/Mods_Tcordeiro_testes/horizonte/qualidade_antes_depois/ com o prefixo -Phz.out (antes/depois).
 * Só corre com -PclientOnly=HorizonteCosturaTest.
 */
public class HorizonteCosturaTest implements FabricClientGameTest {
	static final Path OUT = Path.of(System.getProperty("user.home"), "Downloads", "Mods_Tcordeiro_testes", "horizonte", "qualidade_antes_depois");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		String label = System.getProperty("hz.out", "depois");
		ctx.runOnClient(mc -> {
			mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
			mc.options.renderDistance().set(8);
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
		try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("tcordeiro-horizonte")).create()) {
			for (String c : new String[] {"time set 6000", "gamerule advance_time false", "gamerule spawn_mobs false", "gamerule advance_weather false",
					"gamerule send_command_feedback false", "gamerule show_advancement_messages false", "weather clear", "gamemode spectator @a"})
				sp.getServer().runCommand(c);
			int ground = sp.getServer().computeOnServer(s -> s.overworld().getChunk(0, 0).getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0));
			double y = Math.max(ground, 63);
			sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a 0.5 %.1f 0.5 180 2", y + 2));
			waitChunks(ctx, sp);
			long t0 = System.currentTimeMillis();
			while (!FarScanner.finished && System.currentTimeMillis() - t0 < 300_000) ctx.waitTicks(100);
			ctx.runOnClient(mc -> HorizonteConfig.get().generate = false);
			ctx.waitTicks(200);
			int[] yaws = {180, 90, 0, 270};
			for (int yaw : yaws) {
				pose(ctx, 0.5, y + 2, 0.5, yaw, 3);
				ctx.waitTicks(40);
				shot(ctx, label + "_chao_yaw" + yaw);
			}
			pose(ctx, 0.5, y + 45, 0.5, 180, 18);
			ctx.waitTicks(60);
			shot(ctx, label + "_alto_norte");
			pose(ctx, 0.5, y + 45, 0.5, 90, 18);
			ctx.waitTicks(40);
			shot(ctx, label + "_alto_oeste");
			sp.getServer().runCommand("time set 13800");
			pose(ctx, 0.5, y + 2, 0.5, 180, 3);
			ctx.waitTicks(60);
			shot(ctx, label + "_entardecer");
			sp.getServer().runCommand("time set 18000");
			ctx.waitTicks(60);
			shot(ctx, label + "_noite");
		}
	}

	static void shot(ClientGameTestContext ctx, String name) {
		Path p = ctx.takeScreenshot(name);
		try {
			Files.createDirectories(OUT);
			Files.copy(p, OUT.resolve(name + ".png"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	static void waitChunks(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		try {
			sp.getConnection().waitForChunksRender();
		} catch (AssertionError e) {
			ctx.waitTicks(200);
		}
	}

	static void pose(ClientGameTestContext ctx, double x, double y, double z, double yaw, double pitch) {
		ctx.runOnClient(mc -> mc.player.snapTo(x, y, z, (float) yaw, (float) pitch));
	}
}
