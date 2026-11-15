package com.tcordeiro.horizonte;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.TicketType;

/**
 * Tcordeiro's Horizonte: terreno ao longe (LOD) que funciona em OpenGL 4.1 (Mac). O mod é quase todo do cliente; aqui só se regista
 * o tipo de «ticket» com que o cliente pede ao servidor integrado os chunks ao longe.
 */
public final class Horizonte implements ModInitializer {
	public static final String MOD_ID = "thorizonte";
	public static final Logger LOG = LoggerFactory.getLogger("Horizonte");

	/** Mantém um chunk carregado (sem simular) enquanto se tira o resumo; expira sozinho 200 ticks depois se ficar esquecido. */
	public static TicketType LOD_TICKET;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		LOD_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, id("lod"), new TicketType(200L, TicketType.FLAG_LOADING));
	}
}
