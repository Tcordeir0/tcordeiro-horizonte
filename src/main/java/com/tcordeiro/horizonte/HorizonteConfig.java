package com.tcordeiro.horizonte;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/** config/thorizonte.json. Valores em falta ficam com o valor por omissão; o ficheiro é reescrito com todos os campos ao arrancar. */
public final class HorizonteConfig {
	/** Liga/desliga o terreno ao longe. */
	public boolean enabled = true;
	/** Até onde se desenha o terreno ao longe, em chunks (16–256). */
	public int distance = 64;
	/** "baixa", "media" ou "alta": até que distância se usam células de 2/4/8 blocos. */
	public String quality = "media";
	/** Mundo local: pedir ao servidor integrado os chunks ao longe (carrega do disco ou gera os que faltam), aos poucos. */
	public boolean generate = false;
	/** Raio (chunks) até onde se pedem chunks ao servidor integrado. */
	public int generateRadius = 32;
	/** Só pede chunks se o servidor estiver abaixo disto (ms por tick). */
	public int generateMaxMspt = 20;
	/** Pedidos de chunks em simultâneo. */
	public int generateInFlight = 2;
	/** Teto de memória para os resumos carregados (MB); acima disto descarregam-se as regiões mais longe (ficam no disco). */
	public int memoryMB = 160;
	/** Empurra a neblina do terreno normal para longe, para não haver uma parede de neblina à frente do terreno ao longe. */
	public boolean extendFog = true;
	/** Sobreposição (blocos) entre o fim dos chunks normais e o início do terreno ao longe. */
	public int overlapBlocks = 12;
	/** Ligeira variação de cor por célula (para o terreno ao longe não parecer plástico liso). */
	public boolean colorNoise = true;

	public double qualityFactor() {
		return switch (quality) {
			case "baixa", "low" -> 0.5;
			case "alta", "high" -> 2.0;
			default -> 1.0;
		};
	}

	public void clamp() {
		distance = Math.max(16, Math.min(256, distance));
		generateRadius = Math.max(0, Math.min(256, generateRadius));
		generateMaxMspt = Math.max(5, Math.min(100, generateMaxMspt));
		generateInFlight = Math.max(1, Math.min(16, generateInFlight));
		memoryMB = Math.max(32, Math.min(2048, memoryMB));
		overlapBlocks = Math.max(0, Math.min(64, overlapBlocks));
		if (quality == null) quality = "media";
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static HorizonteConfig instance = new HorizonteConfig();
	private static Path file;

	public static HorizonteConfig get() {
		return instance;
	}

	public static void load(Path f) {
		file = f;
		HorizonteConfig c = null;
		if (Files.exists(f)) {
			try (Reader r = Files.newBufferedReader(f)) {
				c = GSON.fromJson(r, HorizonteConfig.class);
			} catch (IOException | JsonParseException e) {
				Horizonte.LOG.warn("config/thorizonte.json inválido, a usar valores por omissão: {}", e.toString());
			}
		}
		instance = c == null ? new HorizonteConfig() : c;
		instance.clamp();
		save();
	}

	public static void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file)) {
				GSON.toJson(instance, w);
			}
		} catch (IOException e) {
			Horizonte.LOG.warn("não consegui gravar {}: {}", file, e.toString());
		}
	}
}
