package com.recklessgreed.autoshaftmine.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.recklessgreed.autoshaftmine.AutoShaftMine;

import com.recklessgreed.autoshaftmine.client.config.ConfigCommand;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

public class AutoShaftMineClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(AutoShaftMine.id("main"));

	private final ShaftMiner miner = new ShaftMiner();

	@Override
	public void onInitializeClient() {
		ConfigCommand.register();

		KeyMapping toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.autoshaftmine.toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_G, CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (toggleKey.consumeClick()) {
				miner.toggle(mc);
			}
			if (miner.isActive() && mc.player == null) {
				miner.stop(mc, StopReason.WORLD_LEFT);
			}
			miner.tick(mc);
		});
	}
}
