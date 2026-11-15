package com.tcordeiro.horizonte.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.tcordeiro.horizonte.Horizonte;
import com.tcordeiro.horizonte.HorizonteConfig;
import com.tcordeiro.horizonte.LodMath;
import com.tcordeiro.horizonte.LodRegion;
import com.tcordeiro.horizonte.MeshBuilder;

/**
 * Os resumos de um mundo + dimensão, em disco ({@code <jogo>/thorizonte/<mundo>/<dimensão>/r.X.Z.thz}) e em memória. Todo o
 * trabalho pesado (juntar chunks, ler/gravar regiões, construir malhas) corre numa única linha de fundo; os outros fios só põem
 * pedidos na fila.
 */
public final class LodStore {
	public static final int UNKNOWN = 0, PRESENT = 1, ABSENT = 2;

	public final String worldKey, dimId;
	private final Path dir;
	private final ExecutorService worker;
	private final Map<Long, LodRegion> regions = new HashMap<>(); // só a linha de fundo mexe
	/** Máscara de chunks presentes por região (cópia; para o «scanner» saber o que já existe sem tocar nas regiões). */
	private final ConcurrentHashMap<Long, long[]> presence = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, Boolean> regionRequested = new ConcurrentHashMap<>();
	/** Versão de cada mosaico: sobe quando chega um chunk novo dentro dele (ou na borda). */
	private final ConcurrentHashMap<Long, Integer> tileVersion = new ConcurrentHashMap<>();
	private final MeshBuilder builder = new MeshBuilder();
	private volatile double camX, camZ;
	private long lastSave = System.currentTimeMillis();
	public final AtomicInteger queued = new AtomicInteger();
	public final AtomicLong chunksIngested = new AtomicLong();
	private volatile int loadedRegions, knownChunks;
	private volatile boolean closed;

