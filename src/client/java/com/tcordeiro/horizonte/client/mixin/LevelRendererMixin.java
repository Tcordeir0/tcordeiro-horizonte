package com.tcordeiro.horizonte.client.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tcordeiro.horizonte.client.LodRenderer;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/**
 * O terreno ao longe desenha-se no início do passo principal do mundo: o céu já está feito e os chunks normais (do jogo ou do
 * Sodium) ainda não.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	@Shadow
	@Final
	private LevelTargetBundle targets;

	@Inject(method = "lambda$addMainPass$0", at = @At("HEAD"), require = 0)
	private void thorizonte$drawFar(CallbackInfo ci) {
		try {
			LodRenderer.render(levelRenderState.cameraRenderState, targets.main == null ? null : targets.main.get());
		} catch (Throwable t) {
			com.tcordeiro.horizonte.Horizonte.LOG.error("Horizonte: erro a desenhar", t);
		}
	}
}
