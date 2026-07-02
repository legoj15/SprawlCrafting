package com.legoj15.sprawlcrafting.forge.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.InventoryCraftResult;
import net.minecraft.inventory.Slot;

/**
 * Discovers the "extra" item slots a modded crafting station exposes alongside its grid — most
 * notably the connected chest a Tinkers' Construct Crafting Station binds when placed next to an
 * inventory. TConstruct adds that chest as real {@link Slot}s in the open {@link Container} (a
 * side panel), so SprawlCrafting can read and consume them through the vanilla container API with
 * <em>no</em> compile-time TConstruct dependency and no reflection: whatever the station chose to
 * expose is exactly what is in the container, on both the synced client view and the authoritative
 * server view.
 *
 * <p>An "external" slot is any container slot whose backing inventory is neither the player
 * inventory, a crafting matrix, nor a craft-result — i.e. not one of the slot kinds a vanilla
 * crafting container is built from. That generic predicate matches the station's bound side
 * inventory (and any future modded crafter that surfaces one) while excluding the 36 player slots
 * (already counted elsewhere), the 3x3 grid, and the output.
 *
 * <p>Harvesting of the external (chest) slots is gated to containers SprawlCrafting recognises as
 * 3x3 crafters ({@link GridContext#isThreeByThreeCrafter}); a plain chest GUI, which carries
 * non-player slots too, has no crafting matrix and so is deliberately never treated as a crafting
 * material pool.
 *
 * <p>{@link #materialSlots} additionally surfaces the open container's <em>crafting grid</em> (any
 * {@link InventoryCrafting}) — for a vanilla table, FastWorkbench, or Crafting Station alike — so
 * items staged in the grid count toward craftability. That mirrors what
 * {@code CraftExecutor.clearOpenCraftingGrid} already does at craft time (it sweeps the grid into
 * the inventory before planning); without it the highlight ignores the grid while execution uses
 * it. The grid is gate-free because {@code InventoryCrafting} is an unambiguous crafting-input type.
 * The chest-only helpers ({@link #of}, {@link #present}) stay distinct: only the connected
 * inventory carries the "pause until the station is reopened" lifecycle.
 *
 * <p>The matrix exclusion relies on the station's 3x3 grid being backed by an {@link InventoryCrafting}
 * — true for TConstruct (its {@code InventoryCraftingPersistent extends InventoryCrafting}). A fork
 * that backed the grid with a raw item handler instead would slip its grid slots into this pool; the
 * type-based test is the right generic guard, but a maintainer porting to such a fork should re-check
 * it (the grid is also moved to the player by {@code CraftExecutor.clearOpenCraftingGrid}, so a miss
 * would double-count grid items).
 */
public final class ExternalSlots {

    private ExternalSlots() {
    }

    /** Whether {@code slot} is a station side-inventory slot (not player / matrix / result). */
    public static boolean isExternal(Slot slot) {
        IInventory inv = slot.inventory;
        return inv != null
                && !(inv instanceof InventoryPlayer)
                && !(inv instanceof InventoryCrafting)
                && !(inv instanceof InventoryCraftResult);
    }

    /** Whether {@code slot} is a crafting-grid (matrix) input slot. */
    public static boolean isMatrix(Slot slot) {
        return slot.inventory instanceof InventoryCrafting;
    }

    /**
     * The open container's input slots beyond the player's 36 main slots: its crafting grid (any
     * container) plus, at a recognised station, the connected side inventory (its chest). Used by
     * planning, the highlight-cache signature, and execution so all three count the same pool. The
     * returned {@link Slot}s are live references into the open container, valid for reading
     * ({@link Slot#getStack}) and consuming ({@link Slot#decrStackSize}).
     */
    public static List<Slot> materialSlots(EntityPlayer player) {
        Container container = player.openContainer;
        if (container == null) {
            return Collections.emptyList();
        }
        boolean station = GridContext.isThreeByThreeCrafter(container);
        // A non-empty result slot means the grid currently holds a COMPLETE, takeable craft — a
        // recipe the server-side placer just staged (JEI "+", READY_IN_GRID hand-off) or the
        // player laid out by hand. That is deliberate arrangement, not loose material: excluding
        // the matrix then keeps a running job from cannibalizing the staged recipe out from under
        // the player (which would silently blank the result they were about to take). Loose
        // sweep-leftovers — the case the matrix is in this pool for — never form a craft, so the
        // result slot is empty and they stay counted.
        boolean staged = hasCompleteCraft(container);
        List<Slot> slots = null;
        for (Slot slot : container.inventorySlots) {
            if ((isMatrix(slot) && !staged) || (station && isExternal(slot))) {
                if (slots == null) {
                    slots = new ArrayList<Slot>();
                }
                slots.add(slot);
            }
        }
        return slots == null ? Collections.<Slot>emptyList() : slots;
    }

    /** Whether the container's craft-result slot currently shows an output (grid matches a recipe). */
    private static boolean hasCompleteCraft(Container container) {
        for (Slot slot : container.inventorySlots) {
            if (slot.inventory instanceof InventoryCraftResult && slot.getHasStack()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The external (connected-inventory) slots of the player's open container, or an empty list
     * when the open container is not a recognised modded 3x3 crafter or exposes no side inventory.
     * The returned {@link Slot}s are live references into the open container, valid for both reading
     * ({@link Slot#getStack}) and consuming ({@link Slot#decrStackSize}).
     */
    public static List<Slot> of(EntityPlayer player) {
        Container container = player.openContainer;
        if (container == null || !GridContext.isThreeByThreeCrafter(container)) {
            return Collections.emptyList();
        }
        List<Slot> external = null;
        for (Slot slot : container.inventorySlots) {
            if (isExternal(slot)) {
                if (external == null) {
                    external = new ArrayList<Slot>();
                }
                external.add(slot);
            }
        }
        return external == null ? Collections.<Slot>emptyList() : external;
    }

    /** True if the player currently has a recognised station open that exposes a connected inventory. */
    public static boolean present(EntityPlayer player) {
        Container container = player.openContainer;
        if (container == null || !GridContext.isThreeByThreeCrafter(container)) {
            return false;
        }
        for (Slot slot : container.inventorySlots) {
            if (isExternal(slot)) {
                return true;
            }
        }
        return false;
    }
}
