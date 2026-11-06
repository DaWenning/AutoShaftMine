package com.recklessgreed.autoshaftmine.client;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.recklessgreed.autoshaftmine.client.config.ModConfig;

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Walks forward and digs a 1x2 tunnel with the tool in the main hand.
 * Runs entirely on the client by driving the same inputs a human would use.
 */
public final class ShaftMiner {
	/** How many consecutive fill attempts may fail to remove the drop before giving up. */
	private static final int MAX_FILL_ATTEMPTS = 3;

	/** Ticks the backfill phase may take before leftover holes are abandoned. */
	private static final int MAX_BACKFILL_TICKS = 60;

	/** Squared distance up to which blocks are mined and placed without moving. */
	private static final double MAX_REACH_SQ = 4.0 * 4.0;

	/** Direction of a pattern segment relative to the direction the run was started in. */
	private enum Turn { FORWARD, LEFT, RIGHT }

	/** @param length number of blocks, or {@link #BRANCH} for the configured branch length */
	private record Segment(Turn turn, int length) {
		static final int BRANCH = 0;
	}

	/**
	 * Advanced mode: two blocks forward, a branch to the left and back, then a branch to the right and
	 * back, repeated. The branch length comes from {@link ModConfig#branchLength()}. Walking back re-uses the already mined tunnel.
	 */
	private static final List<Segment> PATTERN = List.of(
			new Segment(Turn.FORWARD, 2),
			new Segment(Turn.LEFT, Segment.BRANCH),
			new Segment(Turn.RIGHT, Segment.BRANCH),
			new Segment(Turn.RIGHT, Segment.BRANCH),
			new Segment(Turn.LEFT, Segment.BRANCH));

	/** Distance to the block middle (in blocks) below which the player is snapped onto it. */
	private static final double CENTER_TOLERANCE = 0.1;

	private boolean active;
	private boolean advanced;
	private Direction mainDirection = Direction.NORTH;
	private int segmentIndex;
	/** Branch length of the current run, read from the config when the run starts. */
	private int branchLength;
	private BlockPos segmentStart;
	/** Tunnel cells the player has been in; they are never refilled, so the way back stays open. */
	private final Set<BlockPos> tunnelCells = new HashSet<>();
	private int fillAttempts;
	/** Ore block that is currently being mined. */
	private BlockPos oreTarget;
	/** Cells that were mined out as ore and still need to be refilled. */
	private final Set<BlockPos> holes = new HashSet<>();
	private int backfillTicks;
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
		mainDirection = player.getDirection();
		direction = mainDirection;
		advanced = ModConfig.get().advancedMode;
		branchLength = ModConfig.get().branchLength();
		segmentIndex = 0;
		segmentStart = player.blockPosition();
		if (advanced) {
			// Start exactly in the middle of the block so every branch is centred as well.
			player.setPos(segmentStart.getX() + 0.5, player.getY(), segmentStart.getZ() + 0.5);
		}
		tunnelCells.clear();
		fillAttempts = 0;
		oreTarget = null;
		holes.clear();
		backfillTicks = 0;
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
		// The chat is the only screen that does not interrupt mining.
		if (mc.gui.screen() != null && !(mc.gui.screen() instanceof ChatScreen)) {
			stop(mc, StopReason.SCREEN_OPENED);
			return;
		}

		if (ModConfig.get().stopOnHealthLoss && player.getHealth() < lastHealth) {
			stop(mc, StopReason.HEALTH_LOST);
			return;
		}
		lastHealth = player.getHealth();

		if (isToolAlmostBroken(player.getMainHandItem())) {
			stop(mc, StopReason.TOOL_BREAKING);
			return;
		}

		BlockPos feet = player.blockPosition();
		boolean centering = false;
		if (advanced) {
			tunnelCells.add(feet);
			tunnelCells.add(feet.above());
			centering = updateSegment(player, feet);
		}
		Optional<StopReason> hazard = HazardScanner.scan(mc.level, feet, direction);
		if (hazard.isPresent()) {
			if (hazard.get() == StopReason.DROP && ModConfig.get().autoFillDrops && fillAttempts < MAX_FILL_ATTEMPTS
					&& tryFillDrop(mc, player, feet)) {
				fillAttempts++;
				mc.options.keyUp.setDown(false);
				return;
			}
			stop(mc, hazard.get());
			return;
		}
		fillAttempts = 0;

		if (handleOres(mc, player, feet)) {
			mc.options.keyUp.setDown(false);
			return;
		}

