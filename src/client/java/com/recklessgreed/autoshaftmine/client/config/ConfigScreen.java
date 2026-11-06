package com.recklessgreed.autoshaftmine.client.config;

import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Simple vanilla settings screen, opened from ModMenu. */
public class ConfigScreen extends Screen {
	private final Screen parent;

	public ConfigScreen(Screen parent) {
		super(Component.translatable("config.autoshaftmine.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ModConfig config = ModConfig.get();
		int x = this.width / 2 - 100;
		int y = this.height / 4;

		addToggle(x, y, "config.autoshaftmine.auto_fill_drops", () -> config.autoFillDrops, v -> config.autoFillDrops = v);
		addToggle(x, y + 24, "config.autoshaftmine.mine_ores", () -> config.mineOres, v -> config.mineOres = v);
		addToggle(x, y + 48, "config.autoshaftmine.backfill_ores", () -> config.backfillOres, v -> config.backfillOres = v);

		addRenderableWidget(Button.builder(dropLabel(config.safeDrop()), b -> {
			config.maxSafeDrop = config.safeDrop() % ModConfig.MAX_SAFE_DROP_LIMIT + 1;
			b.setMessage(dropLabel(config.maxSafeDrop));
		}).bounds(x, y + 72, 200, 20).build());

		addToggle(x, y + 96, "config.autoshaftmine.stop_on_health_loss", () -> config.stopOnHealthLoss, v -> config.stopOnHealthLoss = v);

		addRenderableWidget(Button.builder(durabilityLabel(config.minToolDurability()), b -> {
			config.minToolDurability = nextDurability(config.minToolDurability());
			b.setMessage(durabilityLabel(config.minToolDurability));
		}).bounds(x, y + 120, 200, 20).build());

		addRenderableWidget(Button.builder(lavaLabel(config.lavaLookahead()), b -> {
			config.lavaLookahead = config.lavaLookahead() % ModConfig.MAX_LAVA_LOOKAHEAD_LIMIT + 1;
			b.setMessage(lavaLabel(config.lavaLookahead));
		}).bounds(x, y + 144, 200, 20).build());

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
				.bounds(x, y + 180, 200, 20).build());
	}

	private void addToggle(int x, int y, String key, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		addRenderableWidget(Button.builder(label(key, getter.get()), b -> {
			boolean value = !getter.get();
			setter.accept(value);
			b.setMessage(label(key, value));
		}).bounds(x, y, 200, 20).build());
	}

	private static Component label(String key, boolean value) {
		return Component.translatable(key, value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
	}

	private static Component dropLabel(int value) {
		return Component.translatable("config.autoshaftmine.max_safe_drop", value);
	}

	private static Component durabilityLabel(int value) {
		return Component.translatable("config.autoshaftmine.min_tool_durability", value);
	}

	private static Component lavaLabel(int value) {
		return Component.translatable("config.autoshaftmine.lava_lookahead", value);
	}

	/** Next larger step, wrapping to the smallest one. */
	private static int nextDurability(int current) {
		for (int step : ModConfig.DURABILITY_STEPS) {
			if (step > current) {
				return step;
			}
		}
		return ModConfig.DURABILITY_STEPS[0];
	}

	@Override
	public void onClose() {
		ModConfig.get().save();
		this.minecraft.setScreenAndShow(parent);
	}
}
