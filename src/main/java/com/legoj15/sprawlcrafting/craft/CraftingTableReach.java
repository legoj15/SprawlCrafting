package com.legoj15.sprawlcrafting.craft;

import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

/**
 * "Is there a crafting table the player could interact with right now?" — used to gate
 * (and pause) job steps flagged {@code needsFullGrid}.
 *
 * <p>Matches vanilla's use-item validation exactly: {@code Player.canInteractWithBlock}
 * with the server's 1.0 verification buffer, driven by the block-interaction-range
 * attribute — so reach-extending equipment and mods are honored automatically.
 *
 * <p>The reach attribute can be raised to 64, which would make a naive per-block cube
 * scan cost millions of lookups every retry. Instead we walk loaded chunk sections and
 * use the section palette ({@link LevelChunkSection#maybeHas}) to skip — in O(1) — any
 * section that provably contains no crafting table, so the common "no table nearby"
 * steady state costs a handful of palette checks regardless of reach.
 */
public final class CraftingTableReach {

    /** Vanilla's ServerPlayer.INTERACTION_DISTANCE_VERIFICATION_BUFFER. */
    private static final double INTERACTION_PADDING = 1.0;

    private static final Predicate<BlockState> IS_TABLE = state -> state.is(Blocks.CRAFTING_TABLE);

    private CraftingTableReach() {
    }

    public static boolean isInReach(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        double radius = interactionRange(player) + INTERACTION_PADDING;

        int minX = SectionPos.blockToSectionCoord(eye.x() - radius);
        int maxX = SectionPos.blockToSectionCoord(eye.x() + radius);
        int minZ = SectionPos.blockToSectionCoord(eye.z() - radius);
        int maxZ = SectionPos.blockToSectionCoord(eye.z() + radius);
        int minSectionY = SectionPos.blockToSectionCoord(eye.y() - radius);
        int maxSectionY = SectionPos.blockToSectionCoord(eye.y() + radius);

        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                //? if >=1.21.11 {
                /*LevelChunk chunk = player.level().getChunkSource().getChunkNow(cx, cz);*/
                //?} else {
                LevelChunk chunk = player.serverLevel().getChunkSource().getChunkNow(cx, cz);
                //?}
                if (chunk == null) {
                    continue; // never force-load a chunk just to look for a table
                }
                if (scanChunk(player, chunk, cx, cz, minSectionY, maxSectionY)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean scanChunk(ServerPlayer player, LevelChunk chunk, int cx, int cz,
                                     int minSectionY, int maxSectionY) {
        //? if >=1.21.11 {
        /*int loY = Math.max(minSectionY, chunk.getMinSectionY());
        int hiY = Math.min(maxSectionY, chunk.getMaxSectionY());*/
        //?} else {
        int loY = Math.max(minSectionY, chunk.getMinSection());
        int hiY = Math.min(maxSectionY, chunk.getMaxSection() - 1);
        //?}
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int sectionY = loY; sectionY <= hiY; sectionY++) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
            if (section.hasOnlyAir() || !section.maybeHas(IS_TABLE)) {
                continue; // palette proves no crafting table in this 16³ section
            }
            for (int dy = 0; dy < 16; dy++) {
                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        if (section.getBlockState(dx, dy, dz).is(Blocks.CRAFTING_TABLE)) {
                            pos.set((cx << 4) + dx, SectionPos.sectionToBlockCoord(sectionY) + dy,
                                    (cz << 4) + dz);
                            //? if >=1.21.11 {
                            /*if (player.isWithinBlockInteractionRange(pos, INTERACTION_PADDING)) {*/
                            //?} else if >=1.20.5 {
                            if (player.canInteractWithBlock(pos, INTERACTION_PADDING)) {
                            //?} else {
                            /*if (canInteractWithBlock(player, pos)) {*/
                            //?}
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    /** The player's block-interaction reach: the 1.20.5+ attribute, or the fixed pre-attribute value. */
    private static double interactionRange(ServerPlayer player) {
        //? if >=1.20.5 {
        return player.blockInteractionRange();
        //?} else {
        /*// No reach attribute before 1.20.5: vanilla's pick range was 5.0 in creative, 4.5 otherwise
        // (the same defaults the 1.20.5 attribute adopted).
        return player.isCreative() ? 5.0 : 4.5;*/
        //?}
    }

    //? if <1.20.5 {
    /*// Pre-1.20.5 port of Player.canInteractWithBlock: squared distance from the eye to the block's
    // unit box against (reach + padding)^2, like the 1.20.5 method (which used AABB.distanceToSqr).
    private static boolean canInteractWithBlock(ServerPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        double dx = Math.max(Math.max(pos.getX() - eye.x(), eye.x() - (pos.getX() + 1)), 0.0);
        double dy = Math.max(Math.max(pos.getY() - eye.y(), eye.y() - (pos.getY() + 1)), 0.0);
        double dz = Math.max(Math.max(pos.getZ() - eye.z(), eye.z() - (pos.getZ() + 1)), 0.0);
        double reach = interactionRange(player) + INTERACTION_PADDING;
        return dx * dx + dy * dy + dz * dz < reach * reach;
    }*/
    //?}
}