		player.setYRot(direction.toYRot());

		if (centering) {
			// Segment length reached: walk to the middle of the block so the turn happens in the tunnel line.
			mc.options.keyUp.setDown(true);
			return;
		}

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

	/**
	 * Switches to the next pattern segment once the current one is done.
	 *
	 * @return true while the player has reached the end block of the segment but is not yet past its middle
	 */
	private boolean updateSegment(LocalPlayer player, BlockPos feet) {
		Segment segment = PATTERN.get(segmentIndex);
		int length = segment.length() == Segment.BRANCH ? branchLength : segment.length();
		int dx = direction.getStepX();
		int dz = direction.getStepZ();
		int travelled = (feet.getX() - segmentStart.getX()) * dx + (feet.getZ() - segmentStart.getZ()) * dz;
		if (travelled < length) {
			return false;
		}
		double offset = (player.getX() - (feet.getX() + 0.5)) * dx + (player.getZ() - (feet.getZ() + 0.5)) * dz;
		if (travelled == length && offset < -CENTER_TOLERANCE) {
			return true;
		}
		// Snap onto the block middle and drop the horizontal momentum. Without that the player slides on
		// into the side opening after the turn and then hits the wall of the next segment.
		player.setPos(feet.getX() + 0.5, player.getY(), feet.getZ() + 0.5);
		player.setDeltaMovement(0, player.getDeltaMovement().y, 0);
		segmentIndex = (segmentIndex + 1) % PATTERN.size();
		segmentStart = feet;
		direction = switch (PATTERN.get(segmentIndex).turn()) {
			case FORWARD -> mainDirection;
			case LEFT -> mainDirection.getCounterClockWise();
			case RIGHT -> mainDirection.getClockWise();
		};
		return false;
	}

	/**
	 * Places a cheap solid block on the empty floor tile ahead, clicking the side face of the
	 * floor block the player stands on. Restores the previously selected hotbar slot afterwards.
	 *
	 * @return true if a block was placed
	 */
	private boolean tryFillDrop(Minecraft mc, LocalPlayer player, BlockPos feet) {
		BlockPos support = feet.below();
		if (mc.level.getBlockState(support).getCollisionShape(mc.level, support).isEmpty()) {
			return false;
		}
		int slot = findFillerSlot(player);
		if (slot < 0) {
			return false;
		}

		return placeAgainst(mc, player, support, direction, slot);
	}

