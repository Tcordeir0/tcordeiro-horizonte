package com.tcordeiro.horizonte.client;

import java.util.HashSet;
import java.util.Set;

import com.tcordeiro.horizonte.Horizonte;
import com.tcordeiro.horizonte.HorizonteConfig;
import com.tcordeiro.horizonte.LodMath;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Mundo local: pede ao servidor integrado, aos poucos, os chunks à volta do jogador para lá da distância de visão (lê do disco os
 * que existem, gera os que faltam), tira o resumo e larga-os logo. Anda em anéis do mais perto para o mais longe e só trabalha
 * quando o servidor tem folga (ms por tick abaixo do limite).
 */
public final class FarScanner {
	private FarScanner() {}

	private static int centerX = Integer.MIN_VALUE, centerZ, ring, idx;
	private static int[] ringPos;
	private static String dim = "";
	private static int inFlight;
	private static final Set<Long> failed = new HashSet<>();
	public static volatile long requested, completed;
	public static volatile boolean finished;

	public static int inFlight() {
		return inFlight;
	}

	public static void reset() {
		centerX = Integer.MIN_VALUE;
		failed.clear();
		finished = false;
	}

	/** Fim de cada tick do servidor integrado. */
	public static void tick(MinecraftServer server) {
		Minecraft mc = Minecraft.getInstance();
		if (server != mc.getSingleplayerServer()) return;
		HorizonteConfig cfg = HorizonteConfig.get();
		LodStore store = HorizonteClient.store();
		if (!cfg.enabled || !cfg.generate || store == null || !HorizonteClient.collecting()) return;
		var local = mc.player;
		if (local == null) return;
		ServerPlayer p = server.getPlayerList().getPlayer(local.getUUID());
		if (p == null) return;
		ServerLevel level = (ServerLevel) p.level();
		String d = level.dimension().identifier().toString();
		if (!d.equals(store.dimId)) return;
		int radius = Math.min(cfg.generateRadius, cfg.distance);
		int start = mc.options.getEffectiveRenderDistance() + 1;
		ChunkPos pc = p.chunkPosition();
		if (!d.equals(dim) || centerX == Integer.MIN_VALUE || Math.max(Math.abs(pc.x() - centerX), Math.abs(pc.z() - centerZ)) > 8) {
			dim = d;
			centerX = pc.x();
			centerZ = pc.z();
			ring = start;
			ringPos = LodMath.ring(ring);
			idx = 0;
			finished = false;
		}
		if (ring > radius) {
			finished = true;
			return;
		}
		long mspt = server.getAverageTickTimeNanos();
		if (mspt > cfg.generateMaxMspt * 1_000_000L) return;
		// com muita folga pede mais por tick
		int budget = mspt < cfg.generateMaxMspt * 400_000L ? 6 : 2, checks = 512;
		while (inFlight < cfg.generateInFlight && budget > 0 && checks-- > 0) {
			if (idx >= ringPos.length) {
				ring++;
				if (ring > radius) {
					finished = true;
					return;
				}
				ringPos = LodMath.ring(ring);
				idx = 0;
			}
			int dx = ringPos[idx], dz = ringPos[idx + 1];
			if (dx * dx + dz * dz > radius * radius) {
				idx += 2;
				continue;
			}
			int cx = centerX + dx, cz = centerZ + dz;
			int st = store.presence(cx, cz);
			if (st == LodStore.UNKNOWN) return; // região a ser lida do disco: tentar no próximo tick
			idx += 2;
			if (st == LodStore.PRESENT || failed.contains(LodMath.key(cx, cz))) continue;
			request(server, level, store, cx, cz);
			budget--;
		}
	}

	private static void request(MinecraftServer server, ServerLevel level, LodStore store, int cx, int cz) {
		ServerChunkCache cache = level.getChunkSource();
		ChunkPos pos = new ChunkPos(cx, cz);
		cache.addTicketWithRadius(Horizonte.LOD_TICKET, pos, 0);
		inFlight++;
		requested++;
		cache.getChunkFuture(cx, cz, ChunkStatus.FULL, true).whenCompleteAsync((res, err) -> {
			inFlight--;
			try {
				LevelChunk chunk = err == null && res != null && res.isSuccess() && res.orElse(null) instanceof LevelChunk lc ? lc : null;
				if (chunk == null) {
					failed.add(LodMath.key(cx, cz));
					return;
				}
				short[] h8 = new short[64];
				int[] c8 = new int[64];
				if (ChunkSampler.sample(chunk, h8, c8)) store.submitChunk(cx, cz, h8, c8);
				else failed.add(LodMath.key(cx, cz));
				completed++;
			} catch (Throwable t) {
				Horizonte.LOG.warn("Horizonte: erro a ler o chunk {},{}: {}", cx, cz, t.toString());
				failed.add(LodMath.key(cx, cz));
			} finally {
				cache.removeTicketWithRadius(Horizonte.LOD_TICKET, pos, 0);
			}
		}, server);
	}
}
