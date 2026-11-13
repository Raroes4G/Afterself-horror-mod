package com.afterself.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Finds a way on foot through the blocks the client knows: around walls, up single steps, down short drops, through
 * wooden doors, across water. A possessed body is driven by the player's own client, which has no pathfinder of its
 * own, and a body that only walks straight ends up pushing against the first wall.
 */
final class PossessionPath {
    private static final int MAX_VISITS = 1400;
    private static final int RANGE = 40;
    private static final int MAX_DROP = 3;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] CORNERS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static final byte OPEN = 0;
    private static final byte WATER = 1;
    private static final byte DOOR = 2;
    private static final byte SOLID = 3;
    private static final byte TALL = 4;
    private static final byte DANGER = 5;

    private final Level level;
    private final Map<Long, Byte> kinds = new HashMap<>();

    private static final class Node {
        final BlockPos pos;
        final Node parent;
        final double cost;
        final double left;
        boolean done;

        Node(BlockPos pos, Node parent, double cost, double left) {
            this.pos = pos;
            this.parent = parent;
            this.cost = cost;
            this.left = left;
        }
    }

    private PossessionPath(Level level) {
        this.level = level;
    }

    /**
     * The cells to walk through, one after the other, without the starting one. When the goal cannot be reached it
     * leads as close as it could get. Empty when there is nowhere better to be than here.
     */
    static List<BlockPos> find(Level level, BlockPos start, BlockPos goal) {
        return new PossessionPath(level).search(start, goal);
    }

    /**
     * The cell a body at this spot stands in or would land in: here, just below, or beside for one on an edge.
     * With "standing", the exact place of a body that has something under its feet, beside comes before below.
     */
    static BlockPos ground(Level level, BlockPos near, Vec3 standing) {
        PossessionPath look = new PossessionPath(level);
        if (look.standable(near)) {
            return near;
        }
        if (standing != null) {
            BlockPos best = null;
            double bestDistance = 1.2 * 1.2;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos beside = near.offset(dx, 0, dz);
                    double x = beside.getX() + 0.5 - standing.x;
                    double z = beside.getZ() + 0.5 - standing.z;
                    if (x * x + z * z < bestDistance && look.standable(beside)) {
                        best = beside;
                        bestDistance = x * x + z * z;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        for (int down = 1; down <= 4; down++) {
            if (look.standable(near.below(down))) {
                return near.below(down);
            }
            if (!look.passable(near.below(down))) {
                break;
            }
        }
        for (int[] side : SIDES) {
            if (look.standable(near.offset(side[0], 0, side[1]))) {
                return near.offset(side[0], 0, side[1]);
            }
        }
        for (int[] corner : CORNERS) {
            if (look.standable(near.offset(corner[0], 0, corner[1]))) {
                return near.offset(corner[0], 0, corner[1]);
            }
        }
        return look.standable(near.above()) ? near.above() : null;
    }

    /** A wooden door that is shut, which a hand can open. */
    static boolean isShutDoor(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof DoorBlock && DoorBlock.isWoodenDoor(state) && !state.getValue(DoorBlock.OPEN);
    }

    private List<BlockPos> search(BlockPos start, BlockPos goal) {
        PriorityQueue<Node> queue = new PriorityQueue<>((a, b) -> Double.compare(a.cost + a.left, b.cost + b.left));
        Map<Long, Node> seen = new HashMap<>();
        Node first = new Node(start, null, 0.0, left(start, goal));
        seen.put(start.asLong(), first);
        queue.add(first);
        Node best = first;
        int visits = 0;
        while (!queue.isEmpty() && visits < MAX_VISITS) {
            Node node = queue.poll();
            if (node.done) {
                continue;
            }
            node.done = true;
            visits++;
            if (node.left < best.left) {
                best = node;
            }
            if (node.pos.equals(goal)) {
                best = node;
                break;
            }
            for (int[] side : SIDES) {
                BlockPos next = step(node.pos, side[0], side[1]);
                if (next != null) {
                    int climb = next.getY() - node.pos.getY();
                    offer(queue, seen, node, next, 1.0 + (climb > 0 ? 1.0 : climb < 0 ? -climb * 0.5 : 0.0), start, goal);
                }
            }
            for (int[] corner : CORNERS) {
                BlockPos next = node.pos.offset(corner[0], 0, corner[1]);
                // Never around a corner block, never over an edge: both neighbours must be walkable too.
                if (standable(next) && standable(node.pos.offset(corner[0], 0, 0)) && standable(node.pos.offset(0, 0, corner[1]))) {
                    offer(queue, seen, node, next, 1.42, start, goal);
                }
            }
        }
        if (best == first) {
            return List.of();
        }
        List<BlockPos> path = new ArrayList<>();
        for (Node node = best; node.parent != null; node = node.parent) {
            path.add(node.pos);
        }
        Collections.reverse(path);
        return path;
    }

    private void offer(PriorityQueue<Node> queue, Map<Long, Node> seen, Node from, BlockPos next, double stepCost,
            BlockPos start, BlockPos goal) {
        if (Math.abs(next.getX() - start.getX()) > RANGE || Math.abs(next.getZ() - start.getZ()) > RANGE) {
            return;
        }
        byte kind = kind(next);
        double cost = from.cost + stepCost + (kind == WATER ? 2.0 : kind == DOOR ? 1.0 : 0.0);
        Node known = seen.get(next.asLong());
        if (known != null && (known.done || known.cost <= cost)) {
            return;
        }
        Node node = new Node(next, from, cost, left(next, goal));
        seen.put(next.asLong(), node);
        queue.add(node);
    }

    private static double left(BlockPos from, BlockPos goal) {
        double dx = from.getX() - goal.getX();
        double dz = from.getZ() - goal.getZ();
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(from.getY() - goal.getY()) * 0.5;
    }

    /** Where one step to the side ends: on the same level, one block up, or a short drop down. Null when blocked. */
    private BlockPos step(BlockPos from, int dx, int dz) {
        BlockPos side = from.offset(dx, 0, dz);
        if (standable(side)) {
            return side;
        }
        if (!passable(side)) {
            // One block to jump onto, with room overhead to jump.
            BlockPos up = side.above();
            return kind(side) == SOLID && standable(up) && passable(from.above(2)) ? up : null;
        }
        if (!passable(side.above())) {
            return null;
        }
        for (int down = 1; down <= MAX_DROP; down++) {
            BlockPos landing = side.below(down);
            if (standable(landing)) {
                return landing;
            }
            if (!passable(landing)) {
                return null;
            }
        }
        return null;
    }

    private boolean standable(BlockPos pos) {
        byte feet = kind(pos);
        if (feet != OPEN && feet != WATER && feet != DOOR) {
            return false;
        }
        return passable(pos.above()) && (feet == WATER || kind(pos.below()) == SOLID);
    }

    private boolean passable(BlockPos pos) {
        byte kind = kind(pos);
        return kind == OPEN || kind == WATER || kind == DOOR;
    }

    private byte kind(BlockPos pos) {
        Byte known = kinds.get(pos.asLong());
        if (known == null) {
            known = classify(pos);
            kinds.put(pos.asLong(), known);
        }
        return known;
    }

    private byte classify(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getFluidState().is(FluidTags.LAVA) || state.getBlock() instanceof BaseFireBlock || state.is(Blocks.COBWEB)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK)) {
            return DANGER;
        }
        if (state.getBlock() instanceof DoorBlock) {
            return DoorBlock.isWoodenDoor(state) || state.getValue(DoorBlock.OPEN) ? DOOR : TALL;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return state.getFluidState().is(FluidTags.WATER) ? WATER : OPEN;
        }
        double top = shape.max(Direction.Axis.Y);
        // Carpets and the like are walked over; fences and walls are too high to jump.
        return top <= 0.2 ? OPEN : top > 1.0 ? TALL : SOLID;
    }
}
