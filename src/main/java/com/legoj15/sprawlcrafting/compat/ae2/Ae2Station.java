package com.legoj15.sprawlcrafting.compat.ae2;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.legoj15.sprawlcrafting.craft.CraftExecutor;
import com.legoj15.sprawlcrafting.craft.CraftingGrids;
import com.legoj15.sprawlcrafting.craft.StationStorage;
import com.legoj15.sprawlcrafting.craft.StationStorages;

import appeng.api.config.Actionable;
//? if <1.20.5 {
/*import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;*/
//?}
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
//? if >=1.20.5 {
import appeng.api.storage.ITerminalHost;
//?}
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.menu.me.common.IClientRepo;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.me.items.CraftingTermMenu;
import appeng.menu.me.items.WirelessCraftingTermMenu;
import appeng.menu.slot.CraftingMatrixSlot;
import appeng.menu.slot.CraftingTermSlot;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Applied Energistics 2's Crafting Terminal as a crafting station (issue #2). The terminal shows the ME
 * network in the open screen, so while a {@link CraftingTermMenu} (or a subclass, such as AE2's
 * {@link WirelessCraftingTermMenu}) is the player's open container, the network's items are craft
 * material. Decided by menu class only: a plain ME terminal (no grid), adjacent storage and anything
 * not on screen never count.
 *
 * <p>Reached only through {@link StationStorages#bootstrap()}, by reflection, after the loader reports
 * "ae2" loaded; this package is compiled only on nodes that pin {@code ae2_version}. Verified against
 * the decompiled AE2 19.2.17 / 19.2.19 jar (and, for the 1.20.1 line, javap on AE2 15.4.12):
 * <ul>
 *   <li>{@code CraftingTermMenu extends MEStorageMenu}; {@code getEnergySource()} is public on it.
 *       AE2 15 has no such getter: the energy source is {@code MEStorageMenu}'s own
 *       {@code powerSource} field there, read reflectively like {@code storage} below.</li>
 *   <li>{@code MEStorageMenu.storage} is {@code protected final MEStorage}, assigned once from
 *       {@code host.getInventory()} (which must not be called again), so it is read reflectively.</li>
 *   <li>{@code AEBaseMenu.getActionSource()} is the menu's own {@code PlayerSource(player, actionHost)},
 *       the source AE2 itself uses for terminal extractions ({@code moveOneStackToPlayer}).</li>
 *   <li>{@code ILinkStatus.connected()} is what AE2's own {@code canInteractWithGrid} and client
 *       {@code hasIngredient} check. For a terminal on a grid node, {@code ILinkStatus.ofManagedNode}
 *       reports connected exactly when {@code IManagedGridNode.isOnline()}; the other cases are "Out of
 *       power" ({@code !isPowered()}) and "No channel", so an unpowered network is already disconnected.
 *       A wireless terminal's host folds range and the item's charge into the same status.</li>
 *   <li>{@code MEStorageMenu.getLinkStatus()} is a copy refreshed once per menu tick
 *       ({@code broadcastChanges -> updateLinkStatus}) and pushed to the client; the live value is
 *       {@code getHost().getLinkStatus()} ({@code ITerminalHost}).</li>
 *   <li>AE2 15 (the 1.20.1 line, issue #27) has none of the link-status API above: "connected" there is
 *       the menu's power status — server side the live check AE2's own {@code updatePowerStatus} makes
 *       ({@code getNetworkNode()} active, else the energy service / energy source), client side the
 *       synced {@code isPowered()} flag. AE2's {@code canInteractWithGrid} on that line is
 *       {@code storage != null && powerSource != null && isPowered()}, mirrored below. Its
 *       "plain" there is the exact key of a fresh stack ({@code canonical}: a new damageable item already carries {@code Damage:0}), since a tag-less key can still carry capability data and a pristine tool is never tag-less.</li>
 *   <li>{@code StorageHelper.poweredExtraction} caps an extraction at what the energy source can pay
 *       for (one power unit per item), so a connected but power-starved network delivers less than it
 *       stores.</li>
 *   <li>Client side, {@code MEStorageMenu.getClientRepo()} is the screen's repo;
 *       {@code IClientRepo.getAllEntries()} gives {@code GridInventoryEntry}s with the key and stored
 *       amount the server synced (already filtered by the menu's view).</li>
 * </ul>
 * Only plain {@link AEItemKey}s (the key of a fresh stack: no component patch, or on AE2 15 at most
 * the {@code Damage:0} a new damageable stack carries; see {@code canonical}) count, matching
 * {@link StationStorage}'s contract.
 *
 * <p>"Usable" (the {@link StationStorages.Provider} contract) means the terminal is open and its link
 * is connected. Server side that is asked live; the client mirror can only see the synced copy. An
 * unusable terminal counts as no station at all: no counts, no draws. On AE2 15 (1.20.1), which has no
 * link-status API, the online check is the menu's power status instead: the live node/energy state
 * server side, the synced {@code isPowered()} flag client side.
 *
 * <p>The terminal's own 3×3 crafting grid is a {@link CraftingGrids} provider (registered by
 * {@link #init()} alongside the station), so the final craft of a job is laid into it for the player
 * to take. The same usable check gates it, on the side that asks — server the live link, client the
 * synced copy — because {@code GridContext.current} plans an offline terminal as the 2×2 inventory.
 * Its slots are picked out of {@code menu.slots} by class, never by index or reflection: the nine
 * {@link CraftingMatrixSlot}s in slot order and the one {@link CraftingTermSlot}, and any other
 * layout is no grid.
 */
public final class Ae2Station implements StationStorages.Provider {

    private static final Field STORAGE_FIELD = storageField();
    //? if <1.20.5 {
    /*private static final Field POWER_FIELD = powerField();*/
    //?}

    private Ae2Station() {
    }

    /** Entry point, invoked reflectively by {@link StationStorages#bootstrap()}. */
    public static void init() {
        StationStorages.register(new Ae2Station());
        CraftingGrids.register(new TerminalGrids());
    }

    private static Field storageField() {
        try {
            Field field = MEStorageMenu.class.getDeclaredField("storage");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AE2 MEStorageMenu.storage not found; unsupported AE2 version", e);
        }
    }

    //? if <1.20.5 {
    /*private static Field powerField() {
        try {
            Field field = MEStorageMenu.class.getDeclaredField("powerSource");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AE2 MEStorageMenu.powerSource not found; unsupported AE2 version", e);
        }
    }*/
    //?}

    @Override
    public boolean isStationMenu(AbstractContainerMenu menu) {
        return menu instanceof CraftingTermMenu;
    }

    /**
     * Server side: whether {@code menu}'s network is online right now. Asks the host, not the menu's
     * per-tick copy, so a network that lost power this tick is already unusable. (AE2 15, the 1.20.1
     * line, has no link-status API: the live power check stands in, mirroring AE2's own
     * {@code updatePowerStatus}.)
     */
    private static boolean usable(CraftingTermMenu menu) {
        //? if >=1.20.5 {
        ITerminalHost host = menu.getHost();
        return host != null && host.getLinkStatus().connected();
        //?} else {
        /*MEStorage storage;
        IEnergySource powerSource;
        try {
            storage = (MEStorage) STORAGE_FIELD.get(menu);
            powerSource = (IEnergySource) POWER_FIELD.get(menu);
        } catch (IllegalAccessException e) {
            return false;
        }
        // AE2 15's own canInteractWithGrid: no storage or energy source means no grid at all.
        return storage != null && powerSource != null && powered(menu, powerSource);*/
        //?}
    }

    //? if <1.20.5 {
    /*// AE2 15's live power status, what its own updatePowerStatus writes into the per-tick synced flag:
    // an inactive grid node covers "out of power" and "no channel" alike. A wireless terminal's
    // getNetworkNode() is its host's actionable node, null when out of access-point range or the access
    // point is down: no station then (AE2 closes the menu a tick later anyway), never the charge-only check.
    private static boolean powered(CraftingTermMenu menu, IEnergySource powerSource) {
        IGridNode node = menu.getNetworkNode();
        if (node != null) {
            return node.isActive();
        }
        if (menu instanceof WirelessCraftingTermMenu) {
            return false;
        }
        if (powerSource instanceof IEnergyService service) {
            return service.isNetworkPowered();
        }
        return powerSource.extractAEPower(1, Actionable.SIMULATE, PowerMultiplier.CONFIG) > 0.8;
    }

    // "Plain" on AE2 15 is the exact key extract()/available() build (canonical(item)): its keys also
    // carry capability data that hasTag() does not report, and such a key is a separate stored entry the
    // canonical-key extraction can never reach, so a tag-less test alone would over-count it.
    private static boolean plain(AEItemKey key) {
        return key.equals(canonical(key.getItem()));
    }*/
    //?}

    /**
     * The key of a freshly made, untouched stack of {@code item}: the only key the station counts, draws
     * and stores. On 1.20.1 a new stack of a damageable item already carries a {@code Damage:0} tag
     * ({@code ItemStack(ItemLike, int)} calls {@code setDamageValue}), so a pristine tool sits in the
     * network under that tagged key, not under {@code AEItemKey.of(item)}; keying on a fresh stack
     * matches it, while worn, enchanted or renamed copies stay distinct keys and are never touched. On
     * 1.21.1 a fresh stack has no component patch, so this is exactly {@code AEItemKey.of(item)}.
     */
    private static AEItemKey canonical(Item item) {
        //? if >=1.20.5 {
        return AEItemKey.of(item);
        //?} else {
        /*// Memoized: plain() asks for every network entry on every count (client side up to once per
        // tick), and a fresh stack's key serializes its capabilities. It never changes for an item.
        return CANONICAL.computeIfAbsent(item, i -> AEItemKey.of(new ItemStack(i)));*/
        //?}
    }

    //? if <1.20.5 {
    /*private static final Map<Item, AEItemKey> CANONICAL = new java.util.concurrent.ConcurrentHashMap<>();*/
    //?}

    @Override
    public StationStorage open(ServerPlayer player) {
        if (!(player.containerMenu instanceof CraftingTermMenu menu) || !usable(menu)) {
            return null;
        }
        MEStorage storage;
        //? if >=1.20.5 {
        try {
            storage = (MEStorage) STORAGE_FIELD.get(menu);
        } catch (IllegalAccessException e) {
            return null;
        }
        return storage == null ? null : new NetworkStorage(menu, storage, menu.getEnergySource(), menu.getActionSource());
        //?} else {
        /*IEnergySource energy;
        try {
            storage = (MEStorage) STORAGE_FIELD.get(menu);
            energy = (IEnergySource) POWER_FIELD.get(menu);
        } catch (IllegalAccessException e) {
            return null;
        }
        return storage == null || energy == null
                ? null : new NetworkStorage(menu, storage, energy, menu.getActionSource());*/
        //?}
    }

    /**
     * Client side of "usable": the synced online copy, refreshed once per menu tick and pushed to the
     * client (the link status, or AE2 15's power flag) — the client cannot ask the live network. The
     * one check every client-side view of the terminal ({@link #clientCounts}, the grid provider's
     * {@code find}) counts by.
     */
    private static boolean clientOnline(CraftingTermMenu menu) {
        //? if >=1.20.5 {
        return menu.getLinkStatus().connected();
        //?} else {
        /*return menu.isPowered();*/
        //?}
    }

    @Override
    public Map<Item, Long> clientCounts(AbstractContainerMenu menu) {
        // The client only has the synced online copy (the link status, or AE2 15's power flag); a
        // disconnected terminal has no counts.
        if (!(menu instanceof CraftingTermMenu terminal) || !clientOnline(terminal)) {
            return null;
        }
        IClientRepo repo = terminal.getClientRepo();
        if (repo == null) {
            return null;
        }
        Map<Item, Long> counts = new HashMap<>();
        for (GridInventoryEntry entry : repo.getAllEntries()) {
            //? if >=1.20.5 {
            if (entry.getWhat() instanceof AEItemKey key && !key.hasComponents() && entry.getStoredAmount() > 0) {
            //?} else {
            /*if (entry.getWhat() instanceof AEItemKey key && plain(key) && entry.getStoredAmount() > 0) {*/
            //?}
                counts.merge(key.getItem(), entry.getStoredAmount(), Ae2Station::saturatingAdd);
            }
        }
        return counts;
    }

    @Override
    public List<StationStorages.StationMenu> menus() {
        return List.of(
                new StationStorages.StationMenu(CraftingTermMenu.class, CraftingTermMenu.TYPE),
                new StationStorages.StationMenu(WirelessCraftingTermMenu.class, WirelessCraftingTermMenu.TYPE));
    }

    /**
     * The terminal's 3×3 grid as a {@link CraftingGrids.Provider} (the template: the Sophisticated
     * crafting upgrade), so the final-step hand-off lays the last craft into it. Only while the
     * terminal is usable on the asking side — server the live check ({@link #usable}), client the
     * synced copy ({@link #clientOnline}) — keeping this in lockstep with {@code GridContext.current},
     * which plans an offline terminal as the 2×2 inventory: an offline terminal must not be a grid.
     */
    private static final class TerminalGrids implements CraftingGrids.Provider {

        @Override
        public CraftingGrids.Grid find(AbstractContainerMenu menu) {
            if (!(menu instanceof CraftingTermMenu terminal)) {
                return null;
            }
            // AEBaseMenu exposes the player inventory on both sides; its level says which side is asking.
            boolean usableHere = terminal.getPlayerInventory().player.level().isClientSide()
                    ? clientOnline(terminal)
                    : usable(terminal);
            if (!usableHere) {
                return null;
            }
            List<Slot> inputs = new ArrayList<>(9);
            Slot result = null;
            int results = 0;
            for (Slot slot : menu.slots) {
                // The term slot is not a matrix slot, but testing it first keeps a class-hierarchy
                // surprise from ever counting the result cell as an input.
                if (slot instanceof CraftingTermSlot) {
                    results++;
                    result = slot;
                } else if (slot instanceof CraftingMatrixSlot) {
                    inputs.add(slot);
                }
            }
            if (inputs.size() != 9 || results != 1) {
                return null; // not the slot layout this adapter was written against
            }
            return new TerminalGrid(List.copyOf(inputs), result);
        }
    }

    /**
     * The terminal's grid: always 3×3, on screen whenever the menu is, and never a recipe-book menu,
     * so the executor lays a recipe into it through {@link Slot#set} — whose {@code setChanged} makes
     * the menu recompute the result slot — rather than {@code handlePlacement}.
     */
    private record TerminalGrid(List<Slot> inputs, Slot result) implements CraftingGrids.Grid {

        @Override
        public int width() {
            return 3;
        }

        @Override
        public int height() {
            return 3;
        }

        @Override
        public boolean recipeBookPlacement() {
            return false;
        }

        @Override
        public boolean visible() {
            return true;
        }
    }

    /**
     * A stored count at or above {@link Integer#MAX_VALUE} is reported as unbounded ({@link Long#MAX_VALUE}),
     * whatever the energy-capped simulated extraction said. Verified sources: an AE2 creative cell lists
     * every item at exactly Integer.MAX_VALUE and extracts any amount; a Sophisticated Storage infinity
     * upgrade (seen through a storage bus) lists Integer.MAX_VALUE per slot and extracts any amount. The
     * energy cap would otherwise shrink the count to what one extraction could power right now, though
     * crafts draw a few items at a time and re-check power then. The planner reads Integer.MAX_VALUE as
     * unbounded ({@code RecipeGraphSolver.stockCount}); a real network this full is over-trusted and the
     * job pauses or cancels mid-way if it runs dry.
     */
    static long unboundedIfSaturated(long stored, long deliverable) {
        return stored >= Integer.MAX_VALUE ? Long.MAX_VALUE : deliverable;
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    /** The ME network behind one open terminal, for the duration of one plan or craft. */
    private record NetworkStorage(CraftingTermMenu menu, MEStorage storage, IEnergySource energy,
                                  IActionSource source) implements StationStorage {

        /** A plain item the terminal is allowed to show (a portable cell's key-type filter), or null. */
        private AEItemKey key(Item item) {
            AEItemKey key = canonical(item);
            return key != null && menu.isKeyVisible(key) ? key : null;
        }

        /**
         * What extracting {@code limit} of {@code key} would really deliver right now: the same
         * energy-capped call {@link #extract} makes, simulated. The single definition of "available" that
         * {@link #counts} and {@link #available} share, so a plan never counts what the executor's
         * extraction would refuse because the network cannot pay for it.
         */
        private long deliverable(AEItemKey key, long limit) {
            return StorageHelper.poweredExtraction(energy, storage, key, limit, source, Actionable.SIMULATE);
        }

        @Override
        public Map<Item, Long> counts() {
            Map<Item, Long> counts = new HashMap<>();
            if (!usable(menu)) {
                return counts;
            }
            for (Object2LongMap.Entry<AEKey> entry : storage.getAvailableStacks()) {
                //? if >=1.20.5 {
                if (entry.getKey() instanceof AEItemKey key && !key.hasComponents() && entry.getLongValue() > 0
                        && menu.isKeyVisible(key) && CraftExecutor.usableAsIngredient(key.toStack())) {
                //?} else {
                /*if (entry.getKey() instanceof AEItemKey key && plain(key) && entry.getLongValue() > 0
                        && menu.isKeyVisible(key) && CraftExecutor.usableAsIngredient(key.toStack())) {*/
                //?}
                    long deliverable = deliverable(key, entry.getLongValue());
                    if (deliverable > 0) {
                        deliverable = unboundedIfSaturated(entry.getLongValue(), deliverable);
                        counts.merge(key.getItem(), deliverable, Ae2Station::saturatingAdd);
                    }
                }
            }
            return counts;
        }

        @Override
        public long available(Item item, long limit) {
            AEItemKey key = key(item);
            return key == null || limit <= 0 || !usable(menu) ? 0 : deliverable(key, limit);
        }

        @Override
        public int extract(Item item, int count) {
            AEItemKey key = key(item);
            if (key == null || count <= 0) {
                return 0;
            }
            long got = StorageHelper.poweredExtraction(energy, storage, key, count, source, Actionable.MODULATE);
            return (int) Math.max(0, Math.min(got, count));
        }

        @Override
        public ItemStack insert(ItemStack stack) {
            AEItemKey key = stack.isEmpty() ? null : AEItemKey.of(stack);
            // Only component-less stacks go in: counts()/extract() only ever see plain keys, so anything
            // carrying components (damage, enchantments, a name, potion contents...) stored here could
            // never be drawn again. Those fall through to the player's inventory, then the feet.
            //? if >=1.20.5 {
            if (key == null || key.hasComponents() || !menu.isKeyVisible(key)) {
            //?} else {
            /*if (key == null || !plain(key) || !menu.isKeyVisible(key)) {*/
            //?}
                return stack;
            }
            long inserted = StorageHelper.poweredInsert(energy, storage, key, stack.getCount(), source,
                    Actionable.MODULATE);
            if (inserted <= 0) {
                return stack;
            }
            return inserted >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - (int) inserted);
        }

        @Override
        public long acceptable(ItemStack stack, long limit) {
            // insert()'s own checks and power-capped move, simulated for limit items.
            AEItemKey key = stack.isEmpty() || limit <= 0 ? null : AEItemKey.of(stack);
            //? if >=1.20.5 {
            if (key == null || key.hasComponents() || !menu.isKeyVisible(key)) {
            //?} else {
            /*if (key == null || !plain(key) || !menu.isKeyVisible(key)) {*/
            //?}
                return 0;
            }
            return Math.max(0, StorageHelper.poweredInsert(energy, storage, key, limit, source,
                    Actionable.SIMULATE));
        }
    }
}
