package com.legoj15.sprawlcrafting.forge.craft;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
         * Whether the player's open terminal can reach its network right now: powered and, on the
         * server, wired up. The same conditions {@link #view} requires, minus anything that merely
         * reflects client-side screen state (the client's mirror of the network can be unreadable
         * while the terminal is perfectly usable), so client and server agree on it. The caller has
         * already checked {@link #isTerminal} for the open container.
         */
        boolean usable(EntityPlayer player);

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
         * Usable-material counts per {@link ItemKey} for the planner, long: {@link Long#MAX_VALUE}
         * means unbounded, every other value that finite count. The default derives from
         * {@link #stacks}, so it inherits the int-clamped listing and only reads a count at
         * {@link Integer#MAX_VALUE} as unbounded — the client mirror cannot prove more (issue #47),
         * and the server's own plan, which can probe the network (see the live view's override),
         * decides what a job may really consume. {@code items} filters as in {@link #stacks}.
         */
        default Map<ItemKey, Long> counts(Set<Item> items) {
            Map<ItemKey, Long> counts = new HashMap<ItemKey, Long>();
            for (ItemStack stack : stacks(items)) {
                if (!stack.isEmpty() && CraftExecutor.usableAsIngredient(stack)) {
                    long count = stack.getCount();
                    counts.merge(ItemKey.of(stack), count >= Integer.MAX_VALUE ? Long.MAX_VALUE : count,
                            NetworkTerminals::saturatingAdd);
                }
            }
            return counts;
        }

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

    /** Whether any provider is registered, i.e. whether this side can count a network at all. */
    public static boolean hasProvider() {
        return provider != null;
    }

    /**
     * Whether the player's open container is a network crafting terminal, <em>whether or not its
     * network is reachable</em>: a class check. Use {@link #usable} for "can I draw from it now".
     */
    public static boolean isOpen(EntityPlayer player) {
        return isTerminal(player.openContainer);
    }

    /**
     * Whether the player has a terminal open whose network is reachable right now (powered, wired
     * up): the open terminal exists as a station <em>and</em> its network is material. An open but
     * unpowered terminal is {@link #isOpen} yet not usable; it shows an empty screen, so for jobs it
     * is the same as a closed one. Agrees with {@link #view} on the server ({@code usable} iff the
     * view is non-null); on the client it does not depend on the mirror of the network being readable.
     */
    public static boolean usable(EntityPlayer player) {
        Provider current = provider;
        return current != null && current.isTerminal(player.openContainer) && current.usable(player);
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

    /** The open terminal's network as long planner counts (empty when none); see {@link View#counts}. */
    public static Map<ItemKey, Long> counts(EntityPlayer player) {
        View view = view(player);
        return view == null ? Collections.<ItemKey, Long>emptyMap() : view.counts(null);
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

    /** {@code a + b} for non-negative counts, pinned at {@link Long#MAX_VALUE} (unbounded) instead of wrapping. */
    public static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }
}
