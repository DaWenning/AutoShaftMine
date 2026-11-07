package com.recklessgreed.autoshaftmine.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

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

	/** How many rows below the feet ores are searched when {@link ModConfig#mineOresBelow} is on. */
	private static final int ORE_DEPTH_BELOW = 2;

	/** Squared distance up to which blocks are mined and placed without moving. */
	private static final double MAX_REACH_SQ = 4.0 * 4.0;

	/** Direction of a pattern segment relative to the direction the run was started in. */
	private enum Turn { FORWARD, LEFT, RIGHT }

	/**
	 * Advanced mode: two blocks forward, a branch to the left and back, then a branch to the right and
	 * back, repeated. The forward and branch lengths come from {@link ModConfig#forwardLength()} and {@link ModConfig#branchLength()}. Walking back re-uses the already mined tunnel.
	 */
	private static final List<Turn> PATTERN = List.of(Turn.FORWARD, Turn.LEFT, Turn.RIGHT, Turn.RIGHT, Turn.LEFT);

	/** Distance to the block middle (in blocks) below which the player is snapped onto it. */
	private static final double CENTER_TOLERANCE = 0.1;

	private final LookController look = new LookController();
	/** Centre of the ore block that is being mined this tick, so the camera can follow it. */
	private Vec3 mineFocus;
	/** Furthest walk (in tunnel cells) to reach ore or holes that are out of reach. */
	private static final int EXCURSION_RANGE = 12;

	/** Ticks one ore excursion may take before the run is stopped. */
	private static final int MAX_EXCURSION_TICKS = 900;

	private boolean active;
	/** Cells to walk through to the next stop of an ore excursion, or null while standing still. */
	private List<BlockPos> walkPath;
	private int pathIndex;
	/** Where the ore excursion started; the player returns here afterwards. Null outside of an excursion. */
	private BlockPos returnCell;
	private Direction savedDirection;
	private boolean returning;
	private int excursionTicks;
	private boolean advanced;
	private Direction mainDirection = Direction.NORTH;
	private int segmentIndex;
	/** Branch length of the current run, read from the config when the run starts. */
	private int branchLength;
	/** Forward steps between two branch pairs, read from the config when the run starts. */
	private int forwardLength;
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
		forwardLength = ModConfig.get().forwardLength();
		segmentIndex = 0;
		segmentStart = player.blockPosition();
		if (advanced) {
			// Start exactly in the middle of the block so every branch is centred as well.
			player.setPos(segmentStart.getX() + 0.5, player.getY(), segmentStart.getZ() + 0.5);
		}
		tunnelCells.clear();
		walkPath = null;
		returnCell = null;
		returning = false;
		excursionTicks = 0;
		look.reset();
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
		tunnelCells.add(feet);
		tunnelCells.add(feet.above());
		if (returnCell != null && ++excursionTicks > MAX_EXCURSION_TICKS) {
			stop(mc, StopReason.STUCK);
			return;
		}
		if (advanced && returnCell == null) {
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

		mineFocus = null;
		if (handleOres(mc, player, feet)) {
			mc.options.keyUp.setDown(false);
			look.aim(player, direction, mineFocus);
			return;
		}
		if (!active) {
			return;
		}
		if (walkPath != null) {
			followPath(mc, player, feet);
			return;
		}
		if (ModConfig.get().fillHolesBelow && fillHoleBelow(mc, player, feet)) {
			mc.options.keyUp.setDown(false);
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

		// Turn towards the direction first and look at the block that is about to be mined.
		boolean aligned = look.aim(player, direction, target == null ? null : Vec3.atCenterOf(target));
		if (!aligned || look.isPausing(player, target)) {
			mc.options.keyUp.setDown(false);
			return;
		}

		if (centering) {
			// Segment length reached: walk to the middle of the block so the turn happens in the tunnel line.
			mc.options.keyUp.setDown(true);
			return;
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
		int length = PATTERN.get(segmentIndex) == Turn.FORWARD ? forwardLength : branchLength;
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
		snapToCenter(player, feet);
		segmentIndex = (segmentIndex + 1) % PATTERN.size();
		segmentStart = feet;
		direction = switch (PATTERN.get(segmentIndex)) {
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
		ModConfig config = ModConfig.get();
		if (!config.mineOres) {
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

		if (walkPath != null) {
			// Walking to the next stop: only ores on the way are mined.
			return false;
		}
		if (!holes.isEmpty() && config.backfillOres && backfill(mc, player, feet)) {
			return true;
		}
		if (config.followVeins && !holes.isEmpty() && planExcursion(mc, feet)) {
			return false;
		}
		holes.clear();
		if (returnCell != null) {
			planReturn(mc, feet);
		}
		return false;
	}

	/**
	 * Looks for ore (next to already mined ore) or holes that are out of reach and a tunnel cell the player
	 * can walk to from where they are within reach. The first stop remembers where to come back to.
	 *
	 * @return true if a walk was planned
	 */
	private boolean planExcursion(Minecraft mc, BlockPos feet) {
		List<Vec3> targets = new ArrayList<>();
		int radius = ModConfig.get().veinSearchRadius();
		for (BlockPos hole : holes) {
			if (ModConfig.get().backfillOres && mc.level.getBlockState(hole).isAir() && !isShaftCell(hole, feet)) {
				targets.add(Vec3.atCenterOf(hole));
			}
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dy = -radius; dy <= radius; dy++) {
					for (int dz = -radius; dz <= radius; dz++) {
						BlockPos pos = hole.offset(dx, dy, dz);
						if (isMinableOre(mc, pos, feet)) {
							targets.add(Vec3.atCenterOf(pos));
						}
					}
				}
			}
		}
		if (targets.isEmpty()) {
			return false;
		}
		List<BlockPos> path = findPath(mc, feet, cell -> !cell.equals(feet) && anyInReach(cell, targets));
		if (path == null) {
			return false;
		}
		if (returnCell == null) {
			returnCell = feet;
			savedDirection = direction;
			excursionTicks = 0;
		}
		walkPath = path;
		pathIndex = 0;
		returning = false;
		return true;
	}

	/** Nothing left to do at this stop: walk back to where the excursion started. */
	private void planReturn(Minecraft mc, BlockPos feet) {
		if (feet.equals(returnCell)) {
			finishExcursion();
			return;
		}
		List<BlockPos> path = findPath(mc, feet, cell -> cell.equals(returnCell));
		if (path == null) {
			stop(mc, StopReason.STUCK);
			return;
		}
		walkPath = path;
		pathIndex = 0;
		returning = true;
	}

	private void finishExcursion() {
		direction = savedDirection;
		walkPath = null;
		returnCell = null;
		returning = false;
		excursionTicks = 0;
		holes.clear();
	}

	/** Walks along {@link #walkPath}, turning at cell middles like the tunnel segments do. */
	private void followPath(Minecraft mc, LocalPlayer player, BlockPos feet) {
		BlockPos next = walkPath.get(pathIndex);
		if (feet.equals(next)) {
			double offset = (player.getX() - (feet.getX() + 0.5)) * direction.getStepX()
					+ (player.getZ() - (feet.getZ() + 0.5)) * direction.getStepZ();
			if (offset >= -CENTER_TOLERANCE) {
				snapToCenter(player, feet);
				pathIndex++;
				if (pathIndex >= walkPath.size()) {
					mc.options.keyUp.setDown(false);
					walkPath = null;
					if (returning) {
						finishExcursion();
					}
				}
				return;
			}
		} else {
			Direction step = stepTo(feet, next);
			if (step == null) {
				stop(mc, StopReason.STUCK);
				return;
			}
			direction = step;
		}
		boolean aligned = look.aim(player, direction, null);
		mc.options.keyUp.setDown(aligned);
	}

	/** The horizontal direction from one cell to a neighbouring one, or null if they are not neighbours. */
	private static Direction stepTo(BlockPos from, BlockPos to) {
		if (from.getY() != to.getY()) {
			return null;
		}
		int dx = to.getX() - from.getX();
		int dz = to.getZ() - from.getZ();
		if (dx == 1 && dz == 0) {
			return Direction.EAST;
		}
		if (dx == -1 && dz == 0) {
			return Direction.WEST;
		}
		if (dx == 0 && dz == 1) {
			return Direction.SOUTH;
		}
		if (dx == 0 && dz == -1) {
			return Direction.NORTH;
		}
		return null;
	}

	/** A cell the player has stood in, so it is known to be open at feet and head level. */
	private boolean isWalkCell(Minecraft mc, BlockPos cell) {
		return tunnelCells.contains(cell) && tunnelCells.contains(cell.above())
				&& mc.level.getBlockState(cell).isAir() && mc.level.getBlockState(cell.above()).isAir();
	}

	/**
	 * Breadth-first search through walkable tunnel cells.
	 *
	 * @return the cells to walk through (without the start), or null if no cell within range matches
	 */
	private List<BlockPos> findPath(Minecraft mc, BlockPos start, Predicate<BlockPos> goal) {
		Map<BlockPos, BlockPos> parents = new HashMap<>();
		Map<BlockPos, Integer> depths = new HashMap<>();
		Deque<BlockPos> queue = new ArrayDeque<>();
		parents.put(start, null);
		depths.put(start, 0);
		queue.add(start);
		while (!queue.isEmpty()) {
			BlockPos cell = queue.poll();
			if (goal.test(cell)) {
				List<BlockPos> path = new ArrayList<>();
				for (BlockPos c = cell; !c.equals(start); c = parents.get(c)) {
					path.add(c);
				}
				Collections.reverse(path);
				return path;
			}
			int depth = depths.get(cell);
			if (depth >= EXCURSION_RANGE) {
				continue;
			}
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				BlockPos neighbour = cell.relative(dir);
				if (!parents.containsKey(neighbour) && isWalkCell(mc, neighbour)) {
					parents.put(neighbour, cell);
					depths.put(neighbour, depth + 1);
					queue.add(neighbour);
				}
			}
		}
		return null;
	}

	private static boolean anyInReach(BlockPos cell, List<Vec3> targets) {
		Vec3 eye = new Vec3(cell.getX() + 0.5, cell.getY() + 1.62, cell.getZ() + 0.5);
		for (Vec3 target : targets) {
			if (eye.distanceToSqr(target) <= MAX_REACH_SQ) {
				return true;
			}
		}
		return false;
	}

	/** Puts the player on the middle of the cell and drops the horizontal momentum. */
	private static void snapToCenter(LocalPlayer player, BlockPos cell) {
		player.setPos(cell.getX() + 0.5, player.getY(), cell.getZ() + 0.5);
		player.setDeltaMovement(0, player.getDeltaMovement().y, 0);
	}

	/**
	 * Looks for the next ore to mine. The search cubes are centred on the player (the trigger), on an
	 * ore in the floor under the player, and on every ore that was already mined out, so a vein is
	 * followed from ore to ore as far as the player can reach.
	 */
	private BlockPos findOre(Minecraft mc, LocalPlayer player, BlockPos feet) {
		BlockPos support = feet.below();
		int minDy = ModConfig.get().mineOresBelow ? -ORE_DEPTH_BELOW : -1;
		BlockPos best = findOreInCube(mc, player, feet, minDy, 2, 1, support, null);
		if (isOre(mc.level.getBlockState(support))) {
			best = findOreInCube(mc, player, support, -1, 1, 1, support, best);
		}
		int radius = ModConfig.get().veinSearchRadius();
		for (BlockPos mined : holes) {
			best = findOreInCube(mc, player, mined, -radius, radius, radius, support, best);
		}
		return best;
	}

	/**
	 * @param center the middle of the cube
	 * @param minDy  lowest row relative to the center
	 * @param maxDy  highest row relative to the center
	 * @param radius horizontal half edge length of the cube
	 * @param skip   a block that must never be mined (the one carrying the player)
	 * @param best   the best candidate so far, returned if nothing closer is found
	 */
	private BlockPos findOreInCube(Minecraft mc, LocalPlayer player, BlockPos center, int minDy, int maxDy, int radius,
			BlockPos skip, BlockPos best) {
		Vec3 eye = player.getEyePosition();
		double bestDist = best == null ? Double.MAX_VALUE : eye.distanceToSqr(Vec3.atCenterOf(best));
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = minDy; dy <= maxDy; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					BlockPos pos = center.offset(dx, dy, dz);
					if (pos.equals(skip)) {
						continue;
					}
					if (!isMinableOre(mc, pos, player.blockPosition())) {
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

	/** An ore that can be mined: breakable and, below the feet, not next to a fluid that would flow into the hole. */
	private static boolean isMinableOre(Minecraft mc, BlockPos pos, BlockPos feet) {
		BlockState state = mc.level.getBlockState(pos);
		if (!isOre(state) || state.getDestroySpeed(mc.level, pos) < 0) {
			return false;
		}
		return pos.getY() >= feet.getY() || !touchesFluid(mc, pos);
	}

	private static boolean touchesFluid(Minecraft mc, BlockPos pos) {
		for (Direction face : Direction.values()) {
			if (!mc.level.getFluidState(pos.relative(face)).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Refills the holes that are within reach. Holes further away stay in {@link #holes}, so a later stop
	 * of an ore excursion can fill them.
	 *
	 * @return true while this phase is busy
	 */
	private boolean backfill(Minecraft mc, LocalPlayer player, BlockPos feet) {
		int slot = findFillerSlot(player);
		if (slot < 0) {
			holes.clear();
			backfillTicks = 0;
			return false;
		}
		Vec3 eye = player.getEyePosition();
		boolean pending = false;
		Iterator<BlockPos> it = holes.iterator();
		while (it.hasNext()) {
			BlockPos hole = it.next();
			if (isShaftCell(hole, feet) || !mc.level.getBlockState(hole).isAir()) {
				it.remove();
				continue;
			}
			if (eye.distanceToSqr(Vec3.atCenterOf(hole)) > MAX_REACH_SQ) {
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
			pending = true;
		}
		if (!pending) {
			backfillTicks = 0;
			return false;
		}
		if (++backfillTicks > MAX_BACKFILL_TICKS) {
			// Give up on the holes in reach that cannot be filled.
			holes.removeIf(hole -> eye.distanceToSqr(Vec3.atCenterOf(hole)) <= MAX_REACH_SQ);
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
		mineFocus = Vec3.atCenterOf(pos);
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

	/**
	 * Places a building block into one air hole below the feet level, in the 3x3 columns around the player.
	 *
	 * @return true if a block was placed
	 */
	private boolean fillHoleBelow(Minecraft mc, LocalPlayer player, BlockPos feet) {
		int slot = findFillerSlot(player);
		if (slot < 0) {
			return false;
		}
		Vec3 eye = player.getEyePosition();
		for (int dy = -1; dy >= -3; dy--) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos hole = feet.offset(dx, dy, dz);
					if (!mc.level.getBlockState(hole).isAir()
							|| eye.distanceToSqr(Vec3.atCenterOf(hole)) > MAX_REACH_SQ) {
						continue;
					}
					for (Direction face : Direction.values()) {
						BlockPos support = hole.relative(face);
						if (!mc.level.getBlockState(support).getCollisionShape(mc.level, support).isEmpty()
								&& placeAgainst(mc, player, support, face.getOpposite(), slot)) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}
}
