package com.legoj15.sprawlcrafting.forge.client;

import java.util.HashSet;
import java.util.Set;

import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.forge.craft.ExternalSlots;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;
import com.legoj15.sprawlcrafting.forge.craft.NetworkTerminals;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;

/**
 * Reuses one client-side {@link CraftPlanner.Session} across the many JEI "+" validation queries
 * that happen while the player hovers a recipe list. The session (and its expensive producer index)
 * is rebuilt only when the player's main inventory, the open grid, or a connected station inventory
 * (a Crafting Station's chest) changes, keyed by a cheap content signature. Client-only.
 *
 * <p>A second session — the same pool plus the player's protected stacks (enchanted and/or
 * renamed, never damaged; issue #40) and, while the server counts them (issue #49), an open
 * terminal network's — is kept alongside and consulted only when the server has the
 * protected capability ({@link ServerPresence#protectedCraft}) and either the inventory or such a
 * network holds a protected stack. A recipe only that session can make classifies
 * {@link CraftPlanner.Craftability#DEFERRED}
 * (never direct: vanilla's own placement refuses enchanted/renamed stacks) and is remembered in
 * {@link #isProtectedOnly}, so its confirm click sends the protected start instead of the plain one.
 */
public final class ClientPlanCache {

    private ClientPlanCache() {
    }

    private static CraftPlanner.Session session;
    /**
     * The plain pool plus the player's protected stacks (issue #40) and, with the
     * network-protected capability, an open terminal network's (issue #49); null unless the
     * offer — the server's capability times a source that holds a protected stack — is on.
     * Rebuilt in lockstep with the plain session, so the two never disagree about the pool they
     * share.
     */
    private static CraftPlanner.Session protectedSession;
    /** Whether {@link #protectedSession} was built with the offer on, so a flip rebuilds it. */
    private static boolean protectedBuilt;
    /** Whether it was built with the network's protected tier (#49); a capability flip rebuilds it. */
    private static boolean protectedNetworkBuilt;
    /**
     * The plain session the offer ({@link #protectedOfferOn}) was last computed against: the offer's
     * pool inputs — the inventory's and network's protected stacks — are all part of that session's
     * key, so between rebuilds they cannot change and the network scan runs once per session, not
     * per query on the render path. The capabilities are cached alongside (a flip on reconnect,
     * where the session itself may survive an identical signature, must still recompute).
     */
    private static CraftPlanner.Session offerSession;
    private static boolean offerNetworkProtected;
    private static boolean offerProtectedCraft;
    private static boolean protectedOfferOn;
    private static int signature = Integer.MIN_VALUE;
    private static GridContext cachedGrid;
    /** Recipes we marked deferred only via the protected tier (issue #40); see {@link #classify}. */
    private static final Set<ResourceLocation> protectedOnly = new HashSet<ResourceLocation>();

    public static CraftPlanner.Session get(EntityPlayer player, GridContext grid) {
        int sig = inventorySignature(player);
        if (session == null || sig != signature || grid != cachedGrid) {
            session = CraftPlanner.session(player, grid);
            signature = sig;
            cachedGrid = grid;
            // The twin pool moved too, and so may the offer: drop both and let the next
            // protected query rebuild them (the remembered distinctions die with them).
            protectedSession = null;
            protectedBuilt = false;
            protectedNetworkBuilt = false;
            offerSession = null;
            protectedOnly.clear();
        }
        return session;
    }

    /**
     * The plain session's protected twin (issue #40), or null when the offer is off — the server
     * has not advertised the capability, or neither the 36 main slots nor — while the server
     * counts them (issue #49) — an open terminal's network holds a protected stack. Everything
     * else keeps the plain answer, and any change that flips either (a rebuilt session, a
     * capability flip on reconnect) also invalidates this, so it cannot go stale.
     */
    public static CraftPlanner.Session protectedSession(EntityPlayer player, GridContext grid) {
        CraftPlanner.Session plain = get(player, grid); // keeps the twin in lockstep with the plain session's key
        boolean networkProtected = ServerPresence.networkProtected();
        // The offer spans both sources of the tier (issue #49): the 36 main slots' protected
        // stacks, or the open terminal network's — the latter only while the server advertised it
        // counts them, since a matching-but-older server would refuse the start. Computed once per
        // session and capability flip — the network scan must not run per recipe query on the
        // render path.
        if (offerSession != plain || networkProtected != offerNetworkProtected
                || offerProtectedCraft != ServerPresence.protectedCraft()) {
            offerProtectedCraft = ServerPresence.protectedCraft();
            protectedOfferOn = offerProtectedCraft
                    && CraftPlanner.hasProtectedAnywhere(player, networkProtected);
            offerSession = plain;
            offerNetworkProtected = networkProtected;
        }
        if (protectedBuilt != protectedOfferOn || networkProtected != protectedNetworkBuilt
                || (protectedOfferOn && protectedSession == null)) {
            protectedSession = protectedOfferOn
                    ? CraftPlanner.session(player, grid, true, networkProtected) : null;
            protectedBuilt = protectedOfferOn;
            protectedNetworkBuilt = networkProtected;
            protectedOnly.clear();
        }
        return protectedSession;
    }

