package com.recklessgreed.autoshaftmine.client;

import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Walks forward and digs a 1x2 tunnel with the tool in the main hand.
 * Runs entirely on the client by driving the same inputs a human would use.
 */
public final class ShaftMiner {
	/** Stop when the tool has this many uses (or fewer) left. */
	private static final int MIN_REMAINING_DURABILITY = 2;

	private boolean active;
	private Direction direction = Direction.NORTH;
	private float lastHealth;

	public boolean isActive() {
		return active;
	}

	public void toggle(Minecraft mc) {
		if (active) {
			stop(mc, StopReason.MANUAL);
		} else {
			start(mc);
		}
	}

	private void start(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null || mc.gui.screen() != null) {
			return;
		}
		direction = player.getDirection();
		lastHealth = player.getHealth();
		active = true;
		player.sendOverlayMessage(Component.translatable("message.autoshaftmine.started"));
	}

	public void stop(Minecraft mc, StopReason reason) {
		if (!active) {
			return;
		}
		active = false;
		mc.options.keyUp.setDown(false);
		if (mc.gameMode != null) {
			mc.gameMode.stopDestroyBlock();
		}
		if (mc.player != null) {
			mc.player.sendOverlayMessage(reason.message());
		}
	}

	/** Called once per client tick. */
	public void tick(Minecraft mc) {
		if (!active) {
			return;
		}
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null) {
			active = false;
			return;
		}
		if (mc.gui.screen() != null) {
			stop(mc, StopReason.SCREEN_OPENED);
			return;
		}

		if (player.getHealth() < lastHealth) {
			stop(mc, StopReason.HEALTH_LOST);
			return;
		}
		lastHealth = player.getHealth();

		if (isToolAlmostBroken(player.getMainHandItem())) {
			stop(mc, StopReason.TOOL_BREAKING);
			return;
		}

		BlockPos feet = player.blockPosition();
		Optional<StopReason> hazard = HazardScanner.scan(mc.level, feet, direction);
		if (hazard.isPresent()) {
			stop(mc, hazard.get());
			return;
		}

		player.setYRot(direction.toYRot());

		BlockPos ahead = feet.relative(direction);
		// Head level first so gravel or sand above cannot pile into the tunnel unnoticed.
		BlockPos target = null;
		if (!mc.level.getBlockState(ahead.above()).isAir()) {
			target = ahead.above();
		} else if (!mc.level.getBlockState(ahead).isAir()) {
			target = ahead;
		}

		if (target == null) {
			mc.options.keyUp.setDown(true);
			return;
		}

		BlockState state = mc.level.getBlockState(target);
		if (state.getDestroySpeed(mc.level, target) < 0) {
			stop(mc, StopReason.UNBREAKABLE);
			return;
		}

		mc.options.keyUp.setDown(false);
		mc.gameMode.continueDestroyBlock(target, direction.getOpposite());
		player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getAttackAnimation(), true);
	}

	private static boolean isToolAlmostBroken(ItemStack stack) {
		return stack.isDamageableItem()
				&& stack.getMaxDamage() - stack.getDamageValue() <= MIN_REMAINING_DURABILITY;
	}
}