	public LodStore(Path root, String worldKey, String dimId) {
		this.worldKey = worldKey;
		this.dimId = dimId;
		this.dir = root.resolve(sanitize(worldKey)).resolve(sanitize(dimId));
		this.worker = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "Horizonte LOD");
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 2);
			return t;
		});
	}

	static String sanitize(String s) {
		return s.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	public Path dir() {
		return dir;
	}

	public void setCamera(double x, double z) {
		camX = x;
		camZ = z;
	}

	public int loadedRegions() {
		return loadedRegions;
	}

	public int knownChunks() {
		return knownChunks;
	}

	public int tileVersion(int tx, int tz) {
		return tileVersion.getOrDefault(LodMath.key(tx, tz), 0);
	}

	private void run(Runnable r) {
		if (closed) return;
		queued.incrementAndGet();
		try {
			worker.execute(() -> {
				queued.decrementAndGet();
				try {
					r.run();
				} catch (Throwable t) {
					Horizonte.LOG.error("erro na linha do Horizonte", t);
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException e) {
			queued.decrementAndGet();
		}
	}

	/** Chega o resumo de um chunk (de qualquer fio). */
	public void submitChunk(int cx, int cz, short[] h8, int[] c8) {
		run(() -> {
			int rx = Math.floorDiv(cx, LodMath.REGION_CHUNKS), rz = Math.floorDiv(cz, LodMath.REGION_CHUNKS);
			LodRegion r = region(rx, rz);
			r.setChunk(Math.floorMod(cx, LodMath.REGION_CHUNKS), Math.floorMod(cz, LodMath.REGION_CHUNKS), h8, c8);
			publishPresence(r);
			chunksIngested.incrementAndGet();
			int tx = Math.floorDiv(cx, LodMath.TILE_CHUNKS), tz = Math.floorDiv(cz, LodMath.TILE_CHUNKS);
			int lx = Math.floorMod(cx, LodMath.TILE_CHUNKS), lz = Math.floorMod(cz, LodMath.TILE_CHUNKS);
			bump(tx, tz);
			if (lx == 0) bump(tx - 1, tz);
			if (lx == LodMath.TILE_CHUNKS - 1) bump(tx + 1, tz);
			if (lz == 0) bump(tx, tz - 1);
			if (lz == LodMath.TILE_CHUNKS - 1) bump(tx, tz + 1);
			maintenance();
		});
	}

	private void bump(int tx, int tz) {
		tileVersion.merge(LodMath.key(tx, tz), 1, Integer::sum);
	}

	/** O chunk já tem resumo? UNKNOWN = a região ainda está a ser lida do disco (pergunta outra vez daqui a pouco). */
	public int presence(int cx, int cz) {
		int rx = Math.floorDiv(cx, LodMath.REGION_CHUNKS), rz = Math.floorDiv(cz, LodMath.REGION_CHUNKS);
		long rk = LodMath.key(rx, rz);
		long[] bits = presence.get(rk);
		if (bits == null) {
			if (regionRequested.putIfAbsent(rk, Boolean.TRUE) == null) run(() -> region(rx, rz));
			return UNKNOWN;
		}
		int i = Math.floorMod(cz, LodMath.REGION_CHUNKS) * LodMath.REGION_CHUNKS + Math.floorMod(cx, LodMath.REGION_CHUNKS);
		return (bits[i >> 6] & (1L << (i & 63))) != 0 ? PRESENT : ABSENT;
	}

	private void publishPresence(LodRegion r) {
		long[] bits = new long[LodMath.REGION_CHUNKS * LodMath.REGION_CHUNKS / 64];
		for (int lcz = 0; lcz < LodMath.REGION_CHUNKS; lcz++)
			for (int lcx = 0; lcx < LodMath.REGION_CHUNKS; lcx++)
				if (r.hasChunk(lcx, lcz)) {
					int i = lcz * LodMath.REGION_CHUNKS + lcx;
					bits[i >> 6] |= 1L << (i & 63);
				}
		presence.put(LodMath.key(r.rx, r.rz), bits);
	}

	/** Linha de fundo: região em memória (lida do disco ou nova). */
	private LodRegion region(int rx, int rz) {
		long k = LodMath.key(rx, rz);
		LodRegion r = regions.get(k);
		if (r == null) {
			r = read(rx, rz);
			regions.put(k, r);
			publishPresence(r);
			loadedRegions = regions.size();
			int n = 0;
			for (LodRegion x : regions.values()) n += x.chunkCount();
			knownChunks = n;
		}
		r.lastUse = System.nanoTime();
		return r;
	}

	private Path file(int rx, int rz) {
		return dir.resolve("r." + rx + "." + rz + ".thz");
	}

	private LodRegion read(int rx, int rz) {
		Path f = file(rx, rz);
		if (Files.exists(f)) {
			try {
				return LodRegion.decode(rx, rz, Files.readAllBytes(f));
			} catch (IOException e) {
				Horizonte.LOG.warn("região LOD {} ilegível, recomeço: {}", f.getFileName(), e.toString());
			}
		}
		return new LodRegion(rx, rz);
	}

	private void write(LodRegion r) {
		if (!r.dirty) return;
		try {
			Files.createDirectories(dir);
			Path f = file(r.rx, r.rz), tmp = f.resolveSibling(f.getFileName() + ".tmp");
			Files.write(tmp, r.encode());
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			r.dirty = false;
		} catch (IOException e) {
			Horizonte.LOG.warn("não consegui gravar a região LOD {},{}: {}", r.rx, r.rz, e.toString());
		}
	}

	/** Linha de fundo: grava de 15 em 15 s e tira da memória as regiões mais longe se passar do teto. */
	private void maintenance() {
		long now = System.currentTimeMillis();
		if (now - lastSave < 15_000) return;
		lastSave = now;
		for (LodRegion r : regions.values()) write(r);
		HorizonteConfig cfg = HorizonteConfig.get();
		long cap = cfg.memoryMB * 1024L * 1024L;
		double keep = (cfg.distance + 48) * 16.0;
		List<LodRegion> list = new ArrayList<>(regions.values());
		double cx = camX, cz = camZ;
		list.sort((a, b) -> Double.compare(regionDist(b, cx, cz), regionDist(a, cx, cz))); // mais longe primeiro
		long used = (long) regions.size() * LodRegion.memoryBytes();
		for (LodRegion r : list) {
			boolean far = regionDist(r, cx, cz) > keep;
			if (!far && used <= cap) break;
			write(r);
			regions.remove(LodMath.key(r.rx, r.rz));
			used -= LodRegion.memoryBytes();
		}
		loadedRegions = regions.size();
	}

	private static double regionDist(LodRegion r, double x, double z) {
		double s = LodMath.REGION_CHUNKS * 16.0;
		return LodMath.distToRect(x, z, r.rx * s, r.rz * s, (r.rx + 1) * s, (r.rz + 1) * s);
	}

	/** Pede a malha de um mosaico; o resultado chega noutro fio. */
	public CompletableFuture<MeshBuilder.Mesh> buildMesh(int tx, int tz, int step) {
		CompletableFuture<MeshBuilder.Mesh> f = new CompletableFuture<>();
		if (closed) {
			f.cancel(false);
			return f;
		}
		run(() -> {
			try {
				f.complete(builder.build(source, tx, tz, step));
			} catch (Throwable t) {
				f.completeExceptionally(t);
			}
		});
		return f;
	}

	/** Leitura das amostras para a malha (linha de fundo), com cache da última região. */
	private final MeshBuilder.SampleSource source = new MeshBuilder.SampleSource() {
		LodRegion last;

		private LodRegion at(int gx, int gz) {
			int rx = Math.floorDiv(gx, LodMath.REGION_SAMPLES), rz = Math.floorDiv(gz, LodMath.REGION_SAMPLES);
			if (last != null && last.rx == rx && last.rz == rz && regions.get(LodMath.key(rx, rz)) == last) return last;
			last = region(rx, rz);
			return last;
		}

		@Override
		public short height(int gx, int gz) {
			return at(gx, gz).height(Math.floorMod(gx, LodMath.REGION_SAMPLES), Math.floorMod(gz, LodMath.REGION_SAMPLES));
		}

		@Override
		public int color(int gx, int gz) {
			return at(gx, gz).color(Math.floorMod(gx, LodMath.REGION_SAMPLES), Math.floorMod(gz, LodMath.REGION_SAMPLES));
		}
	};

	/** Grava tudo e para a linha (espera até 10 s). */
	public void close() {
		if (closed) return;
		run(() -> {
			for (LodRegion r : regions.values()) write(r);
			regions.clear();
		});
		closed = true;
		worker.shutdown();
		try {
			if (!worker.awaitTermination(10, TimeUnit.SECONDS)) Horizonte.LOG.warn("Horizonte: a gravação demorou mais de 10 s");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
