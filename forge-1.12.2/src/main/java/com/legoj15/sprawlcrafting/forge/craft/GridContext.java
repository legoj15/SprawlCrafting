package com.legoj15.sprawlcrafting.forge.craft;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerWorkbench;

/**
 * The crafting grid a deferred-craft request was made from. Whole-chain gating (DESIGN.md):
 * every step of a job — intermediates and final — must fit this grid. Steps that need the
 * full grid additionally pause at execution time unless a crafting table is within the
 * player's reach.
 *
 * <p>1.12.2 has no generic "grid size" accessor on {@code Container} the way modern versions
 * do, so {@link #current} keys on the open container type: a {@link ContainerWorkbench} (and
 * known modded 3x3 tables) is the 3x3 table, anything else is treated as 2x2.
 */
public enum GridContext {
    /** The player inventory's 2x2 grid. */
    INVENTORY(2, 2),
    /** A crafting table's 3x3 grid. */
    CRAFTING_TABLE(3, 3);

    private static final Set<String> MODDED_3X3_CLASS_NAMES;
    static {
        Set<String> names = new HashSet<String>();
        names.add("slimeknights.tconstruct.tools.common.inventory.ContainerCraftingStation");
        MODDED_3X3_CLASS_NAMES = names;
    }

    private final int width;
    private final int height;

    GridContext(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The grid the player is looking at right now, by open-container type. */
    public static GridContext current(EntityPlayer player) {
        Container container = player.openContainer;
        if (container instanceof ContainerWorkbench) {
            return CRAFTING_TABLE;
        }
        if (isModded3x3Crafter(container)) {
            return CRAFTING_TABLE;
        }
        return INVENTORY;
    }

    /** Walks the class hierarchy looking for a known modded 3x3 crafting container. */
    public static boolean isModded3x3Crafter(Container container) {
        for (Class<?> cls = container.getClass(); cls != null; cls = cls.getSuperclass()) {
            if (MODDED_3X3_CLASS_NAMES.contains(cls.getName())) {
                return true;
            }
        }
        return false;
    }
}