	/**
	 * Places the block from the given hotbar slot against one face of {@code support}, so it lands at
	 * {@code support.relative(face)}. Restores the previously selected slot afterwards.
	 */
	private static boolean placeAgainst(Minecraft mc, LocalPlayer player, BlockPos support, Direction face, int slot) {
		int previous = player.getInventory().getSelectedSlot();
		player.getInventory().setSelectedSlot(slot);
		Vec3 hitPos = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		BlockHitResult hit = new BlockHitResult(hitPos, face, support, false);
		InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		player.getInventory().setSelectedSlot(previous);
		if (result.consumesAction()) {
			player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getAttackAnimation(), true);
			return true;
		}
		return false;
	}

	/**
	 * Mines ores in the 3x3x3 cube around the player, one after another, and rescans after each.
	 * Once no ore is left, the holes that were dug are refilled so only the shaft stays open.
	 *
	 * @return true while this phase is busy and the normal tunnelling must wait
	 */
	private boolean handleOres(Minecraft mc, LocalPlayer player, BlockPos feet) {
		if (!ModConfig.get().mineOres) {
			oreTarget = null;
			holes.clear();
			return false;
		}
		if (oreTarget != null) {
			if (!isOre(mc.level.getBlockState(oreTarget))) {
				holes.add(oreTarget);
				oreTarget = null;
			} else {
				mineBlock(mc, player, oreTarget);
				return true;
			}
		}

		BlockPos ore = findOre(mc, player, feet);
		if (ore != null) {
			oreTarget = ore;
			mineBlock(mc, player, ore);
			return true;
		}

		return !holes.isEmpty() && ModConfig.get().backfillOres && backfill(mc, player, feet);
	}

	/**
	 * Looks for the next ore to mine. The search cubes are centred on the player (the trigger), on an
	 * ore in the floor under the player, and on every ore that was already mined out, so a vein is
	 * followed from ore to ore as far as the player can reach.
	 */
	private BlockPos findOre(Minecraft mc, LocalPlayer player, BlockPos feet) {
		BlockPos support = feet.below();
		BlockPos best = findOreInCube(mc, player, feet, -1, 2, support, null);
		if (isOre(mc.level.getBlockState(support))) {
			best = findOreInCube(mc, player, support, -1, 1, support, best);
		}
		for (BlockPos mined : holes) {
			best = findOreInCube(mc, player, mined, -1, 1, support, best);
		}
		return best;
	}

	/**
	 * @param center the middle of the 3-wide cube
	 * @param minDy  lowest row relative to the center
	 * @param maxDy  highest row relative to the center
	 * @param skip   a block that must never be mined (the one carrying the player)
	 * @param best   the best candidate so far, returned if nothing closer is found
	 */
	private BlockPos findOreInCube(Minecraft mc, LocalPlayer player, BlockPos center, int minDy, int maxDy,
			BlockPos skip, BlockPos best) {
		Vec3 eye = player.getEyePosition();
		double bestDist = best == null ? Double.MAX_VALUE : eye.distanceToSqr(Vec3.atCenterOf(best));
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = minDy; dy <= maxDy; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos pos = center.offset(dx, dy, dz);
					if (pos.equals(skip)) {
						continue;
					}
					BlockState state = mc.level.getBlockState(pos);
					if (!isOre(state) || state.getDestroySpeed(mc.level, pos) < 0) {
						continue;
					}
					double dist = eye.distanceToSqr(Vec3.atCenterOf(pos));
					if (dist <= MAX_REACH_SQ && dist < bestDist) {
						bestDist = dist;
						best = pos;
					}
				}
			}
		}
		return best;
	}

	private boolean backfill(Minecraft mc, LocalPlayer player, BlockPos feet) {
		int slot = findFillerSlot(player);
		if (slot < 0 || ++backfillTicks > MAX_BACKFILL_TICKS) {
			holes.clear();
			backfillTicks = 0;
			return false;
		}
		Iterator<BlockPos> it = holes.iterator();
		while (it.hasNext()) {
			BlockPos hole = it.next();
			if (isShaftCell(hole, feet) || !mc.level.getBlockState(hole).isAir()) {
				it.remove();
				continue;
			}
			for (Direction face : Direction.values()) {
				BlockPos support = hole.relative(face);
				if (!mc.level.getBlockState(support).getCollisionShape(mc.level, support).isEmpty()
						&& placeAgainst(mc, player, support, face.getOpposite(), slot)) {
					it.remove();
					return true;
				}
			}
		}
		if (holes.isEmpty()) {
			backfillTicks = 0;
			return false;
		}
		return true;
	}

	/** The shaft is the 1x2 column in the tunnel line at feet and head height. */
	private boolean isShaftCell(BlockPos pos, BlockPos feet) {
		boolean inLine = direction.getAxis() == Direction.Axis.X ? pos.getZ() == feet.getZ() : pos.getX() == feet.getX();
		return tunnelCells.contains(pos)
				|| inLine && (pos.getY() == feet.getY() || pos.getY() == feet.getY() + 1);
	}

	/** Explicit list, because the c:ores tag is missing when the server does not provide it. */
	private static final java.util.Set<net.minecraft.world.level.block.Block> ORE_BLOCKS = java.util.Set.of(
			Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
			Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
			Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
			Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE,
			Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
			Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
			Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
			Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
			Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS);

	private static boolean isOre(BlockState state) {
		return state.is(ConventionalBlockTags.ORES) || ORE_BLOCKS.contains(state.getBlock());
	}

	private void mineBlock(Minecraft mc, LocalPlayer player, BlockPos pos) {
		mc.gameMode.continueDestroyBlock(pos, direction.getOpposite());
		player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getAttackAnimation(), true);
	}

	/** Finds a hotbar slot holding a plain, non-falling building block. */
	private static int findFillerSlot(LocalPlayer player) {
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.getItem() instanceof BlockItem blockItem && isFiller(blockItem.getBlock())) {
				return slot;
			}
		}
		return -1;
	}

	private static boolean isFiller(Block block) {
		return block.defaultBlockState().is(BlockTags.BASE_STONE_OVERWORLD)
				|| block.defaultBlockState().is(BlockTags.BASE_STONE_NETHER)
				|| block == Blocks.COBBLESTONE
				|| block == Blocks.COBBLED_DEEPSLATE
				|| block == Blocks.DIRT
				|| block == Blocks.NETHERRACK;
	}

	private static boolean isToolAlmostBroken(ItemStack stack) {
		return stack.isDamageableItem()
				&& stack.getMaxDamage() - stack.getDamageValue() <= ModConfig.get().minToolDurability();
	}
}
