package com.legoj15.sprawlcrafting.forge.craft;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

/**
 * "Is there a crafting table the player could interact with right now?" — used to gate (and
 * pause) job steps flagged {@code needsFullGrid}.
 *
 * <p>Matches vanilla's {@code ContainerWorkbench.canInteractWith} exactly: a crafting table block
 * whose centre is within squared distance 64 (8 blocks) of the player. 1.12.2 has no
 * block-interaction-range attribute, so the distance is the fixed vanilla 8.
 *
 * <p>Because the search only runs when a 3x3 step is due and no table is in reach (every half
 * second while paused), a bounded 17x17x17 cube scan — pruned to the sphere by the distance check
 * and skipping unloaded blocks — is cheap enough; there is no palette fast-path in 1.12.2.
 */
public final class CraftingTableReach {

    /** Vanilla ContainerWorkbench reach: distance squared from block centre to player feet. */
    private static final double MAX_DISTANCE_SQ = 64.0D;
    private static final int SCAN_RADIUS = 8;

    private CraftingTableReach() {
    }

    public static boolean isInReach(EntityPlayerMP player) {
        World world = player.getEntityWorld();
        int baseX = MathHelper.floor(player.posX);
        int baseY = MathHelper.floor(player.posY);
        int baseZ = MathHelper.floor(player.posZ);

        for (int x = baseX - SCAN_RADIUS; x <= baseX + SCAN_RADIUS; x++) {
            for (int z = baseZ - SCAN_RADIUS; z <= baseZ + SCAN_RADIUS; z++) {
                for (int y = baseY - SCAN_RADIUS; y <= baseY + SCAN_RADIUS; y++) {
                    if (y < 0 || y > 255) {
                        continue;
                    }
                    // Vanilla distance check first, so most of the cube costs no block lookup.
                    if (player.getDistanceSq(x + 0.5D, y + 0.5D, z + 0.5D) > MAX_DISTANCE_SQ) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!world.isBlockLoaded(pos)) {
                        continue; // never force-load a chunk just to look for a table
                    }
                    if (world.getBlockState(pos).getBlock() == Blocks.CRAFTING_TABLE) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
