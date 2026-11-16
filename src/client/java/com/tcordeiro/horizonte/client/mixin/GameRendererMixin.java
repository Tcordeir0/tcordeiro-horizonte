package com.tcordeiro.horizonte.client.mixin;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.sugar.Local;
import com.tcordeiro.horizonte.client.LodRenderer;

import net.minecraft.client.renderer.GameRenderer;

/**
 * Apanha a projeção que o jogo usa de facto para o mundo neste frame: a da câmara já multiplicada pelo balanço da visão (bobView),
 * pela inclinação de dano (bobHurt) e pela distorção da náusea/portal. A {@code CameraRenderState.projectionMatrix} NÃO tem nada
 * disto, por isso o terreno ao longe ficava parado enquanto os chunks normais balançavam ao andar.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V",
			ordinal = 0), require = 0)
	private void thorizonte$captureProjection(CallbackInfo ci, @Local Matrix4f projectionMatrix) {
		LodRenderer.captureLevelProjection(projectionMatrix);
	}
}
