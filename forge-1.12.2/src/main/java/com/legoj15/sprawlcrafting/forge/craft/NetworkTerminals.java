package com.legoj15.sprawlcrafting.forge.craft;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Crafting stations whose screen shows a whole storage network — today exactly one: the Applied
 * Energistics 2 Crafting Terminal ({@code compat.ae2}). Design rule (issue #2): every item a craft
 * draws from must be visible in the open screen. A Crafting Terminal lists the ME network, so while
 * one is the open container the network's items are craft material, alongside the player's own 36
 * slots; nothing off-screen ever is (a plain ME terminal has no grid, adjacent storage is not shown).
 *
 * <p>This class is the core-side seam and never references a mod type: a compat module registers
 * its {@link Provider} at preInit, only after {@code Loader.isModLoaded} says the mod is there, so a
 * pack without AE2 never loads an {@code appeng} class. With no provider every query here answers
 * "no terminal", and every consumer behaves exactly as it did before the feature existed.
 *
 * <p>The four material consumers agree on the pool through this class:
 * <ul>
 *   <li>planning ({@link CraftPlanner#session}, server and client) adds {@link View#stacks};</li>
 *   <li>the client highlight cache ({@code ClientPlanCache}) folds {@link #clientSignature} into its
 *       key so a network change rebuilds the session;</li>
 *   <li>execution ({@link CraftExecutor#craftOnce}) consumes through {@link View#extract} after the
 *       player's slots, and sends overflow that would drop at the player's feet into the network;</li>
 *   <li>grid placement ({@link ServerGridPlacer}) deliberately refuses at a terminal: its grid is not
 *       an {@code InventoryCrafting} and its own JEI handler (which already fills from the network)
 *       keeps the direct "+" path, so the placer never counts or pulls anything there.</li>
 * </ul>
 * The terminal contributes no {@code Slot}-based material at all ({@link ExternalSlots} excludes it):
 * its output slot crafts when taken and its view-cell slots are configuration, not stock.
 */
public final class NetworkTerminals {

    private NetworkTerminals() {
    }

    /** One storage-network mod's terminal support. Implementations live in a compat package. */
    public interface Provider {
        /** Whether {@code container} is one of this mod's network crafting terminals (either side). */
        boolean isTerminal(Container container);

        /**
         * The network behind the player's open terminal, or null when the open container is not a
         * terminal or the network is unusable right now (unpowered, offline). On the server it is
         * live; on the client it is the read-only mirror the terminal screen itself displays.
         */
        View view(EntityPlayer player);

        /**
         * Client only: a cheap value that changes when the open terminal's displayed network
         * contents change (0 when no terminal is open). May lag the screen by a few ticks so a busy
         * network does not rebuild the planning session every frame.
         */
        int clientSignature(EntityPlayer player);
    }

    /** A storage network as craft material. */
    public interface View {
        /**
         * One stack per distinct stored identity (item, metadata, NBT) with a count above zero,
         * clamped to {@link Integer#MAX_VALUE}; private copies the caller may keep. With a non-null
         * {@code items} only identities of those items are returned (execution only ever needs the
         * few items a recipe names, not a thousands-entry network).
         */
        List<ItemStack> stacks(Set<Item> items);

        /**
         * Server only. Extracts up to {@code count} items exactly like {@code like} (item, metadata
         * and NBT), returning how many were (or, simulating, would be) extracted: 0..count. The
         * network charges its own power for the transfer.
         */
        int extract(ItemStack like, int count, boolean simulate);

        /** Server only. Inserts {@code stack} into the network; returns what did not fit (EMPTY if all did). */
        ItemStack insert(ItemStack stack);
    }

    private static volatile Provider provider;

    /** Called once by a compat module after confirming its mod is loaded. */
    public static void register(Provider newProvider) {
        provider = newProvider;
    }

    /** Whether {@code container} is a registered network crafting terminal. */
    public static boolean isTerminal(Container container) {
        Provider current = provider;
        return current != null && container != null && current.isTerminal(container);
    }

    /** Whether the player's open container is a network crafting terminal. */
    public static boolean isOpen(EntityPlayer player) {
        return isTerminal(player.openContainer);
    }

    /** The open terminal's network, or null (no terminal open, no provider, network unusable). */
    public static View view(EntityPlayer player) {
        Provider current = provider;
        if (current == null || !current.isTerminal(player.openContainer)) {
            return null;
        }
        return current.view(player);
    }

    /** All usable-or-not network stacks of the open terminal (empty when none). */
    public static List<ItemStack> stacks(EntityPlayer player) {
        View view = view(player);
        return view == null ? Collections.<ItemStack>emptyList() : view.stacks(null);
    }

    /** Client only: see {@link Provider#clientSignature}. */
    public static int clientSignature(EntityPlayer player) {
        Provider current = provider;
        if (current == null || !current.isTerminal(player.openContainer)) {
            return 0;
        }
        return current.clientSignature(player);
    }

    /** {@code a + b} clamped to {@link Integer#MAX_VALUE}: network counts make int overflow real. */
    public static int saturatedAdd(int a, int b) {
        long sum = (long) a + (long) b;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
