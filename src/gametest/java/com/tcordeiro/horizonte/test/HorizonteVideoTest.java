package com.tcordeiro.horizonte.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.tcordeiro.horizonte.HorizonteConfig;
import com.tcordeiro.horizonte.client.FarScanner;
import com.tcordeiro.horizonte.client.HorizonteClient;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Vídeo: um fotograma por tick (20 fps). 1) 15 s a voar devagar ~30 blocos acima do chão, a girar 360°, LIGADO; 2) 8 s o mesmo
 * DESLIGADO; 3) 6 s parado a alternar com F7; 4) 6 s a subir. Os fotogramas e frames.txt (estado de cada fotograma) ficam em
 * build/clientgametest/screenshots; o .mp4 faz-se fora (ffmpeg). Só corre com -PclientOnly=HorizonteVideoTest.
 */
public class HorizonteVideoTest implements FabricClientGameTest {
	final List<String> states = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
			mc.options.languageCode = "pt_br";
			mc.getLanguageManager().setSelected("pt_br");
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
		ctx.runOnClient(Minecraft::reloadResourcePacks);
		ctx.waitTicks(20);
		try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("tcordeiro-horizonte")).create()) {
			for (String c : new String[] {"time set 6000", "gamerule advance_time false", "gamerule spawn_mobs false", "gamerule advance_weather false",
					"gamerule send_command_feedback false", "gamerule show_advancement_messages false", "weather clear", "gamemode spectator @a"})
				sp.getServer().runCommand(c);
			int ground = sp.getServer().computeOnServer(s -> s.overworld().getChunk(0, 0).getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0));
			double y = Math.max(ground, 63) + 30;
			sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a 0.5 %.1f 0.5 180 8", y));
			try {
				sp.getConnection().waitForChunksRender();
			} catch (AssertionError e) {
				ctx.waitTicks(200);
			}
			long t0 = System.currentTimeMillis();
			while (!FarScanner.finished && System.currentTimeMillis() - t0 < 300_000) ctx.waitTicks(100);
			ctx.runOnClient(mc -> HorizonteConfig.get().generate = false);
			ctx.waitTicks(100);
			// 1) ligado: avança para norte a girar 360°
			fly(ctx, true, 300, y, 0);
			// 2) desligado: a mesma volta (mais curta)
			fly(ctx, false, 160, y, 0);
			// 3) parado a olhar para norte, F7 de 1,5 em 1,5 s
			ctx.runOnClient(mc -> HorizonteConfig.get().enabled = true);
			pose(ctx, 0.5, y, 0.5, 180, 8);
			ctx.waitTicks(40);
			for (int i = 0; i < 120; i++) {
				if (i > 0 && i % 30 == 0) ctx.getInput().pressKey(HorizonteClient.toggleKey);
				frame(ctx);
			}
			ctx.runOnClient(mc -> HorizonteConfig.get().enabled = true);
			ctx.waitTicks(20);
			// 4) a subir 90 blocos, a girar devagar
			for (int i = 0; i < 120; i++) {
				double t = i / 119.0;
				pose(ctx, 0.5, y + 90 * t, 0.5, 180 + 60 * t, 8 + 17 * t);
				frame(ctx);
			}
			ctx.runOnClient(mc -> {
				try {
					Files.write(mc.gameDirectory.toPath().resolve("screenshots").resolve("frames.txt"), states);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		}
	}

	void fly(ClientGameTestContext ctx, boolean on, int frames, double y, double z0) {
		ctx.runOnClient(mc -> HorizonteConfig.get().enabled = on);
		pose(ctx, 0.5, y, z0 + 0.5, 180, 8);
		ctx.waitTicks(30);
		for (int i = 0; i < frames; i++) {
			double t = i / (double) frames;
			pose(ctx, 0.5, y, z0 + 0.5 - 0.25 * i, 180 + 360 * t, 8);
			frame(ctx);
		}
	}

	static void pose(ClientGameTestContext ctx, double x, double y, double z, double yaw, double pitch) {
		ctx.runOnClient(mc -> mc.player.snapTo(x, y, z, (float) yaw, (float) pitch));
	}

	void frame(ClientGameTestContext ctx) {
		ctx.waitTick();
		boolean on = ctx.computeOnClient(mc -> HorizonteConfig.get().enabled);
		Path p = ctx.takeScreenshot(String.format(Locale.ROOT, "video_%04d", states.size()));
		states.add(p.getFileName() + " " + (on ? "ligado" : "desligado"));
	}
}
