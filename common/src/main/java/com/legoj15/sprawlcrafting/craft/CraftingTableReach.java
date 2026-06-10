package com.legoj15.sprawlcrafting.craft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * "Is there a crafting table the player could interact with right now?" — used to gate
 * (and pause) job steps flagged {@code needsFullGrid}.
 *
 * <p>Matches vanilla's use-item validation exactly: {@code Player.canInteractWithBlock}
 * with the server's 1.0 verification buffer, driven by the block-interaction-range
 * attribute — so reach-extending equipment and mods are honored automatically.
 */
public final class CraftingTableReach {

    /** Vanilla's ServerPlayer.INTERACTION_DISTANCE_VERIFICATION_BUFFER. */
    private static final double INTERACTION_PADDING = 1.0;

    private CraftingTableReach() {
    }

    public static boolean isInReach(ServerPlayer player) {
        Level level = player.serverLevel();
        Vec3 eye = player.getEyePosition();
        double radius = player.blockInteractionRange() + INTERACTION_PADDING;
        BlockPos min = BlockPos.containing(eye.x() - radius, eye.y() - radius, eye.z() - radius);
        BlockPos max = BlockPos.containing(eye.x() + radius, eye.y() + radius, eye.z() + radius);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (level.isLoaded(pos)
                    && level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)
                    && player.canInteractWithBlock(pos, INTERACTION_PADDING)) {
                return true;
            }
        }
        return false;
    }
}
