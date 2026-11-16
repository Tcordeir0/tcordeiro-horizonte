package com.tcordeiro.horizonte.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.platform.NativeImage;
import com.tcordeiro.horizonte.Horizonte;
import com.tcordeiro.horizonte.client.mixin.SpriteContentsAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Cor média da textura real de cada bloco (a face de cima do modelo, com os packs de recursos que estiverem ligados), para o
 * terreno ao longe ter a mesma cor que os chunks normais ao pé dele. Guarda-se por estado de bloco e por textura; recomeça quando o
 * jogo recarrega os modelos (F3+T, troca de pack).
 */
public final class TextureColors {
	private TextureColors() {}

	/** Sem cor (bloco sem textura, ou modelos ainda não carregados): usa-se a cor do mapa. */
	public static final int NONE = -1;
	/** Bit 24 do valor guardado: a face é tingida (erva, folhas, água…). */
	public static final int TINTED = 1 << 24;

	private static final ConcurrentHashMap<BlockState, Integer> byState = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<TextureAtlasSprite, Integer> bySprite = new ConcurrentHashMap<>();
	private static volatile Object models;
	private static volatile boolean warned;

	/** RGB médio (bits 0–23) e {@link #TINTED}, ou {@link #NONE}. */
	public static int of(BlockState s) {
		BlockStateModelSet set;
		try {
			set = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
		} catch (Throwable t) {
			return NONE;
		}
		if (set != models) {
			byState.clear();
			bySprite.clear();
			models = set;
		}
		Integer v = byState.get(s);
		if (v == null) {
			v = compute(set, s);
			byState.put(s, v);
		}
		return v;
	}

	/** Cor fixa de tinta do bloco (sem mundo), para blocos tingidos que não são erva/folhas/água; −1 se não tiver. */
	public static int defaultTint(BlockState s) {
		try {
			BlockTintSource src = Minecraft.getInstance().getBlockColors().getTintSource(s, 0);
			return src == null ? -1 : src.color(s) & 0xFFFFFF;
		} catch (Throwable t) {
			return -1;
		}
	}

	private static int compute(BlockStateModelSet set, BlockState s) {
		try {
			BlockStateModel model = set.get(s);
			List<BlockStateModelPart> parts = new ArrayList<>();
			model.collectParts(RandomSource.create(42L), parts);
			BakedQuad up = null, any = null;
			for (BlockStateModelPart p : parts) {
				List<BakedQuad> q = p.getQuads(Direction.UP);
				if (!q.isEmpty()) {
					up = q.get(0);
					break;
				}
				if (any == null) {
					for (BakedQuad b : p.getQuads(null)) {
						if (b.direction() == Direction.UP) {
							up = b;
							break;
						}
						if (any == null) any = b;
					}
					if (up != null) break;
				}
			}
			BakedQuad quad = up != null ? up : any;
			TextureAtlasSprite sprite;
			boolean tinted = false;
			if (quad != null) {
				sprite = quad.materialInfo().sprite();
				tinted = quad.materialInfo().isTinted();
			} else {
				// sem quadrados (água, lava…): a textura das partículas é a do líquido
				sprite = set.getParticleMaterial(s).sprite();
				tinted = !s.getFluidState().isEmpty();
			}
			// ar e blocos sem modelo dão a textura «em falta» (magenta): sem cor
			if (s.isAir() || sprite.contents().name().getPath().equals("missingno")) return NONE;
			int avg = average(sprite);
			if (avg == NONE) return NONE;
			return avg | (tinted ? TINTED : 0);
		} catch (Throwable t) {
			if (!warned) {
				warned = true;
				Horizonte.LOG.warn("Horizonte: não consegui ler a textura de {} (uso a cor do mapa): {}", s, t.toString());
			}
			return NONE;
		}
	}

	/** Média dos píxeis opacos (alfa ≥ 50 %) da imagem original da textura; NONE se não houver. */
	static int average(TextureAtlasSprite sprite) {
		Integer c = bySprite.get(sprite);
		if (c != null) return c;
		int out = NONE;
		NativeImage img = ((SpriteContentsAccessor) sprite.contents()).thorizonte$originalImage();
		if (img != null && !img.isClosed()) {
			long r = 0, g = 0, b = 0, n = 0;
			int w = img.getWidth(), h = img.getHeight();
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int argb = img.getPixel(x, y);
					if ((argb >>> 24) < 128) continue;
					r += (argb >> 16) & 255;
					g += (argb >> 8) & 255;
					b += argb & 255;
					n++;
				}
			}
			if (n > 0) out = (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
		}
		bySprite.put(sprite, out);
		return out;
	}

	/** Multiplica duas cores RGB canal a canal (textura × tinta), como o jogo faz. */
	public static int multiply(int a, int b) {
		int r = ((a >> 16) & 255) * ((b >> 16) & 255) / 255;
		int g = ((a >> 8) & 255) * ((b >> 8) & 255) / 255;
		int bl = (a & 255) * (b & 255) / 255;
		return r << 16 | g << 8 | bl;
	}
}
