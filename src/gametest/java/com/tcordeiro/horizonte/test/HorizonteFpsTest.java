package com.tcordeiro.horizonte.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import com.tcordeiro.horizonte.Horizonte;
import com.tcordeiro.horizonte.HorizonteConfig;
import com.tcordeiro.horizonte.client.FarScanner;
import com.tcordeiro.horizonte.client.LodRenderer;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * FPS real (tempo entre frames do ciclo do jogo, VSync desligado, sem limite) com os mods do jogo do Talys. Cenários A (8 sem
 * Horizonte), B (8 com), C (6 com), D (5 com), E (4 com), cada um a mesma rota (voo para norte a girar 360°), repetidos 2× em
 * alternância. Só corre com -PclientOnly=HorizonteFpsTest.
 */
public class HorizonteFpsTest implements FabricClientGameTest {
	static final Path OUT = Path.of(System.getProperty("user.home"), "Downloads", "Mods_Tcordeiro_testes", "horizonte");
	static final Path HORIZON_PACKS = Path.of(System.getProperty("user.home"), "Library", "Application Support", "horizon", "instances", "fabric", "resourcepacks");
	static final String[] PACKS = {"FreshAnimations_v1.11.2_26.1+.zip", "FA+All_Extensions-v1.10.1.zip"};

	record Scenario(String id, int rd, boolean on) {}

