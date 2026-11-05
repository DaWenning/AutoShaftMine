package com.recklessgreed.autoshaftmine.client;

import com.recklessgreed.autoshaftmine.client.config.ModConfig;

import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Looks ahead of the player for anything that should interrupt the tunnel. */
public final class HazardScanner {
	/** How many blocks ahead lava is looked for. */
	private static final int LAVA_LOOKAHEAD = 2;

	private HazardScanner() {
	}

	/**
	 * @param feet the block position of the player feet
	 * @param dir  the horizontal tunnel direction
	 * @return the reason to stop, or empty if the way is safe
	 */
	public static Optional<StopReason> scan(ClientLevel level, BlockPos feet, Direction dir) {
		if (hasLavaNearby(level, feet, dir)) {
			return Optional.of(StopReason.LAVA);
		}

		BlockPos ahead = feet.relative(dir);
		if (!level.getFluidState(ahead).isEmpty() || !level.getFluidState(ahead.above()).isEmpty()) {
			return Optional.of(StopReason.FLUID);
		}

		if (isDamaging(level.getBlockState(ahead))
				|| isDamaging(level.getBlockState(ahead.above()))
				|| isDamaging(level.getBlockState(ahead.below()))) {
			return Optional.of(StopReason.DAMAGE_BLOCK);
		}

		if (isDangerousDrop(level, ahead)) {
			return Optional.of(StopReason.DROP);
		}

		return Optional.empty();
	}

	/** Lava anywhere in a 3-wide, 4-high box that starts at the player and extends ahead. */
	private static boolean hasLavaNearby(ClientLevel level, BlockPos feet, Direction dir) {
		Direction side = dir.getClockWise();
		for (int forward = 0; forward <= LAVA_LOOKAHEAD; forward++) {
			for (int lateral = -1; lateral <= 1; lateral++) {
				for (int height = -1; height <= 2; height++) {
					BlockPos pos = feet.relative(dir, forward).relative(side, lateral).above(height);
					if (level.getFluidState(pos).is(FluidTags.LAVA)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean isDamaging(BlockState state) {
		return state.is(Blocks.MAGMA_BLOCK)
				|| state.is(Blocks.FIRE)
				|| state.is(Blocks.SOUL_FIRE)
				|| state.is(Blocks.CACTUS);
	}

	private static boolean isDangerousDrop(ClientLevel level, BlockPos ahead) {
		int maxSafeDrop = ModConfig.get().safeDrop();
		for (int depth = 1; depth <= maxSafeDrop; depth++) {
			BlockPos below = ahead.below(depth);
			if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
				return false;
			}
		}
		return true;
	}
}
