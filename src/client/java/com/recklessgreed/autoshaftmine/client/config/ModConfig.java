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
	/** Also mine ores below the tunnel floor (the holes are refilled like all ore holes). */
	public boolean mineOresBelow = true;
	/** Walk through the tunnel to ores and holes out of reach, then return to the previous position. */
	public boolean followVeins = true;
	/** Refill the holes left behind by mined ores. */
	public boolean backfillOres = true;
	/** Fill every hole below the feet level (in the shaft and beside it) with a building block. */
	public boolean fillHolesBelow = false;
	/** Edge length of the cube searched for more ore around the last mined ore: 3, 5 or 7. */
	public int veinSearchSize = 3;
	/** Number of empty blocks below the next floor tile that still counts as safe (1-10). */
	public int maxSafeDrop = 1;

	/** Dig side branches (2 forward, 3 left and back, 3 right and back) instead of one straight shaft. */
	public boolean advancedMode = true;
	/** Eased camera turns, pitch that follows the mined block and short reaction pauses. */
	public boolean smoothMovement = true;
	/** Forward steps between two pairs of side branches in advanced mode (1-7). */
	public int forwardLength = 3;
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
	public static final int MAX_FORWARD_LENGTH_LIMIT = 7;
	public static final int MAX_VEIN_SEARCH_SIZE = 7;
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

	/** Clamped value, protects against hand-edited config files. */
	public int forwardLength() {
		return Math.max(1, Math.min(MAX_FORWARD_LENGTH_LIMIT, forwardLength));
	}

	/** Search radius around a mined ore (1-3), derived from the clamped cube size. */
	public int veinSearchRadius() {
		return Math.max(1, Math.min(MAX_VEIN_SEARCH_SIZE / 2, veinSearchSize / 2));
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
		followVeins = defaults.followVeins;
		mineOresBelow = defaults.mineOresBelow;
		maxSafeDrop = defaults.maxSafeDrop;
		stopOnHealthLoss = defaults.stopOnHealthLoss;
		minToolDurability = defaults.minToolDurability;
		lavaLookahead = defaults.lavaLookahead;
		branchLength = defaults.branchLength;
		forwardLength = defaults.forwardLength;
		smoothMovement = defaults.smoothMovement;
		fillHolesBelow = defaults.fillHolesBelow;
		veinSearchSize = defaults.veinSearchSize;
	}

	public void save() {
		try {
			Files.writeString(FILE, GSON.toJson(this));
		} catch (IOException e) {
			// Settings just will not persist.
		}
	}
}
