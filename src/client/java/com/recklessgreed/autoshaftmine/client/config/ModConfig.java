package com.recklessgreed.autoshaftmine.client.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/** User settings, stored as JSON in the Fabric config directory. */
public final class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("autoshaftmine.json");
	private static ModConfig instance;

	/** Place a building block on dangerous drops instead of stopping right away. */
	public boolean autoFillDrops = true;
	/** Mine ores next to the tunnel. */
	public boolean mineOres = true;
	/** Refill the holes left behind by mined ores. */
	public boolean backfillOres = true;
	/** Number of empty blocks below the next floor tile that still counts as safe (1-10). */
	public int maxSafeDrop = 1;

	/** Dig side branches (2 forward, 3 left and back, 3 right and back) instead of one straight shaft. */
	public boolean advancedMode = true;
	/** Eased camera turns, pitch that follows the mined block and short reaction pauses. */
	public boolean smoothMovement = true;
	/** Length of the side branches in advanced mode (1-7). */
	public int branchLength = 3;
	/** Stop as soon as the player loses health. */
	public boolean stopOnHealthLoss = true;
	/** Stop when the tool has this many uses (or fewer) left. */
	public int minToolDurability = 2;
	/** How many blocks ahead lava is looked for (1-5). */
	public int lavaLookahead = 2;

	public static final int MAX_SAFE_DROP_LIMIT = 10;
	public static final int MAX_LAVA_LOOKAHEAD_LIMIT = 5;
	public static final int MAX_BRANCH_LENGTH_LIMIT = 7;
	/** Selectable durability thresholds, the settings screen cycles through them. */
	public static final int[] DURABILITY_STEPS = {2, 5, 10, 25, 50, 100};

	/** Clamped value, protects against hand-edited config files. */
	public int safeDrop() {
		return Math.max(1, Math.min(MAX_SAFE_DROP_LIMIT, maxSafeDrop));
	}

	/** Clamped value, protects against hand-edited config files. */
	public int lavaLookahead() {
		return Math.max(1, Math.min(MAX_LAVA_LOOKAHEAD_LIMIT, lavaLookahead));
	}

	/** Clamped value, protects against hand-edited config files. */
	public int branchLength() {
		return Math.max(1, Math.min(MAX_BRANCH_LENGTH_LIMIT, branchLength));
	}

	/** Never negative, protects against hand-edited config files. */
	public int minToolDurability() {
		return Math.max(0, minToolDurability);
	}

	public static ModConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static ModConfig load() {
		if (Files.exists(FILE)) {
			try {
				ModConfig loaded = GSON.fromJson(Files.readString(FILE), ModConfig.class);
				if (loaded != null) {
					return loaded;
				}
			} catch (IOException | RuntimeException e) {
				// Fall back to defaults on unreadable or broken files.
			}
		}
		return new ModConfig();
	}

	/** Restores every setting to its default value. */
	public void resetToDefaults() {
		ModConfig defaults = new ModConfig();
		advancedMode = defaults.advancedMode;
		autoFillDrops = defaults.autoFillDrops;
		mineOres = defaults.mineOres;
		backfillOres = defaults.backfillOres;
		maxSafeDrop = defaults.maxSafeDrop;
		stopOnHealthLoss = defaults.stopOnHealthLoss;
		minToolDurability = defaults.minToolDurability;
		lavaLookahead = defaults.lavaLookahead;
		branchLength = defaults.branchLength;
		smoothMovement = defaults.smoothMovement;
	}

	public void save() {
		try {
			Files.writeString(FILE, GSON.toJson(this));
		} catch (IOException e) {
			// Settings just will not persist.
		}
	}
}
