package com.legoj15.sprawlcrafting.forge.craft;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerWorkbench;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;

/**
 * The crafting grid a deferred-craft request was made from. Whole-chain gating (DESIGN.md):
 * every step of a job — intermediates and final — must fit this grid. Steps that need the
 * full grid additionally pause at execution time unless a crafting table is within the
 * player's reach.
 *
 * <p>1.12.2 has no generic "grid size" accessor on {@code Container} the way modern versions do,
 * so {@link #current} decides by inspecting the open container's slots: a container that exposes
 * an {@link InventoryCrafting} matrix at least 3 wide and 3 tall is the 3x3 table, anything else
 * (including the player inventory's 2x2 matrix) is treated as 2x2. This <em>structural</em> test —
 * rather than a hardcoded list of container classes — is what lets an arbitrary modded crafting
 * station (a Tinkers' Construct Crafting Station, its slab-mod re-implementations, FastWorkbench,
 * …) be recognised without SprawlCrafting knowing its class up front. A short class-name allow-list
 * ({@link #WHITELISTED_3X3_CLASS_NAMES}) is kept only as a belt-and-suspenders fallback for a
 * hypothetical station that backs its grid with something other than {@code InventoryCrafting}.
 */
public enum GridContext {
    /** The player inventory's 2x2 grid. */
    INVENTORY(2, 2),
    /** A crafting table's 3x3 grid. */
    CRAFTING_TABLE(3, 3);

    /**
     * Known 3x3 crafting containers, matched by class name (hierarchy-walked). Structural detection
     * already covers every station whose grid is an {@code InventoryCrafting}; this list is only a
     * safety net for one that isn't, so it never needs a new entry for a normal modded crafter.
     */
    private static final Set<String> WHITELISTED_3X3_CLASS_NAMES;
    static {
        Set<String> names = new HashSet<String>();
        names.add("slimeknights.tconstruct.tools.common.inventory.ContainerCraftingStation");
        WHITELISTED_3X3_CLASS_NAMES = names;
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

    /**
     * The grid the player is looking at right now, decided by the open container's slots. A network
     * crafting terminal ({@link NetworkTerminals}) is a station like any other here: it grants 3x3
     * access only while its network is {@link NetworkTerminals#usable usable}. An unpowered terminal
     * still physically has a grid, but its network is unreachable and its screen shows none, so it
     * neither lets a 3x3 step run nor counts as the crafter a planned 3x3 chain needs; a job that
     * depends on the network pauses there instead of cancelling (see {@code CraftQueueManager}).
     */
    public static GridContext current(EntityPlayer player) {
        Container open = player.openContainer;
        if (NetworkTerminals.isTerminal(open)) {
            return NetworkTerminals.usable(player) ? CRAFTING_TABLE : INVENTORY;
        }
        return isThreeByThreeCrafter(open) ? CRAFTING_TABLE : INVENTORY;
    }

    /**
     * Whether {@code container} presents a full 3x3 crafting grid. True for the vanilla crafting
     * table and for any modded crafter (station, slab, fast-bench) whose grid is backed by an
     * {@link InventoryCrafting} of width and height ≥ 3; the class-name allow-list is a fallback for
     * the rare crafter that uses a different grid inventory. False for the 2x2 player inventory grid
     * and for non-crafting menus (a plain chest has no {@code InventoryCrafting}, so it is never
     * mistaken for a crafter — which is what gates {@link ExternalSlots} chest harvesting safely).
     *
     * <p>A network crafting terminal ({@link NetworkTerminals}, the AE2 Crafting Terminal) is a 3x3
     * crafter too, although its grid is an item-handler, not an {@code InventoryCrafting}: it is a
     * cable part, not a table block, so treating it as an open crafter is what lets a 3x3 step run
     * while it is open. This is the structural, container-only answer (class based, whether or not the
     * terminal is powered); {@link #current} is the player-aware one that also requires the terminal
     * to be usable. {@link ExternalSlots} explicitly refuses to harvest its slots, by class too.
     */
    public static boolean isThreeByThreeCrafter(Container container) {
        if (container == null) {
            return false;
        }
        if (container instanceof ContainerWorkbench) {
            return true;
        }
        if (hasThreeByThreeMatrix(container)) {
            return true;
        }
        if (NetworkTerminals.isTerminal(container)) {
            return true;
        }
        return hasWhitelistedClass(container);
    }

    /** True if any of the container's slots is backed by an {@code InventoryCrafting} ≥ 3x3. */
    private static boolean hasThreeByThreeMatrix(Container container) {
        for (Slot slot : container.inventorySlots) {
            IInventory inv = slot.inventory;
            if (inv instanceof InventoryCrafting) {
                InventoryCrafting matrix = (InventoryCrafting) inv;
                if (matrix.getWidth() >= 3 && matrix.getHeight() >= 3) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Walks the class hierarchy looking for a known modded 3x3 crafting container. */
    private static boolean hasWhitelistedClass(Container container) {
        for (Class<?> cls = container.getClass(); cls != null; cls = cls.getSuperclass()) {
            if (WHITELISTED_3X3_CLASS_NAMES.contains(cls.getName())) {
                return true;
            }
        }
        return false;
    }
}
