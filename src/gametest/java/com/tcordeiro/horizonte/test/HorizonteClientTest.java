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
import com.tcordeiro.horizonte.client.HorizonteClient;
import com.tcordeiro.horizonte.client.LodRenderer;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Mundo normal (semente fixa), distância de visão 8, Horizonte a 40 chunks. Espera o servidor integrado preparar o terreno ao
 * longe, depois mede (sem gerar) o tempo real por frame com o Horizonte DESLIGADO / LIGADO / DESLIGADO / LIGADO e tira capturas
 * nas quatro direções com e sem. Resultados e capturas em ~/Downloads/Mods_Tcordeiro_testes/horizonte/. Sem som, 1280×720, pt_br.
 * <p>
 * Propriedades: hz.out (nome da corrida), hz.secs (limite de espera da geração, 300), hz.dist (40), hz.rd (8).
 */
public class HorizonteClientTest implements FabricClientGameTest {
	static final Path OUT = Path.of(System.getProperty("user.home"), "Downloads", "Mods_Tcordeiro_testes", "horizonte");

	record Phase(String name, boolean on, int ticks, double wallMsPerTick, double lodMs, int tiles, int quads) {}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		String label = System.getProperty("hz.out", "base");
		int secs = Integer.getInteger("hz.secs", 300);
		int dist = Integer.getInteger("hz.dist", 40);
		int rd = Integer.getInteger("hz.rd", 8);
		List<String> mods = new ArrayList<>();
		for (String id : new String[] {"sodium", "iris", "voxy", "lithium", "immediatelyfast", "ferritecore", "modernfix"})
			if (FabricLoader.getInstance().isModLoaded(id)) mods.add(id);
		ctx.runOnClient(mc -> {
			mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
			mc.options.languageCode = "pt_br";
			mc.getLanguageManager().setSelected("pt_br");
			mc.options.renderDistance().set(rd);
			HorizonteConfig c = HorizonteConfig.get();
			c.enabled = true;
			c.distance = dist;
			c.generate = true;
			c.generateRadius = dist;
			c.generateInFlight = 8;
			c.generateMaxMspt = 80;
		});
		ctx.getInput().resizeWindow(1280, 720);
		ctx.runOnClient(Minecraft::reloadResourcePacks);
		ctx.waitTicks(20);
		List<Phase> phases = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		List<String> log = new ArrayList<>();
		try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("tcordeiro-horizonte")).create()) {
			for (String c : new String[] {"time set 6000", "gamerule advance_time false", "gamerule spawn_mobs false", "gamerule advance_weather false",
					"gamerule send_command_feedback false", "gamerule show_advancement_messages false", "weather clear", "gamemode spectator @a"})
				sp.getServer().runCommand(c);
			int ground = sp.getServer().computeOnServer(s -> s.overworld().getChunk(0, 0).getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0));
			int y = Math.max(ground, 63) + 40;
			sp.getServer().runCommand("tp @a 0.5 " + y + " 0.5 0 12");
			try {
				sp.getConnection().waitForChunksRender();
			} catch (AssertionError e) {
				ctx.waitTicks(200);
			}
			// esperar o terreno ao longe (o servidor integrado lê/gera os chunks à volta)
			long t0 = System.currentTimeMillis();
			while (!FarScanner.finished && System.currentTimeMillis() - t0 < secs * 1000L) {
				ctx.waitTicks(200);
				long done = FarScanner.completed;
				String line = String.format(Locale.ROOT, "%3ds: %d chunks lidos/gerados, %d recolhidos, %d mosaicos desenhados, %.1f MB GPU",
						(System.currentTimeMillis() - t0) / 1000, done, ctx.computeOnClient(mc -> HorizonteClient.store() == null ? 0L : HorizonteClient.store().chunksIngested.get()),
						LodRenderer.drawnTiles, LodRenderer.gpuBytes / 1048576.0);
				log.add(line);
				Horizonte.LOG.info("[teste] {}", line);
			}
			log.add(String.format(Locale.ROOT, "neblina: distância %.0f→%.0f, ambiente %.0f→%.0f blocos; buraco %.0f blocos", LodRenderer.lastFogStart, LodRenderer.lastFogEnd, LodRenderer.lastEnvStart, LodRenderer.lastEnvEnd, LodRenderer.lastHole));
			double genSecs = (System.currentTimeMillis() - t0) / 1000.0;
			log.add(String.format(Locale.ROOT, "geração %s em %.0f s (%d chunks)", FarScanner.finished ? "terminada" : "INCOMPLETA", genSecs, FarScanner.completed));
			ctx.runOnClient(mc -> HorizonteConfig.get().generate = false);
			ctx.waitTicks(100); // malhas acabarem
			if (!LodRenderer.ready()) problems.add("o renderizador não ficou pronto: " + LodRenderer.lastError);
			// medição: DESLIGADO / LIGADO ×2, a olhar para norte (onde há mais terreno à frente)
			boolean[] modes = {false, true, false, true};
			for (int i = 0; i < modes.length; i++) {
				boolean on = modes[i];
				ctx.runOnClient(mc -> HorizonteConfig.get().enabled = on);
				ctx.waitTicks(60);
				long f0 = LodRenderer.frames;
				long n0 = System.nanoTime();
				long lodNs = 0;
				int ticks = 200;
				for (int k = 0; k < ticks; k++) {
					ctx.waitTick();
					lodNs += on ? LodRenderer.lastNanos : 0;
				}
				double wall = (System.nanoTime() - n0) / 1e6 / ticks;
				Phase ph = new Phase((on ? "LIGADO " : "DESLIGADO ") + (i / 2 + 1), on, ticks, wall, lodNs / 1e6 / ticks, LodRenderer.drawnTiles, LodRenderer.drawnQuads);
				phases.add(ph);
				if (on && ph.tiles() == 0) problems.add(ph.name() + ": nenhum mosaico desenhado");
				if (on && LodRenderer.frames == f0) problems.add(ph.name() + ": o renderizador não correu");
				if (!on && ph.tiles() != 0) problems.add(ph.name() + ": desligado mas desenhou " + ph.tiles());
			}
			// capturas: 4 direções, com e sem
			String[] dirs = {"norte", "este", "sul", "oeste"};
			float[] yaws = {180, -90, 0, 90};
			for (int i = 0; i < 4; i++) {
				sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a 0.5 %d 0.5 %.0f 10", y, yaws[i]));
				for (boolean on : new boolean[] {false, true}) {
					ctx.runOnClient(mc -> HorizonteConfig.get().enabled = on);
					ctx.waitTicks(40);
					ctx.takeScreenshot("horizonte_" + label + "_" + (i + 1) + dirs[i] + "_" + (on ? "ligado" : "desligado"));
				}
			}
			// vista de cima
			sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a 0.5 %d 0.5 180 30", y + 60));
			ctx.runOnClient(mc -> HorizonteConfig.get().enabled = true);
			ctx.waitTicks(60);
			ctx.takeScreenshot("horizonte_" + label + "_5_de_cima_ligado");
			ctx.runOnClient(mc -> HorizonteConfig.get().enabled = false);
			ctx.waitTicks(40);
			ctx.takeScreenshot("horizonte_" + label + "_5_de_cima_desligado");
			ctx.runOnClient(mc -> HorizonteConfig.get().enabled = true);
		}
		String report = report(label, dist, rd, mods, log, phases, problems);
		ctx.runOnClient(mc -> save(mc, label, report));
		if (!problems.isEmpty()) throw new AssertionError(String.join("\n", problems));
	}

	static String report(String label, int dist, int rd, List<String> mods, List<String> log, List<Phase> ps, List<String> problems) {
		StringBuilder sb = new StringBuilder();
		sb.append("Tcordeiro's Horizonte — teste «").append(label).append("»\n");
		sb.append("distância de visão ").append(rd).append(" chunks, Horizonte ").append(dist).append(" chunks; mods de terceiros: ")
				.append(mods.isEmpty() ? "nenhum" : String.join(", ", mods)).append("\n\nPreparação:\n");
		for (String l : log) sb.append("  ").append(l).append('\n');
		sb.append("\nfase         | ticks | ms reais/frame | FPS (1000/ms) | ms do Horizonte (CPU) | mosaicos | quadrados\n");
		for (Phase p : ps)
			sb.append(String.format(Locale.ROOT, "%-12s | %5d | %14.2f | %13.1f | %21.3f | %8d | %9d\n", p.name(), p.ticks(), p.wallMsPerTick(),
					1000 / Math.max(0.01, p.wallMsPerTick()), p.lodMs(), p.tiles(), p.quads()));
		double off = ps.stream().filter(p -> !p.on()).mapToDouble(Phase::wallMsPerTick).average().orElse(0);
		double on = ps.stream().filter(Phase::on).mapToDouble(Phase::wallMsPerTick).average().orElse(0);
		sb.append(String.format(Locale.ROOT, "\nMédia DESLIGADO %.2f ms (%.1f FPS) · LIGADO %.2f ms (%.1f FPS) · diferença %+.1f%%\n", off, 1000 / off, on, 1000 / on,
				100 * (on - off) / off));
		sb.append(String.format(Locale.ROOT, "GPU: %.1f MB de malhas\n", LodRenderer.gpuBytes / 1048576.0));
		sb.append(problems.isEmpty() ? "\nVerificações: todas OK\n" : "\nProblemas:\n- " + String.join("\n- ", problems) + "\n");
		return sb.toString();
	}

	static void save(Minecraft mc, String label, String report) {
		try {
			Files.createDirectories(OUT);
			Files.writeString(OUT.resolve("resultado_" + label + ".txt"), report);
			Path shots = mc.gameDirectory.toPath().resolve("screenshots");
			if (Files.isDirectory(shots)) try (Stream<Path> s = Files.walk(shots)) {
				for (Path p : (Iterable<Path>) s::iterator)
					if (p.getFileName().toString().contains("horizonte_" + label + "_")) Files.copy(p, OUT.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		Horizonte.LOG.info("\n{}", report);
	}
}
