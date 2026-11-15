package com.tcordeiro.horizonte.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.sugar.Local;
import com.tcordeiro.horizonte.HorizonteConfig;
import com.tcordeiro.horizonte.client.HorizonteClient;

import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;

/**
 * Com o terreno ao longe ligado, a neblina «da distância de visão» passa a acabar no fim do LOD em vez de no fim dos chunks
 * normais (que senão ficavam com uma parede de neblina à frente do terreno ao longe). A neblina de ambiente (água, lava, cegueira,
 * chuva…) fica como está.
 * <p>
 * Muda-se logo a seguir ao jogo escrever {@code renderDistanceEnd} (e não no RETURN): o Sodium copia a FogData no RETURN para a
 * neblina dos chunks dele, e tem de copiar já os valores novos.
 */
@Mixin(value = FogRenderer.class, priority = 500)
public abstract class FogRendererMixin {
	@Inject(method = "setupFog", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/fog/FogData;renderDistanceEnd:F",
			opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER))
	private void thorizonte$pushFog(CallbackInfoReturnable<FogData> cir, @Local FogData fd) {
		if (!HorizonteClient.fogActive() || fd == null) return;
		float end = HorizonteConfig.get().distance * 16f;
		if (end <= fd.renderDistanceEnd) return;
		fd.renderDistanceStart = end * 0.6f;
		fd.renderDistanceEnd = end;
	}
}
