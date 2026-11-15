package com.tcordeiro.horizonte.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tcordeiro.horizonte.test.FrameClock;

import net.minecraft.client.Minecraft;

/** Marca o fim de cada frame real do ciclo do jogo (para o FPS verdadeiro, não por tick). */
@Mixin(Minecraft.class)
public abstract class FrameClockMixin {
	@Inject(method = "runTick", at = @At("RETURN"))
	private void thorizonteTest$frame(boolean advanceGameTime, CallbackInfo ci) {
		FrameClock.frame();
	}
}
