package com.legoj15.sprawlcrafting.craft;

import java.util.function.Predicate;

import com.legoj15.sprawlcrafting.platform.Services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;

/**
 * "Is there a crafting table the player could interact with right now?" — gates (and pauses) job steps
 * flagged {@code needsFullGrid}.
 *
 * <p>Matches the loader's own use-item validation: the squared distance from the player's position to
 * the block centre must be below {@link com.legoj15.sprawlcrafting.platform.IPlatformHelper#blockInteractionDistanceSqr}
 * (Forge: {@code (REACH_DISTANCE + 3)^2}, so reach-extending gear is honoured; Fabric: vanilla's 64).
 *
 * <p>A large modded reach would make a naive cube scan expensive, so loaded chunk sections are walked
 * and the section palette ({@link ChunkSection#maybeHas}) skips — in O(1) — any section that provably
 * contains no crafting table. Never force-loads a chunk.
 *
 * <p>The scan is the expensive half. {@link #findInReach} returns the table it found so a caller that
 * must keep the gate closed every tick (a burst of crafts between two scans) can re-validate just that
 * one block with {@link #isStillInReach}: a chunk lookup, one block read and the same distance test.
 */
public final class CraftingTableReach {

    private static final Predicate<BlockState> IS_TABLE = state -> state.is(Blocks.CRAFTING_TABLE);

    private CraftingTableReach() {
    }

    public static boolean isInReach(ServerPlayerEntity player) {
        return findInReach(player) != null;
    }

    /**
     * The first crafting table in interaction reach of the player (an immutable position), or null if
     * there is none. This is the full scan; see {@link #isStillInReach} for the cheap re-check.
     */
    public static BlockPos findInReach(ServerPlayerEntity player) {
        double maxDistSqr = Services.PLATFORM.blockInteractionDistanceSqr(player);
        double radius = Math.sqrt(maxDistSqr) + 1.0;
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        int minX = MathHelper.floor(px - radius) >> 4;
        int maxX = MathHelper.floor(px + radius) >> 4;
        int minZ = MathHelper.floor(pz - radius) >> 4;
        int maxZ = MathHelper.floor(pz + radius) >> 4;
        int minSectionY = Math.max(0, MathHelper.floor(py - radius) >> 4);
        int maxSectionY = Math.min(15, MathHelper.floor(py + radius) >> 4);

        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                Chunk chunk = player.getLevel().getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue; // never force-load a chunk just to look for a table
                }
                BlockPos found = scanChunk(chunk, cx, cz, minSectionY, maxSectionY, px, py, pz, maxDistSqr);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Cheap re-validation of a table {@link #findInReach} returned earlier: its chunk is still loaded,
     * the block there is still a crafting table, and it is still within the loader's interaction
     * distance of the player (the same test the scan applies, against the player's current position).
     * Never loads a chunk. Null (no table remembered) is not in reach.
     */
    public static boolean isStillInReach(ServerPlayerEntity player, BlockPos table) {
        if (table == null) {
            return false;
        }
        Chunk chunk = player.getLevel().getChunkSource().getChunkNow(table.getX() >> 4, table.getZ() >> 4);
        return chunk != null && chunk.getBlockState(table).is(Blocks.CRAFTING_TABLE)
                && withinReach(table.getX(), table.getY(), table.getZ(), player.getX(), player.getY(), player.getZ(),
                        Services.PLATFORM.blockInteractionDistanceSqr(player));
    }

    /** Squared distance from the block's centre to the player's position, below the loader's limit. */
    private static boolean withinReach(int x, int y, int z, double px, double py, double pz, double maxDistSqr) {
        double bx = x + 0.5 - px;
        double by = y + 0.5 - py;
        double bz = z + 0.5 - pz;
        return bx * bx + by * by + bz * bz < maxDistSqr;
    }

    private static BlockPos scanChunk(Chunk chunk, int cx, int cz, int minSectionY, int maxSectionY,
                                      double px, double py, double pz, double maxDistSqr) {
        ChunkSection[] sections = chunk.getSections();
        for (int sectionY = minSectionY; sectionY <= maxSectionY && sectionY < sections.length; sectionY++) {
            ChunkSection section = sections[sectionY];
            if (ChunkSection.isEmpty(section) || !section.maybeHas(IS_TABLE)) {
                continue; // palette proves no crafting table in this 16³ section
            }
            for (int dy = 0; dy < 16; dy++) {
                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        if (section.getBlockState(dx, dy, dz).is(Blocks.CRAFTING_TABLE)) {
                            int x = (cx << 4) + dx;
                            int y = (sectionY << 4) + dy;
                            int z = (cz << 4) + dz;
                            if (withinReach(x, y, z, px, py, pz, maxDistSqr)) {
                                return new BlockPos(x, y, z);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }
}