	record Result(String id, int rd, boolean on, int rep, double fps, double low1, double ms, int frames, double heapMb, double lodMs, int tiles, int quads) {}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		int secs = Integer.getInteger("hz.fpsSecs", 60);
		int dist = Integer.getInteger("hz.dist", 64);
		boolean emf = FabricLoader.getInstance().isModLoaded("entity_model_features");
		List<String> mods = new ArrayList<>();
		for (var m : FabricLoader.getInstance().getAllMods()) {
			String id = m.getMetadata().getId();
			if (id.startsWith("fabric") || id.equals("java") || id.equals("minecraft") || id.startsWith("thorizonte") || id.equals("mixinextras")) continue;
			if (m.getContainingMod().isPresent()) continue;
			mods.add(id);
		}
		ctx.runOnClient(mc -> {
			Options o = mc.options;
			o.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
			o.languageCode = "pt_br";
			mc.getLanguageManager().setSelected("pt_br");
			o.enableVsync().set(false);
			o.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF);
			o.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
			HorizonteConfig c = HorizonteConfig.get();
			c.enabled = true;
			c.distance = dist;
			c.generate = true;
			c.generateRadius = dist;
			c.generateInFlight = 8;
			c.generateMaxMspt = 80;
			if (emf) {
				try {
					Path dir = mc.getResourcePackDirectory();
					Files.createDirectories(dir);
					for (String p : PACKS) Files.copy(HORIZON_PACKS.resolve(p), dir.resolve(p), StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				var repo = mc.getResourcePackRepository();
				repo.reload();
				List<String> sel = new ArrayList<>(repo.getSelectedIds());
				for (String p : PACKS) if (!sel.contains("file/" + p)) sel.add("file/" + p);
				repo.setSelected(sel);
			}
		});
		ctx.getInput().resizeWindow(1280, 720);
		ctx.runOnClient(Minecraft::reloadResourcePacks);
		ctx.waitTicks(60);
		List<Result> results = new ArrayList<>();
		List<String> log = new ArrayList<>();
		try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("tcordeiro-horizonte")).create()) {
			for (String c : new String[] {"time set 6000", "gamerule advance_time false", "gamerule spawn_mobs false", "gamerule advance_weather false",
					"gamerule send_command_feedback false", "gamerule show_advancement_messages false", "weather clear", "gamemode spectator @a"})
				sp.getServer().runCommand(c);
			int ground = sp.getServer().computeOnServer(s -> s.overworld().getChunk(0, 0).getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0));
			double y = Math.max(ground, 63) + 30;
			// preparar o terreno ao longe (cache) com distância 8
			ctx.runOnClient(mc -> mc.options.renderDistance().set(8));
			pose(ctx, 0.5, y, 0.5, 180, 8);
			waitChunks(ctx, sp);
			long t0 = System.currentTimeMillis();
			while (!FarScanner.finished && System.currentTimeMillis() - t0 < 480_000) ctx.waitTicks(100);
			log.add(String.format(Locale.ROOT, "preparação do LOD (%d chunks): %s em %.0f s", dist, FarScanner.finished ? "completa" : "INCOMPLETA",
					(System.currentTimeMillis() - t0) / 1000.0));
			ctx.runOnClient(mc -> HorizonteConfig.get().generate = false);
			Scenario[] sc = {new Scenario("A", 8, false), new Scenario("B", 8, true), new Scenario("C", 6, true), new Scenario("D", 5, true),
					new Scenario("E", 4, true)};
			for (int rep = 1; rep <= 2; rep++) {
				for (Scenario s : sc) {
					ctx.runOnClient(mc -> {
						mc.options.renderDistance().set(s.rd());
						HorizonteConfig.get().enabled = s.on();
					});
					pose(ctx, 0.5, y, 0.5, 180, 8);
					ctx.waitTicks(20);
					waitChunks(ctx, sp);
					ctx.waitTicks(100);
					if (rep == 1) ctx.takeScreenshot("fps_" + s.id() + "_rd" + s.rd() + (s.on() ? "_horizonte" : "_sem"));
					Result res = null;
					for (int attempt = 1; attempt <= 3; attempt++) {
						res = measure(ctx, s, rep, secs, y);
						// janela tapada/escondida: o macOS deixa o jogo a 1 frame por tick (20 FPS); essa medição não conta
						if (res.frames() > secs * 20 * 1.2) break;
						log.add(String.format(Locale.ROOT, "%s rep %d: %d frames em %d s (janela travada a 20 FPS) — medição repetida", s.id(), rep, res.frames(), secs));
						pose(ctx, 0.5, y, 0.5, 180, 8);
						ctx.waitTicks(100);
					}
					results.add(res);
					Horizonte.LOG.info("[fps] {}", res);
				}
			}
		}
		String report = report(secs, dist, mods, emf, log, results);
		ctx.runOnClient(mc -> save(mc, report));
	}

	static Result measure(ClientGameTestContext ctx, Scenario s, int rep, int secs, double y) {
		System.gc();
		ctx.waitTicks(20);
		FrameClock.start();
		int ticks = secs * 20;
		double heap = 0, lod = 0;
		int samples = 0, quads = 0;
		for (int i = 0; i < ticks; i++) {
			double t = i / (double) ticks;
			pose(ctx, 0.5, y, 0.5 - 0.15 * i, 180 + 360 * t, 8);
			ctx.waitTick();
			if (i % 20 == 0) {
				Runtime r = Runtime.getRuntime();
				heap += (r.totalMemory() - r.freeMemory()) / 1048576.0;
				lod += s.on() ? LodRenderer.lastNanos / 1e6 : 0;
				quads = Math.max(quads, s.on() ? LodRenderer.drawnQuads : 0);
				samples++;
			}
		}
		double[] f = FrameClock.stop();
		return new Result(s.id(), s.rd(), s.on(), rep, f[0], f[1], f[2], (int) f[3], heap / samples, lod / samples, LodRenderer.drawnTiles, quads);
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

	static String report(int secs, int dist, List<String> mods, boolean emf, List<String> log, List<Result> rs) {
		StringBuilder sb = new StringBuilder();
		sb.append("Tcordeiro's Horizonte — FPS real (tempo entre frames, VSync desligado, FPS sem limite)\n");
		sb.append("Janela 1280×720, MacBook Air M1 8 GB, OpenGL 4.1 Metal. Rota: voo ~30 blocos acima do chão para norte, a girar 360°, ")
				.append(secs).append(" s por cenário, 2 repetições alternadas. Horizonte a ").append(dist).append(" chunks.\n");
		sb.append("Packs: ").append(emf ? "Fresh Animations + FA All Extensions" : "nenhum").append("\nMods: ").append(String.join(", ", mods)).append("\n");
		for (String l : log) sb.append(l).append('\n');
		sb.append("\ncen. | dist. | Horizonte | rep | FPS médio | 1% low | ms/frame | frames | heap usado (MB) | CPU Horizonte (ms) | mosaicos | quadrados\n");
		for (Result r : rs)
			sb.append(String.format(Locale.ROOT, "%-4s | %5d | %-9s | %3d | %9.1f | %6.1f | %8.2f | %6d | %15.0f | %18.3f | %8d | %9d\n", r.id(), r.rd(), r.on() ? "sim" : "não",
					r.rep(), r.fps(), r.low1(), r.ms(), r.frames(), r.heapMb(), r.lodMs(), r.tiles(), r.quads()));
		sb.append("\nMédia das 2 repetições:\n");
		sb.append("cen. | dist. | Horizonte | FPS médio | 1% low | ms/frame | heap (MB)\n");
		for (String id : new String[] {"A", "B", "C", "D", "E"}) {
			List<Result> l = rs.stream().filter(r -> r.id().equals(id)).toList();
			if (l.isEmpty()) continue;
			sb.append(String.format(Locale.ROOT, "%-4s | %5d | %-9s | %9.1f | %6.1f | %8.2f | %9.0f\n", id, l.get(0).rd(), l.get(0).on() ? "sim" : "não",
					l.stream().mapToDouble(Result::fps).average().orElse(0), l.stream().mapToDouble(Result::low1).average().orElse(0),
					l.stream().mapToDouble(Result::ms).average().orElse(0), l.stream().mapToDouble(Result::heapMb).average().orElse(0)));
		}
		return sb.toString();
	}

	static void save(Minecraft mc, String report) {
		try {
			Files.createDirectories(OUT);
			Files.writeString(OUT.resolve("fps_medicao.txt"), report);
			Path shots = mc.gameDirectory.toPath().resolve("screenshots");
			if (Files.isDirectory(shots)) try (Stream<Path> s = Files.walk(shots)) {
				for (Path p : (Iterable<Path>) s::iterator)
					if (p.getFileName().toString().contains("fps_")) Files.copy(p, OUT.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		Horizonte.LOG.info("\n{}", report);
	}
}
