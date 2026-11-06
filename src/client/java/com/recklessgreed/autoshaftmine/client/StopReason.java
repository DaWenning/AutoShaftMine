package com.recklessgreed.autoshaftmine.client;

import net.minecraft.network.chat.Component;

/** Why the auto-miner stopped. Each reason maps to a translatable overlay message. */
public enum StopReason {
	MANUAL("manual"),
	LAVA("lava"),
	FLUID("fluid"),
	DROP("drop"),
	DAMAGE_BLOCK("damage_block"),
	HEALTH_LOST("health_lost"),
	TOOL_BREAKING("tool_breaking"),
	UNBREAKABLE("unbreakable"),
	SCREEN_OPENED("screen_opened"),
	WORLD_LEFT("world_left"),
	STUCK("stuck");

	private final String key;

	StopReason(String key) {
		this.key = key;
	}

	public Component message() {
		return Component.translatable("message.autoshaftmine.stopped." + key);
	}
}
