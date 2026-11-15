package com.tcordeiro.horizonte.client;

import com.tcordeiro.horizonte.LodMath;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;

/**
 * Tira o resumo de um chunk carregado: para cada coluna, a altura do topo (heightmap MOTION_BLOCKING: chão, água, folhas; ignora
 * erva e flores) e a cor do bloco de cima (cor do mapa, com a erva/folhas/água tingidas pelo bioma). Depois reduz a 8×8 amostras.
 * Serve tanto para chunks do cliente como do servidor integrado (só lê o chunk).
 */
public final class ChunkSampler {
	private ChunkSampler() {}

	/** Folhas que o jogo tinge com a cor do bioma (as outras têm cor própria). */
	private static boolean tintedLeaves(BlockState s) {
		return s.is(Blocks.OAK_LEAVES) || s.is(Blocks.JUNGLE_LEAVES) || s.is(Blocks.ACACIA_LEAVES) || s.is(Blocks.DARK_OAK_LEAVES)
				|| s.is(Blocks.MANGROVE_LEAVES) || s.is(Blocks.VINE);
	}

	/** @return false se o chunk não tem nada (vazio). */
	public static boolean sample(LevelChunk chunk, short[] h8, int[] c8) {
		short[] h = new short[256];
		int[] c = new int[256];
		int minY = chunk.getMinY();
		int bx = chunk.getPos().x() << 4, bz = chunk.getPos().z() << 4;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		boolean any = false;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int i = z * 16 + x;
				int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z); // primeiro y livre por cima do topo
				if (top <= minY) {
					h[i] = LodMath.NONE;
					continue;
				}
				h[i] = (short) top;
				c[i] = columnColor(chunk, p, bx + x, top - 1, bz + z, minY);
				any = true;
			}
		}
		LodMath.downsample(h, c, h8, c8);
		return any;
	}

	static int columnColor(LevelChunk chunk, BlockPos.MutableBlockPos p, int x, int y, int z, int minY) {
		p.set(x, y + 1, z);
		BlockState above = chunk.getBlockState(p);
		if (above.is(Blocks.SNOW)) return MapColor.SNOW.col;
		p.set(x, y, z);
		BlockState s = chunk.getBlockState(p);
		FluidState fs = s.getFluidState();
		if (!fs.isEmpty() && fs.is(FluidTags.WATER)) {
			int depth = 0;
			while (depth < 16 && y - depth - 1 > minY) {
				p.set(x, y - depth - 1, z);
				FluidState below = chunk.getBlockState(p).getFluidState();
				if (below.isEmpty() || !below.is(FluidTags.WATER)) break;
				depth++;
			}
			int water = biome(chunk, x, y, z).value().getWaterColor();
			return LodMath.shade(water, 1.0f - Math.min(depth, 16) / 16f * 0.45f);
		}
		if (s.is(Blocks.GRASS_BLOCK)) return LodMath.shade(biome(chunk, x, y, z).value().getGrassColor(x, z), 0.88f);
		if (s.is(BlockTags.LEAVES) && tintedLeaves(s)) return LodMath.shade(biome(chunk, x, y, z).value().getFoliageColor(), 0.78f);
		if (s.is(Blocks.SPRUCE_LEAVES)) return LodMath.shade(0x619961, 0.78f);
		if (s.is(Blocks.BIRCH_LEAVES)) return LodMath.shade(0x80A755, 0.78f);
		int col = s.getMapColor(chunk, p).col;
		// blocos sem cor de mapa (vidro, luz…): usar o de baixo
		for (int k = 1; col == 0 && k <= 6 && y - k > minY; k++) {
			p.set(x, y - k, z);
			col = chunk.getBlockState(p).getMapColor(chunk, p).col;
		}
		return col == 0 ? 0x808080 : col;
	}

	private static Holder<Biome> biome(LevelChunk chunk, int x, int y, int z) {
		return chunk.getNoiseBiome(x >> 2, y >> 2, z >> 2);
	}
}