    /**
     * The session a gather list is solved over: the protected twin while the offer is on, else the
     * plain one. A confirmed craft consumes those stacks, so the list must not ask for them (#48).
     */
    public static CraftPlanner.Session gatherSession(EntityPlayer player, GridContext grid) {
        CraftPlanner.Session withProtected = protectedSession(player, grid);
        CraftPlanner.Session base = withProtected != null ? withProtected : session;
        if (grid == GridContext.CRAFTING_TABLE) {
            return base;
        }
        // The list is informational (issue #51): from the 2x2 screen it must still cost the whole
        // chain at a crafting table, as the recipe book and the modern trees do. A 2x2-costed
        // session answers an empty demand list for any 3x3 recipe, which the screen then reads as
        // "you already have the materials". Built once per pool: keyed on the session it mirrors,
        // which is replaced whenever the pool or the protected offer moves.
        if (gatherTableFor != base) {
            gatherTable = withProtected != null
                    ? CraftPlanner.session(player, GridContext.CRAFTING_TABLE, true, protectedNetworkBuilt)
                    : CraftPlanner.session(player, GridContext.CRAFTING_TABLE);
            gatherTableFor = base;
        }
        return gatherTable;
    }

    /** The CRAFTING_TABLE-costed twin {@link #gatherSession} hands out from the 2x2 (issue #51). */
    private static CraftPlanner.Session gatherTable;
    /** The session {@link #gatherTable} was built alongside; a different one means rebuild. */
    private static CraftPlanner.Session gatherTableFor;

    /**
     * Direct vs deferred vs unsolvable over the plain pool, widened by the protected tier
     * (issue #40): a recipe the plain session cannot make but the protected one can reports
     * {@link CraftPlanner.Craftability#DEFERRED} — never direct, since vanilla's own placement
     * refuses enchanted/renamed stacks — and is remembered so the confirm sends the protected
     * start ({@link #isProtectedOnly}).
     */
    public static CraftPlanner.Craftability classify(EntityPlayer player, GridContext grid, IRecipe recipe) {
        CraftPlanner.Craftability plain = get(player, grid).classify(recipe);
        if (plain != CraftPlanner.Craftability.UNSOLVABLE) {
            return plain;
        }
        CraftPlanner.Session protectedTier = protectedSession(player, grid);
        if (protectedTier != null && protectedTier.classify(recipe) != CraftPlanner.Craftability.UNSOLVABLE) {
            if (recipe.getRegistryName() != null) {
                protectedOnly.add(recipe.getRegistryName());
            }
            return CraftPlanner.Craftability.DEFERRED;
        }
        return CraftPlanner.Craftability.UNSOLVABLE;
    }

    /** Whether {@code recipe} is makeable only by consuming protected stacks (see {@link #classify}). */
    public static boolean isProtectedOnly(IRecipe recipe) {
        return recipe != null && recipe.getRegistryName() != null
                && protectedOnly.contains(recipe.getRegistryName());
    }

    /**
     * A full plan for the click preview, from the same session the outlines used. A recipe only
     * the protected tier can make gets that plan, with {@code usesProtected} set — the preview's
     * warning lines and the confirm click both key off it.
     */
    public static CraftPlanner.PlanOutcome plan(EntityPlayer player, GridContext grid, IRecipe recipe) {
        CraftPlanner.PlanOutcome outcome = get(player, grid).plan(recipe);
        if (outcome instanceof CraftPlanner.PlanOutcome.Planned) {
            return outcome;
        }
        CraftPlanner.Session protectedTier = protectedSession(player, grid);
        if (protectedTier != null) {
            CraftPlanner.PlanOutcome withProtected = protectedTier.plan(recipe);
            if (withProtected instanceof CraftPlanner.PlanOutcome.Planned) {
                ((CraftPlanner.PlanOutcome.Planned) withProtected).job().setUsesProtected(true);
                return withProtected;
            }
        }
        return outcome; // the plain rejection, with its own message semantics
    }

    private static int inventorySignature(EntityPlayer player) {
        int hash = 1;
        for (int i = 0; i < CraftPlanner.MAIN_INVENTORY_SIZE; i++) {
            hash = 31 * hash + stackHash(player.inventory.mainInventory.get(i));
        }
        // Fold in the open container's other input slots — its crafting grid and a station's
        // connected chest — so the highlight refreshes when those change even though the player's own
        // 36 slots are untouched (items moved into the grid, a hopper feeds the chest). Empty when no
        // crafting container is open.
        for (Slot slot : ExternalSlots.materialSlots(player)) {
            hash = 31 * hash + stackHash(slot.getStack());
        }
        // An open network crafting terminal (AE2): the network is material while it is open, so its
        // displayed contents are part of the key (0 when no terminal is open). The provider throttles
        // this to a few ticks so a busy network does not rebuild the session every frame, and hashes
        // each entry's NBT, so a protected stack (enchanted, renamed) arriving in or leaving the
        // network moves the key — the protected twin rebuilds with it (issue #49).
        hash = 31 * hash + NetworkTerminals.clientSignature(player);
        return hash;
    }

    private static int stackHash(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        int hash = (Item.getIdFromItem(stack.getItem()) * 31 + stack.getMetadata()) * 31 + stack.getCount();
        // The stack's protected state is part of the key (issue #40): renaming or enchanting a stack
        // in place changes neither item, metadata nor count, but moves it between the plain and the
        // protected pool — and with it every protected-only classification. Damaged stacks have
        // their meta (the damage) hashed above already.
        if (stack.isItemEnchanted()) {
            hash = hash * 2 + 1;
        }
        if (stack.hasDisplayName()) {
            hash = hash * 3 + 2;
        }
        return hash;
    }
}
