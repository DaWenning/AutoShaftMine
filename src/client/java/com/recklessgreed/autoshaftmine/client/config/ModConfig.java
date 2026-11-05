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

	/** Clamped value, protects against hand-edited config files. */
	public int safeDrop() {
		return Math.max(1, Math.min(MAX_SAFE_DROP_LIMIT, maxSafeDrop));
	}
	public static final int MAX_SAFE_DROP_LIMIT = 10;

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

	public void save() {
		try {
			Files.writeString(FILE, GSON.toJson(this));
		} catch (IOException e) {
			// Settings just will not persist.
		}
	}
}
