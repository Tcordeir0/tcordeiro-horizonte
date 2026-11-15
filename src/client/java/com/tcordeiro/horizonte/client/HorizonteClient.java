package com.tcordeiro.horizonte.client;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.tcordeiro.horizonte.Horizonte;
import com.tcordeiro.horizonte.HorizonteConfig;
import com.tcordeiro.horizonte.LodMath;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

/** Cliente: ciclo de vida do armazém de LOD, recolha dos chunks carregados, tecla, comando e decisão de desenhar ou não. */
public final class HorizonteClient implements ClientModInitializer {
	private static volatile LodStore store;
	private static ClientLevel storeLevel;
	private static final ArrayDeque<Long> pending = new ArrayDeque<>();
	private static final Set<Long> pendingSet = new HashSet<>();
	public static KeyMapping toggleKey;
	/** Motivo por que não desenha (vazio = desenha). */
	private static String blockedReason = "";
	private static int blockedCheck;
	private static Boolean voxyWorks;

	@Override
	public void onInitializeClient() {
		HorizonteConfig.load(FabricLoader.getInstance().getConfigDir().resolve("thorizonte.json"));
		KeyMapping.Category cat = KeyMapping.Category.register(Horizonte.id("main"));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.thorizonte.toggle", InputConstants.KEY_F7, cat));
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> enqueue(chunk.getPos().x(), chunk.getPos().z()));
		ClientChunkEvents.CHUNK_UNLOAD.register(HorizonteClient::onUnload);
		ClientTickEvents.END_CLIENT_TICK.register(HorizonteClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> mc.execute(HorizonteClient::closeStore));
		ServerTickEvents.END_SERVER_TICK.register(FarScanner::tick);
		ClientCommandRegistrationCallback.EVENT.register((d, reg) -> register(d));
	}

	public static LodStore store() {
		return store;
	}

	/** Recolher chunks (mesmo com o desenho bloqueado por shaders/Voxy, para ter dados quando voltar). */
	public static boolean collecting() {
		return HorizonteConfig.get().enabled && blockedReason.isEmpty();
	}

	public static boolean renderActive() {
		return HorizonteConfig.get().enabled && blockedReason.isEmpty();
	}

	/** Raio (blocos) do buraco onde o LOD não se desenha: lá estão os chunks normais. */
	public static float holeRadius() {
		Minecraft mc = Minecraft.getInstance();
		return Math.max(16f, mc.options.getEffectiveRenderDistance() * 16f - HorizonteConfig.get().overlapBlocks);
	}

	/** A neblina do jogo deve ir para longe? (só quando estamos mesmo a desenhar) */
	public static boolean fogActive() {
		return renderActive() && HorizonteConfig.get().extendFog && store != null && LodRenderer.ready();
	}

	// ── motivos para não desenhar ───────────────────────────────────────────────────────────────

	private static void updateBlocked() {
		if (--blockedCheck > 0) return;
		blockedCheck = 20;
		String r = "";
		if (FabricLoader.getInstance().isModLoaded("distanthorizons")) r = "Distant Horizons instalado";
		else if (voxyWorks()) r = "o Voxy funciona neste computador";
		else if (irisShadersOn()) r = "shaders do Iris ligados";
		else if (!LodRenderer.lastError.isEmpty()) r = LodRenderer.lastError;
		if (!r.equals(blockedReason)) {
			if (!r.isEmpty()) Horizonte.LOG.info("Horizonte em pausa: {}", r);
			blockedReason = r;
		}
	}

	/** O Voxy precisa de compute shaders e de glMultiDrawElementsIndirectCount (GL 4.6): no Mac (GL 4.1) diz «unsupported». */
	static boolean voxyWorks() {
		if (voxyWorks != null) return voxyWorks;
		if (!FabricLoader.getInstance().isModLoaded("voxy")) return voxyWorks = false;
		try {
			GLCapabilities caps = GL.getCapabilities();
			voxyWorks = caps.glDispatchComputeIndirect != 0 && caps.glMultiDrawElementsIndirectCountARB != 0;
		} catch (Throwable t) {
			voxyWorks = false;
		}
		Horizonte.LOG.info("Voxy instalado; {}", voxyWorks ? "este GPU suporta-o, o Horizonte fica desligado" : "não funciona aqui (OpenGL 4.1), o Horizonte desenha");
		return voxyWorks;
	}

	static boolean irisShadersOn() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) return false;
		try {
			Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Object inst = api.getMethod("getInstance").invoke(null);
			return (boolean) api.getMethod("isShaderPackInUse").invoke(inst);
		} catch (Throwable t) {
			return false;
		}
	}

	// ── armazém por mundo/dimensão ──────────────────────────────────────────────────────────────

	static String worldKey(Minecraft mc) {
		MinecraftServer sp = mc.getSingleplayerServer();
		if (sp != null) {
			Path p = sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			return "sp_" + p.getFileName();
		}
		ServerData sd = mc.getCurrentServer();
		return "mp_" + (sd != null ? sd.ip : "desconhecido").toLowerCase(Locale.ROOT);
	}

	private static void ensureStore(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == storeLevel && store != null) return;
		closeStore();
		if (level == null) return;
		storeLevel = level;
		Path root = FabricLoader.getInstance().getGameDir().resolve("thorizonte");
		store = new LodStore(root, worldKey(mc), level.dimension().identifier().toString());
		FarScanner.reset();
		Horizonte.LOG.info("Horizonte: {} / {} em {}", store.worldKey, store.dimId, store.dir());
	}

	static void closeStore() {
		LodStore s = store;
		store = null;
		storeLevel = null;
		pending.clear();
		pendingSet.clear();
		LodRenderer.clearTiles();
		if (s != null) s.close();
	}

	// ── recolha dos chunks do cliente ───────────────────────────────────────────────────────────

	private static void enqueue(int cx, int cz) {
		long k = LodMath.key(cx, cz);
		if (pendingSet.add(k)) pending.add(k);
	}

	private static int unloadsThisTick;

	private static void onUnload(ClientLevel level, LevelChunk chunk) {
		// apanha o que mudou enquanto o chunk esteve carregado (limitado, para não pesar ao teletransportar)
		LodStore s = store;
		if (s == null || level != storeLevel || !collecting() || unloadsThisTick++ > 24) return;
		sampleAndSubmit(s, chunk);
	}

	private static void sampleAndSubmit(LodStore s, LevelChunk chunk) {
		short[] h8 = new short[64];
		int[] c8 = new int[64];
		if (ChunkSampler.sample(chunk, h8, c8)) s.submitChunk(chunk.getPos().x(), chunk.getPos().z(), h8, c8);
	}

	private static void tick(Minecraft mc) {
		unloadsThisTick = 0;
		updateBlocked();
		while (toggleKey.consumeClick()) {
			HorizonteConfig cfg = HorizonteConfig.get();
			cfg.enabled = !cfg.enabled;
			HorizonteConfig.save();
			if (mc.player != null) mc.player.sendOverlayMessage(Component.translatableWithFallback(cfg.enabled ? "thorizonte.on" : "thorizonte.off",
					cfg.enabled ? "🌄 Horizonte ligado" : "🌄 Horizonte desligado"));
		}
		if (mc.level == null) {
			if (store != null) closeStore();
			return;
		}
		ensureStore(mc);
		LodStore s = store;
		if (s == null || !collecting()) return;
		long t0 = System.nanoTime();
		int n = 0;
		while (!pending.isEmpty() && n < 32 && System.nanoTime() - t0 < 2_000_000L) {
			long k = pending.poll();
			pendingSet.remove(k);
			int cx = LodMath.keyX(k), cz = LodMath.keyZ(k);
			if (mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk) {
				sampleAndSubmit(s, chunk);
				n++;
			}
		}
	}

	// ── comando ─────────────────────────────────────────────────────────────────────────────────

	static void register(CommandDispatcher<FabricClientCommandSource> d) {
		d.register(ClientCommands.literal("horizonte").executes(c -> info(c.getSource()))
				.then(ClientCommands.literal("ligar").executes(c -> toggle(c.getSource(), true)))
				.then(ClientCommands.literal("desligar").executes(c -> toggle(c.getSource(), false)))
				.then(ClientCommands.literal("distancia").then(ClientCommands.argument("chunks", IntegerArgumentType.integer(16, 256)).executes(c -> {
					HorizonteConfig.get().distance = IntegerArgumentType.getInteger(c, "chunks");
					HorizonteConfig.save();
					FarScanner.reset();
					return info(c.getSource());
				})))
				.then(ClientCommands.literal("qualidade")
						.then(ClientCommands.literal("baixa").executes(c -> quality(c.getSource(), "baixa")))
						.then(ClientCommands.literal("media").executes(c -> quality(c.getSource(), "media")))
						.then(ClientCommands.literal("alta").executes(c -> quality(c.getSource(), "alta"))))
				.then(ClientCommands.literal("gerar")
						.then(ClientCommands.literal("ligar").executes(c -> generate(c.getSource(), true)))
						.then(ClientCommands.literal("desligar").executes(c -> generate(c.getSource(), false)))));
	}

	static int info(FabricClientCommandSource src) {
		HorizonteConfig cfg = HorizonteConfig.get();
		LodStore s = store;
		String state = !cfg.enabled ? "desligado" : !blockedReason.isEmpty() ? "em pausa (" + blockedReason + ")" : "ligado";
		src.sendFeedback(Component.literal(String.format(Locale.ROOT, "🌄 Horizonte %s · %d chunks · %d mosaicos · %.1f MB GPU · gerar %s%s",
				state, cfg.distance, LodRenderer.drawnTiles, LodRenderer.gpuBytes / 1048576.0, cfg.generate ? "sim" : "não",
				s == null ? "" : String.format(Locale.ROOT, " · %d recolhidos", s.chunksIngested.get()))));
		return 1;
	}

	static int toggle(FabricClientCommandSource src, boolean on) {
		HorizonteConfig.get().enabled = on;
		HorizonteConfig.save();
		src.sendFeedback(Component.literal(on ? "🌄 Horizonte ligado" : "🌄 Horizonte desligado"));
		return 1;
	}

	static int quality(FabricClientCommandSource src, String q) {
		HorizonteConfig.get().quality = q;
		HorizonteConfig.save();
		return info(src);
	}

	static int generate(FabricClientCommandSource src, boolean on) {
		HorizonteConfig.get().generate = on;
		HorizonteConfig.save();
		FarScanner.reset();
		return info(src);
	}
}
