package com.recklessgreed.autoshaftmine.client.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * Client commands to change the settings without ModMenu:
 * {@code /autoshaftmine [list|reset|set <option> <value>]}, shortcut {@code /asm}.
 */
public final class ConfigCommand {
	/** Option name to display value, in the order shown by {@code list}. */
	private static final Map<String, Function<ModConfig, String>> DISPLAY = new LinkedHashMap<>();

	private ConfigCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
			LiteralCommandNode<FabricClientCommandSource> node = dispatcher.register(build());
			dispatcher.register(ClientCommands.literal("asm").executes(node.getCommand()).redirect(node));
		});
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> build() {
		LiteralArgumentBuilder<FabricClientCommandSource> set = ClientCommands.literal("set");
		addBool(set, "advanced_mode", c -> c.advancedMode, (c, v) -> c.advancedMode = v);
		addBool(set, "auto_fill_drops", c -> c.autoFillDrops, (c, v) -> c.autoFillDrops = v);
		addBool(set, "mine_ores", c -> c.mineOres, (c, v) -> c.mineOres = v);
		addBool(set, "backfill_ores", c -> c.backfillOres, (c, v) -> c.backfillOres = v);
		addBool(set, "smooth_movement", c -> c.smoothMovement, (c, v) -> c.smoothMovement = v);
		addBool(set, "stop_on_health_loss", c -> c.stopOnHealthLoss, (c, v) -> c.stopOnHealthLoss = v);
		addInt(set, "max_safe_drop", 1, ModConfig.MAX_SAFE_DROP_LIMIT, ModConfig::safeDrop, (c, v) -> c.maxSafeDrop = v);
		addInt(set, "min_tool_durability", 0, 1000, ModConfig::minToolDurability, (c, v) -> c.minToolDurability = v);
		addInt(set, "branch_length", 1, ModConfig.MAX_BRANCH_LENGTH_LIMIT, ModConfig::branchLength, (c, v) -> c.branchLength = v);
		addInt(set, "lava_lookahead", 1, ModConfig.MAX_LAVA_LOOKAHEAD_LIMIT, ModConfig::lavaLookahead, (c, v) -> c.lavaLookahead = v);

		return ClientCommands.literal("autoshaftmine")
				.executes(ctx -> list(ctx.getSource()))
				.then(ClientCommands.literal("list").executes(ctx -> list(ctx.getSource())))
				.then(ClientCommands.literal("reset").executes(ctx -> {
					ModConfig config = ModConfig.get();
					config.resetToDefaults();
					config.save();
					ctx.getSource().sendFeedback(Component.translatable("command.autoshaftmine.reset"));
					return 1;
				}))
				.then(set);
	}

	private static void addBool(LiteralArgumentBuilder<FabricClientCommandSource> parent, String name,
			Function<ModConfig, Boolean> getter, BiConsumer<ModConfig, Boolean> setter) {
		DISPLAY.put(name, c -> String.valueOf(getter.apply(c)));
		parent.then(ClientCommands.literal(name)
				.then(ClientCommands.argument("value", BoolArgumentType.bool()).executes(ctx -> {
					ModConfig config = ModConfig.get();
					setter.accept(config, BoolArgumentType.getBool(ctx, "value"));
					return saved(ctx.getSource(), config, name, getter.apply(config));
				})));
	}

	private static void addInt(LiteralArgumentBuilder<FabricClientCommandSource> parent, String name, int min, int max,
			Function<ModConfig, Integer> getter, BiConsumer<ModConfig, Integer> setter) {
		DISPLAY.put(name, c -> String.valueOf(getter.apply(c)));
		parent.then(ClientCommands.literal(name)
				.then(ClientCommands.argument("value", IntegerArgumentType.integer(min, max)).executes(ctx -> {
					ModConfig config = ModConfig.get();
					setter.accept(config, IntegerArgumentType.getInteger(ctx, "value"));
					return saved(ctx.getSource(), config, name, getter.apply(config));
				})));
	}

	private static int saved(FabricClientCommandSource source, ModConfig config, String name, Object value) {
		config.save();
		source.sendFeedback(Component.translatable("command.autoshaftmine.set", name, String.valueOf(value)));
		return 1;
	}

	private static int list(FabricClientCommandSource source) {
		ModConfig config = ModConfig.get();
		source.sendFeedback(Component.translatable("command.autoshaftmine.list"));
		DISPLAY.forEach((name, value) -> source.sendFeedback(Component.literal(" " + name + " = " + value.apply(config))));
		return DISPLAY.size();
	}
}
