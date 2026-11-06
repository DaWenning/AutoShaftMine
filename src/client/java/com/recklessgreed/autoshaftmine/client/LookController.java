package com.recklessgreed.autoshaftmine.client;

import com.recklessgreed.autoshaftmine.client.config.ModConfig;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Moves the camera the way a person would: eased turns instead of instant snaps, a pitch that follows the
 * block being mined, slight wobble, and a short reaction pause whenever a new block becomes the target.
 * With {@link ModConfig#smoothMovement} off it only sets the yaw directly, as before.
 */
final class LookController {
	/** Largest yaw change per tick in degrees. */
	private static final float MAX_YAW_STEP = 22.0F;
	/** Largest pitch change per tick in degrees. */
	private static final float MAX_PITCH_STEP = 9.0F;
	/** Remaining yaw difference in degrees below which the player counts as facing the direction. */
	private static final float ALIGNED_YAW = 6.0F;

	private BlockPos lastTarget;
	private int pauseTicks;
	private float yawOffset;
	private float pitchOffset;

	void reset() {
		lastTarget = null;
		pauseTicks = 0;
		yawOffset = 0;
		pitchOffset = 0;
	}

	/**
	 * @param focus the point to look at, or null to look ahead
	 * @return true once the player faces the walking direction closely enough to walk or mine
	 */
	boolean aim(LocalPlayer player, Direction dir, Vec3 focus) {
		if (!ModConfig.get().smoothMovement) {
			player.setYRot(dir.toYRot());
			return true;
		}

		float targetYaw = dir.toYRot() + yawOffset + (float) Math.sin(player.tickCount * 0.11) * 0.6F;
		float yawDiff = Mth.wrapDegrees(targetYaw - player.getYRot());
		player.setYRot(player.getYRot() + ease(yawDiff, 0.4F, MAX_YAW_STEP));

		float targetPitch = focus != null ? pitchTo(player, focus) : idlePitch(player);
		targetPitch = Mth.clamp(targetPitch + pitchOffset, -60.0F, 60.0F);
		float pitchDiff = targetPitch - player.getXRot();
		player.setXRot(player.getXRot() + ease(pitchDiff, 0.3F, MAX_PITCH_STEP));

		return Math.abs(yawDiff) < ALIGNED_YAW;
	}

	/**
	 * Short, random pause when the target block changes, like a player reacting to the next block. Each
	 * new target also re-rolls the small aim offsets.
	 */
	boolean isPausing(LocalPlayer player, BlockPos target) {
		if (!ModConfig.get().smoothMovement) {
			return false;
		}
		if (target != null && !target.equals(lastTarget)) {
			lastTarget = target;
			pauseTicks = player.getRandom().nextInt(4);
			yawOffset = (player.getRandom().nextFloat() - 0.5F) * 2.0F;
			pitchOffset = (player.getRandom().nextFloat() - 0.5F) * 4.0F;
		}
		if (pauseTicks > 0) {
			pauseTicks--;
			return true;
		}
		return false;
	}

	/** Moves a fraction of the remaining difference, limited per tick, and finishes tiny differences. */
	private static float ease(float diff, float factor, float max) {
		if (Math.abs(diff) < 0.25F) {
			return diff;
		}
		return Mth.clamp(diff * factor, -max, max);
	}

	private static float pitchTo(LocalPlayer player, Vec3 focus) {
		Vec3 eye = player.getEyePosition();
		double dx = focus.x - eye.x;
		double dz = focus.z - eye.z;
		double dy = focus.y - eye.y;
		return (float) -(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI));
	}

	/** Slowly drifting, slightly downward look while nothing is being mined. */
	private static float idlePitch(LocalPlayer player) {
		return 6.0F + (float) Math.sin(player.tickCount * 0.04) * 4.0F;
	}
}
