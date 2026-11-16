package com.tcordeiro.horizonte.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.renderer.texture.SpriteContents;

/** Píxeis originais de uma textura do atlas (para a cor média do bloco). */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
	@Accessor("originalImage")
	NativeImage thorizonte$originalImage();
}
