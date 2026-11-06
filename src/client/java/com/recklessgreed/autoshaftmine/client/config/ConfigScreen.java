package com.recklessgreed.autoshaftmine.client.config;

import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Simple vanilla settings screen, opened from ModMenu. */
public class ConfigScreen extends Screen {
	private static final int COLUMN_WIDTH = 150;
	private static final int COLUMN_GAP = 10;
	private static final int ROW_HEIGHT = 24;

	private final Screen parent;
	/** Next free grid cell while the buttons are laid out. */
	private int slot;

	public ConfigScreen(Screen parent) {
		super(Component.translatable("config.autoshaftmine.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ModConfig config = ModConfig.get();
		this.slot = 0;

		addToggle("config.autoshaftmine.advanced_mode", () -> config.advancedMode, v -> config.advancedMode = v);
		addCycle(branchLabel(config.branchLength()), b -> {
			config.branchLength = config.branchLength() % ModConfig.MAX_BRANCH_LENGTH_LIMIT + 1;
			b.setMessage(branchLabel(config.branchLength));
		});
		addToggle("config.autoshaftmine.auto_fill_drops", () -> config.autoFillDrops, v -> config.autoFillDrops = v);
		addToggle("config.autoshaftmine.mine_ores", () -> config.mineOres, v -> config.mineOres = v);
		addToggle("config.autoshaftmine.backfill_ores", () -> config.backfillOres, v -> config.backfillOres = v);
		addCycle(dropLabel(config.safeDrop()), b -> {
			config.maxSafeDrop = config.safeDrop() % ModConfig.MAX_SAFE_DROP_LIMIT + 1;
			b.setMessage(dropLabel(config.maxSafeDrop));
		});
		addToggle("config.autoshaftmine.smooth_movement", () -> config.smoothMovement, v -> config.smoothMovement = v);
		addToggle("config.autoshaftmine.stop_on_health_loss", () -> config.stopOnHealthLoss, v -> config.stopOnHealthLoss = v);
		addCycle(durabilityLabel(config.minToolDurability()), b -> {
			config.minToolDurability = nextDurability(config.minToolDurability());
			b.setMessage(durabilityLabel(config.minToolDurability));
		});
		addCycle(lavaLabel(config.lavaLookahead()), b -> {
			config.lavaLookahead = config.lavaLookahead() % ModConfig.MAX_LAVA_LOOKAHEAD_LIMIT + 1;
			b.setMessage(lavaLabel(config.lavaLookahead));
		});

		int doneRow = (this.slot + 1) / 2;
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
				.bounds(this.width / 2 - 100, top() + doneRow * ROW_HEIGHT + 6, 200, 20).build());
	}

	private int top() {
		return this.height / 6;
	}

	/** Adds a button into the next free grid cell (two columns, filled row by row). */
	private void addCell(Button.Builder builder) {
		int column = this.slot % 2;
		int row = this.slot / 2;
		int x = this.width / 2 - COLUMN_WIDTH - COLUMN_GAP / 2 + column * (COLUMN_WIDTH + COLUMN_GAP);
		addRenderableWidget(builder.bounds(x, top() + row * ROW_HEIGHT, COLUMN_WIDTH, 20).build());
		this.slot++;
	}

	private void addCycle(Component initial, Button.OnPress onPress) {
		addCell(Button.builder(initial, onPress));
	}

	private void addToggle(String key, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		addCell(Button.builder(label(key, getter.get()), b -> {
			boolean value = !getter.get();
			setter.accept(value);
			b.setMessage(label(key, value));
		}));
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

	private static Component branchLabel(int value) {
		return Component.translatable("config.autoshaftmine.branch_length", value);
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
