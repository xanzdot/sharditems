package com.trippyrainbow;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class TrippyRainbowClient implements ClientModInitializer {
	public static final String MOD_ID = "trippy_rainbow";

	/**
	 * Vanilla adds GameRenderer.END_OF_FRAME_POST_EFFECT to the post-effect list every frame
	 * and silently ignores it when no such chain exists, so the "off" level is just vanilla's own id.
	 */
	private static final Identifier OFF = Identifier.withDefaultNamespace("end_of_frame");

	private static final Identifier[] LEVELS = {
			OFF,
			Identifier.fromNamespaceAndPath(MOD_ID, "mild"),
			Identifier.fromNamespaceAndPath(MOD_ID, "full"),
			Identifier.fromNamespaceAndPath(MOD_ID, "insane")
	};
	private static final String[] NAMES = {"Off", "Mild", "Full", "Insane"};

	/** Start on "Full" so everything is trippy and rainbow straight away. */
	private static int level = 2;
	private static KeyMapping cycleKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		cycleKey = new KeyMapping("key.trippy_rainbow.cycle", 75, category); // K
		KeyMappingHelper.registerKeyMapping(cycleKey);

		apply();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (cycleKey.consumeClick()) {
				level = (level + 1) % LEVELS.length;
				apply();
				if (client.player != null) {
					client.player.sendOverlayMessage(Component.literal("Trippy Rainbow: " + NAMES[level]));
				}
			}
		});
	}

	private static void apply() {
		GameRenderer.END_OF_FRAME_POST_EFFECT = LEVELS[level];
	}
}
