package com.legoj15.sprawlcrafting.craft;

import java.util.function.Predicate;

import com.legoj15.sprawlcrafting.platform.Services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.ServerPlayerEntity;
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
 */
public final class CraftingTableReach {

    private static final Predicate<BlockState> IS_TABLE = state -> state.is(Blocks.CRAFTING_TABLE);

    private CraftingTableReach() {
    }

    public static boolean isInReach(ServerPlayerEntity player) {
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
                if (scanChunk(chunk, cx, cz, minSectionY, maxSectionY, px, py, pz, maxDistSqr)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean scanChunk(Chunk chunk, int cx, int cz, int minSectionY, int maxSectionY,
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
                            double bx = (cx << 4) + dx + 0.5 - px;
                            double by = (sectionY << 4) + dy + 0.5 - py;
                            double bz = (cz << 4) + dz + 0.5 - pz;
                            if (bx * bx + by * by + bz * bz < maxDistSqr) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
}
