package com.legoj15.sprawlcrafting.compat.ae2;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.legoj15.sprawlcrafting.craft.CraftExecutor;
import com.legoj15.sprawlcrafting.craft.CraftingGrids;
import com.legoj15.sprawlcrafting.craft.StationStorage;
import com.legoj15.sprawlcrafting.craft.StationStorages;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.container.me.common.GridInventoryEntry;
import appeng.container.me.common.IClientRepo;
import appeng.container.me.common.MEMonitorableContainer;
import appeng.container.me.items.CraftingTermContainer;
import appeng.container.slot.CraftingMatrixSlot;
import appeng.container.slot.CraftingTermSlot;
import appeng.items.storage.ViewCellItem;
import appeng.util.Platform;
import appeng.util.prioritylist.IPartitionList;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Applied Energistics 2's Crafting Terminal as a crafting station on 1.16.5 (issue #29, the port of #2 /
 * #27). The terminal shows the ME network in the open screen, so while a {@link CraftingTermContainer} is
 * the player's open container and usable, the network's items are craft material. Decided by container
 * class only: a plain ME terminal (no grid), adjacent storage and anything not on screen never count.
 *
 * <p>Reached only through {@link StationStorages#bootstrap()}, by reflection, after Forge reports
 * "appliedenergistics2" loaded; the Fabric jar has no copy of this package. Verified with javap against
 * the AE2 8.4.7 Forge jar:
 * <ul>
 *   <li>{@code CraftingTermContainer extends ItemTerminalContainer extends
 *       MEMonitorableContainer<IAEItemStack> extends AEBaseContainer}; the only host in 8.4.7 is
 *       {@code CraftingTerminalPart} (there is no wireless crafting terminal on this line:
 *       {@code WirelessTermContainer} is a plain terminal).</li>
 *   <li>{@code MEMonitorableContainer.monitor} and {@code .powerSource} are {@code protected final}, set
 *       once in the constructor (server side only), so they are read reflectively.</li>
 *   <li>AE2's own {@code canInteractWithGrid} is {@code monitor != null && powerSource != null &&
 *       isPowered()}, and {@code isPowered()} is a flag {@code updatePowerStatus} refreshes once per menu
 *       tick and syncs: {@code getNetworkNode().isActive()}, else {@code IEnergyGrid.isNetworkPowered()},
 *       else a simulated 1 AE extraction above 0.8. The server check below repeats that live; the client
 *       can only read the synced flag.</li>
 *   <li>{@code Platform.poweredExtraction/poweredInsert} copy the request and cap the move at what the
 *       energy source can pay for, so a powered but starved network delivers less than it stores.</li>
 *   <li>{@code IMEMonitor.extractItems(request, Actionable.SIMULATE, source)} — the {@code IMEInventory}
 *       method under {@code Platform.poweredExtraction} — simulates with no energy cap: a creative cell
 *       answers any amount, a real network at most what it holds. That probe is what tells an infinite
 *       source from a merely huge one ({@code NetworkStorage#unboundedIfProbed}, #47).</li>
 *   <li>View cells: AE2 filters the client repo in the screen, and its own crafting and JEI flows apply
 *       {@code ViewCellItem.createFilter(getViewCells())} (null for no cells); both sides here apply it, so
 *       only items the screen can show ever count.</li>
 *   <li>Client side, {@code getClientRepo().getAllEntries()} gives {@code GridInventoryEntry}s with the
 *       stack and stored amount the server synced.</li>
 * </ul>
 *
 * <p>Item identity: on 1.16.5 {@code new ItemStack(item)} of a damageable item already carries a
 * {@code {Damage:0}} tag (the constructor calls {@code setDamageValue}), and AE2 keys stored items by
 * {@code ItemStack.matches} (item, tag and Forge capabilities). So "plain" is "AE2-equal to a freshly made
 * stack" ({@link #canonical}): a pristine tool counts and is drawn under its real key, while worn,
 * enchanted, renamed or capability-carrying copies stay separate keys and are never touched.
 *
 * <p>The terminal's 3×3 grid is a {@link CraftingGrids} provider, registered alongside the station, so a
 * job's final craft is laid into it for the player to take. The same usable check gates it on the asking
 * side, because {@code GridContext.current} plans an unusable terminal as the 2×2 inventory.
 */
public final class Ae2Station implements StationStorages.Provider {

    private static final Field MONITOR_FIELD = field("monitor");
    private static final Field POWER_FIELD = field("powerSource");

    /** The AE2 key of a fresh stack per item; never mutated (callers copy before setting a size). */
    private static final Map<Item, IAEItemStack> CANONICAL = new ConcurrentHashMap<>();

    private Ae2Station() {
    }

    /** Entry point, invoked reflectively by {@link StationStorages#bootstrap()}. */
    public static void init() {
        StationStorages.register(new Ae2Station());
        CraftingGrids.register(new TerminalGrids());
    }

    private static Field field(String name) {
        try {
            Field field = MEMonitorableContainer.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AE2 MEMonitorableContainer." + name + " not found; unsupported AE2 version", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static IMEMonitor<IAEItemStack> monitor(CraftingTermContainer menu) {
        try {
            return (IMEMonitor<IAEItemStack>) MONITOR_FIELD.get(menu);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static IEnergySource power(CraftingTermContainer menu) {
        try {
            return (IEnergySource) POWER_FIELD.get(menu);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /**
     * Server side: whether {@code menu} can reach its network right now. Asked live rather than through
     * the per-tick {@code isPowered()} copy, so a network that lost power this tick is already unusable.
     */
    private static boolean usable(CraftingTermContainer menu) {
        if (!menu.isValidContainer()) {
            return false;
        }
        IEnergySource powerSource = power(menu);
        return monitor(menu) != null && powerSource != null && powered(menu, powerSource);
    }

    // AE2's own updatePowerStatus, live: an inactive grid node covers "out of power" and "no channel" alike.
    private static boolean powered(CraftingTermContainer menu, IEnergySource powerSource) {
        IGridNode node = menu.getNetworkNode();
        if (node != null) {
            return node.isActive();
        }
        if (powerSource instanceof IEnergyGrid) {
            return ((IEnergyGrid) powerSource).isNetworkPowered();
        }
        return powerSource.extractAEPower(1, Actionable.SIMULATE, PowerMultiplier.CONFIG) > 0.8;
    }

    /**
     * The AE2 key of a freshly made, untouched stack of {@code item} (see the class doc on identity), or
     * null for an item AE2 cannot store. Memoized: {@link #plain} asks for every network entry on every
     * count (client side up to once per tick), and the answer never changes for an item.
     */
    private static IAEItemStack canonical(IStorageChannel<IAEItemStack> channel, Item item) {
        if (item == null || item == Items.AIR) {
            return null;
        }
        IAEItemStack key = CANONICAL.get(item);
        if (key == null) {
            key = channel.createStack(new ItemStack(item));
            if (key != null) {
                CANONICAL.put(item, key);
            }
        }
        return key;
    }

    /**
     * Whether {@code stack} is AE2-equal to the fresh stack of its item: the only plain identity the
     * station uses. In 8.4.7 {@code AEItemStack.equals} is {@code isSameType}, which is tag-sensitive —
     * the full ItemStack comparison, item, damage and NBT (only the amount is left out, so a size-1
     * canonical matches a network entry of any size) — so this is identity-exact: a worn, enchanted,
     * renamed or capability-carrying copy is a different AE2 key and never plain. What keeps enchanted
     * and renamed entries out of {@code counts()}/{@code clientCounts} is nevertheless
     * {@code usableAsIngredient} (the protected tier, #49, counts them separately in
     * {@code protectedCounts()}); damaged ones are never material at all.
     */
    private static boolean plain(IStorageChannel<IAEItemStack> channel, IAEItemStack stack) {
        IAEItemStack canonical = canonical(channel, stack.getItem());
        return canonical != null && canonical.equals(stack);
    }

    /** The menu's view-cell filter, or null when it has no view cells (everything is shown). */
    private static IPartitionList<IAEItemStack> viewFilter(CraftingTermContainer menu) {
        return ViewCellItem.createFilter(menu.getViewCells());
    }

    private static boolean shown(IPartitionList<IAEItemStack> filter, IAEItemStack stack) {
        return filter == null || filter.isListed(stack);
    }

    @Override
    public StationStorage open(ServerPlayerEntity player) {
        if (!(player.containerMenu instanceof CraftingTermContainer)) {
            return null;
        }
        CraftingTermContainer menu = (CraftingTermContainer) player.containerMenu;
        if (!usable(menu)) {
            return null;
        }
        return new NetworkStorage(menu, monitor(menu), power(menu), menu.getActionSource(),
                menu.getStorageChannel(), viewFilter(menu));
    }

    /**
     * Client-side counts from the server-synced repo, for colouring only (yellow outlines, click
     * previews): the server's own plan — which can probe the network (see
     * {@code NetworkStorage#unboundedIfProbed}) — decides what a job may really consume (#47). This
     * side has no monitor to probe, so a stored amount at or above {@link Integer#MAX_VALUE} keeps the
     * old listing rule: unbounded ({@link Long#MAX_VALUE}), exactly what an AE2 creative cell or a
     * Sophisticated Storage infinity upgrade lists.
     */
    @Override
    public Map<Item, Long> clientCounts(Container menu) {
        // The client only has the synced power flag; an unpowered terminal has no counts.
        if (!(menu instanceof CraftingTermContainer)) {
            return null;
        }
        CraftingTermContainer terminal = (CraftingTermContainer) menu;
        IClientRepo<IAEItemStack> repo = terminal.getClientRepo();
        if (!terminal.isPowered() || repo == null) {
            return null;
        }
        IStorageChannel<IAEItemStack> channel = terminal.getStorageChannel();
        IPartitionList<IAEItemStack> filter = viewFilter(terminal);
        Map<Item, Long> counts = new HashMap<>();
        for (GridInventoryEntry<IAEItemStack> entry : repo.getAllEntries()) {
            IAEItemStack stack = entry.getStack();
            if (stack != null && entry.getStoredAmount() > 0 && plain(channel, stack) && shown(filter, stack)
                    && CraftExecutor.usableAsIngredient(stack.getDefinition())) {
                long stored = entry.getStoredAmount();
                counts.merge(stack.getItem(), stored >= Integer.MAX_VALUE ? Long.MAX_VALUE : stored,
                        Ae2Station::saturatingAdd);
            }
        }
        return counts;
    }

    /**
     * Client side of {@link StationStorage#protectedCounts()} (issue #49): the same synced repo as
     * {@link #clientCounts}, selecting the protected entries instead — shown by the view filter,
     * {@code isProtected}, and NOT plain (a protected copy is its own AE2 key). This side cannot probe
     * the network, so the listing rule of {@link #clientCounts} applies to these too.
     */
    @Override
    public Map<Item, Long> clientProtectedCounts(Container menu) {
        ProtectedClientScan scan = clientProtectedScan(menu);
        return scan == null ? null : scan.counts;
    }

    /** {@link #clientProtectedCounts} plus one count-1 sample per item, in one repo scan (issue #49). */
    @Override
    public Map<Item, ItemStack> clientProtectedSamples(Container menu) {
        ProtectedClientScan scan = clientProtectedScan(menu);
        return scan == null ? null : scan.samples;
    }

    /** Both protected client mirrors of one repo pass, or null when {@code menu} is not a usable station. */
    private static ProtectedClientScan clientProtectedScan(Container menu) {
        // The client only has the synced power flag; an unpowered terminal has no counts.
        if (!(menu instanceof CraftingTermContainer)) {
            return null;
        }
        CraftingTermContainer terminal = (CraftingTermContainer) menu;
        IClientRepo<IAEItemStack> repo = terminal.getClientRepo();
        if (!terminal.isPowered() || repo == null) {
            return null;
        }
        IStorageChannel<IAEItemStack> channel = terminal.getStorageChannel();
        IPartitionList<IAEItemStack> filter = viewFilter(terminal);
        ProtectedClientScan scan = new ProtectedClientScan();
        for (GridInventoryEntry<IAEItemStack> entry : repo.getAllEntries()) {
            IAEItemStack stack = entry.getStack();
            if (stack == null || entry.getStoredAmount() <= 0 || !shown(filter, stack)
                    || !CraftExecutor.isProtected(stack.getDefinition()) || plain(channel, stack)) {
                continue;
            }
            long stored = entry.getStoredAmount();
            scan.counts.merge(stack.getItem(), stored >= Integer.MAX_VALUE ? Long.MAX_VALUE : stored,
                    Ae2Station::saturatingAdd);
            ItemStack definition = stack.getDefinition();
            if (definition != null && !scan.samples.containsKey(stack.getItem())) {
                ItemStack sample = definition.copy();
                sample.setCount(1);
                scan.samples.put(stack.getItem(), sample);
            }
        }
        return scan;
    }

    /** The two protected client mirrors of one scan (issue #49). */
    private static final class ProtectedClientScan {
        final Map<Item, Long> counts = new HashMap<>();
        final Map<Item, ItemStack> samples = new HashMap<>();
    }

    @Override
    public List<Class<? extends Container>> menus() {
        // Class only: CraftingTermContainer.TYPE is a registry object; touching it here is not needed.
        return Collections.<Class<? extends Container>>singletonList(CraftingTermContainer.class);
    }

    /**
     * The terminal's 3×3 grid as a {@link CraftingGrids.Provider}, so the final-step hand-off lays the last
     * craft into it. Only while the terminal is usable on the asking side (server live, client the synced
     * flag), in lockstep with {@code GridContext.current}: an unusable terminal must not be a grid.
     */
    private static final class TerminalGrids implements CraftingGrids.Provider {

        @Override
        public CraftingGrids.Grid find(Container menu) {
            if (!(menu instanceof CraftingTermContainer)) {
                return null;
            }
            CraftingTermContainer terminal = (CraftingTermContainer) menu;
            boolean usableHere = terminal.isRemote() ? terminal.isPowered() : usable(terminal);
            if (!usableHere) {
                return null;
            }
            List<Slot> inputs = new ArrayList<>(9);
            Slot result = null;
            int results = 0;
            for (Slot slot : menu.slots) {
                // The term slot is not a matrix slot, but testing it first keeps a class-hierarchy surprise
                // from ever counting the result cell as an input.
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
            return new TerminalGrid(Collections.unmodifiableList(inputs), result);
        }
    }

    /**
     * The terminal's grid: always 3×3, on screen whenever the menu is, and no recipe-book menu, so the
     * executor lays a recipe into it through {@link Slot#set}, whose {@code CraftingMatrixSlot} override
     * makes the container recompute the result slot.
     */
    private static final class TerminalGrid implements CraftingGrids.Grid {

        private final List<Slot> inputs;
        private final Slot result;

        TerminalGrid(List<Slot> inputs, Slot result) {
            this.inputs = inputs;
            this.result = result;
        }

        @Override
        public int width() {
            return 3;
        }

        @Override
        public int height() {
            return 3;
        }

        @Override
        public List<Slot> inputs() {
            return inputs;
        }

        @Override
        public Slot result() {
            return result;
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
     * The probe amount for {@code NetworkStorage#unboundedIfProbed}: a quarter of the long range, far
     * above any real stock — even a network holding billions of one item (issue #47) — so only a source
     * that hands out any amount can supply the whole request.
     */
    private static final long PROBE = Long.MAX_VALUE / 4;

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    /** The ME network behind one open terminal, for the duration of one plan or craft. */
    private static final class NetworkStorage implements StationStorage {

        private final CraftingTermContainer menu;
        private final IMEMonitor<IAEItemStack> monitor;
        private final IEnergySource energy;
        private final IActionSource source;
        private final IStorageChannel<IAEItemStack> channel;
        private final IPartitionList<IAEItemStack> filter;

        NetworkStorage(CraftingTermContainer menu, IMEMonitor<IAEItemStack> monitor, IEnergySource energy,
                       IActionSource source, IStorageChannel<IAEItemStack> channel,
                       IPartitionList<IAEItemStack> filter) {
            this.menu = menu;
            this.monitor = monitor;
            this.energy = energy;
            this.source = source;
            this.channel = channel;
            this.filter = filter;
        }

        /** A request for {@code amount} plain {@code item} the screen is allowed to show, or null. */
        private IAEItemStack request(Item item, long amount) {
            IAEItemStack key = canonical(channel, item);
            return key == null || !shown(filter, key) ? null : key.copy().setStackSize(amount);
        }

        /**
         * What extracting {@code limit} of {@code item} would really deliver right now: the same energy-capped
         * call {@link #extract} makes, simulated. The single definition of "available" that {@link #counts}
         * and {@link #available} share, so a plan never counts what the extraction would refuse.
         */
        private long deliverable(Item item, long limit) {
            IAEItemStack request = request(item, limit);
            if (request == null) {
                return 0;
            }
            IAEItemStack got = Platform.poweredExtraction(energy, monitor, request, source, Actionable.SIMULATE);
            return got == null ? 0 : Math.max(0, Math.min(got.getStackSize(), limit));
        }

        @Override
        public Map<Item, Long> counts() {
            Map<Item, Long> counts = new HashMap<>();
            if (!usable(menu)) {
                return counts;
            }
            for (IAEItemStack stack : monitor.getStorageList()) {
                if (stack != null && stack.getStackSize() > 0 && plain(channel, stack) && shown(filter, stack)
                        && CraftExecutor.usableAsIngredient(stack.getDefinition())) {
                    long count = count(stack);
                    if (count > 0) {
                        counts.merge(stack.getItem(), count, Ae2Station::saturatingAdd);
                    }
                }
            }
            return counts;
        }

        /**
         * The count one stored stack plans as: the energy-capped {@link #deliverable} as a finite long
         * (no int clamp — a real network can hold billions of one item, #47), or unbounded
         * ({@link Long#MAX_VALUE}) when a probe proves the source behind the stack hands out any
         * amount of it.
         */
        private long count(IAEItemStack stack) {
            long deliverable = deliverable(stack.getItem(), stack.getStackSize());
            if (deliverable > 0 && stack.getStackSize() >= Integer.MAX_VALUE
                    && unboundedIfProbed(stack.getItem())) {
                return Long.MAX_VALUE;
            }
            return deliverable;
        }

        /**
         * Whether the network hands out any amount of {@code item}: an UNPOWERED simulated extraction of
         * {@link Ae2Station#PROBE} returns at least {@code PROBE}. The call is
         * {@code monitor.extractItems(request, Actionable.SIMULATE, source)} — the {@code IMEInventory}
         * API under {@code Platform.poweredExtraction}, which {@link #deliverable} wraps — because the
         * energy cap would starve even a creative cell. A creative cell (or a Sophisticated Storage
         * infinity upgrade seen through a storage bus) hands back the whole request; a real network at
         * most what it holds, and {@code PROBE} is far over any real stock. Asked only for a listing at
         * or above {@link Integer#MAX_VALUE}, which those sources report exactly — and which no longer
         * alone means unbounded, because a real network CAN hold that many (#47): without the probe the
         * plan would treat it as infinite, consume the real stock and cancel "went missing".
         */
        private boolean unboundedIfProbed(Item item) {
            IAEItemStack request = request(item, PROBE);
            if (request == null) {
                return false;
            }
            IAEItemStack got = monitor.extractItems(request, Actionable.SIMULATE, source);
            return got != null && got.getStackSize() >= PROBE;
        }

        /**
         * The protected entries of the storage list (issue #49): shown by the view filter,
         * {@code isProtected} on the definition, and not plain — a protected copy is its own AE2 key,
         * never the canonical one (an item whose fresh stack itself is enchanted would be plain here,
         * and its copies stay the plain pool's problem, which refuses them).
         */
        private boolean protectedEntry(IAEItemStack stack) {
            return stack != null && stack.getStackSize() > 0 && shown(filter, stack)
                    && CraftExecutor.isProtected(stack.getDefinition()) && !plain(channel, stack);
        }

        /** {@link #deliverable} over an exact stored identity, never the canonical key (issue #49). */
        private long deliverableProtected(IAEItemStack stored, long limit) {
            if (!shown(filter, stored)) {
                return 0;
            }
            IAEItemStack request = stored.copy().setStackSize(limit);
            IAEItemStack got = Platform.poweredExtraction(energy, monitor, request, source, Actionable.SIMULATE);
            return got == null ? 0 : Math.max(0, Math.min(got.getStackSize(), limit));
        }

        /** {@link #count} for one protected identity: the same rules, probed on its own key (issue #49). */
        private long protectedCount(IAEItemStack stack) {
            long deliverable = deliverableProtected(stack, stack.getStackSize());
            if (deliverable > 0 && stack.getStackSize() >= Integer.MAX_VALUE) {
                IAEItemStack request = stack.copy().setStackSize(PROBE);
                IAEItemStack got = monitor.extractItems(request, Actionable.SIMULATE, source);
                if (got != null && got.getStackSize() >= PROBE) {
                    return Long.MAX_VALUE;
                }
            }
            return deliverable;
        }

        @Override
        public Map<Item, Long> protectedCounts() {
            Map<Item, Long> counts = new HashMap<>();
            if (!usable(menu)) {
                return counts;
            }
            for (IAEItemStack stack : monitor.getStorageList()) {
                if (protectedEntry(stack)) {
                    long count = protectedCount(stack);
                    if (count > 0) {
                        counts.merge(stack.getItem(), count, Ae2Station::saturatingAdd);
                    }
                }
            }
            return counts;
        }

        @Override
        public Map<Item, ItemStack> protectedSamples() {
            Map<Item, ItemStack> samples = new HashMap<>();
            if (!usable(menu)) {
                return samples;
            }
            for (IAEItemStack stack : monitor.getStorageList()) {
                if (protectedEntry(stack) && !samples.containsKey(stack.getItem())) {
                    ItemStack definition = stack.getDefinition();
                    if (definition != null) {
                        ItemStack sample = definition.copy();
                        sample.setCount(1);
                        samples.put(stack.getItem(), sample);
                    }
                }
            }
            return samples;
        }

        @Override
        public ItemStack extractProtected(Item item, java.util.function.Predicate<ItemStack> accepts) {
            if (item == null || !usable(menu)) {
                return ItemStack.EMPTY;
            }
            // Pick the entry first, draw after the iteration: a MODULATE extraction fires storage
            // events, and whether the monitor's list tolerates that mid-iteration is its business.
            IAEItemStack match = null;
            for (IAEItemStack stack : monitor.getStorageList()) {
                if (!protectedEntry(stack) || stack.getItem() != item) {
                    continue;
                }
                ItemStack definition = stack.getDefinition();
                if (definition == null || !accepts.test(definition)) {
                    continue; // not an identity this cell's ingredient would take
                }
                match = stack;
                break;
            }
            if (match == null) {
                return ItemStack.EMPTY;
            }
            // Identity-exact (issue #49): the request is this stored entry's own copy at size 1,
            // never the canonical key — that names the plain identity and would draw a plain stack.
            IAEItemStack got = Platform.poweredExtraction(energy, monitor, match.copy().setStackSize(1),
                    source, Actionable.MODULATE);
            if (got == null || got.getStackSize() <= 0) {
                return ItemStack.EMPTY; // a powered but starved network may not deliver even one
            }
            ItemStack out = got.getDefinition() != null ? got.getDefinition().copy()
                    : match.getDefinition().copy();
            out.setCount(1);
            return out;
        }

        @Override
        public long available(Item item, long limit) {
            return limit <= 0 || !usable(menu) ? 0 : deliverable(item, limit);
        }

        @Override
        public int extract(Item item, int count) {
            if (count <= 0) {
                return 0;
            }
            IAEItemStack request = request(item, count);
            if (request == null) {
                return 0;
            }
            IAEItemStack got = Platform.poweredExtraction(energy, monitor, request, source, Actionable.MODULATE);
            return got == null ? 0 : (int) Math.max(0, Math.min(got.getStackSize(), count));
        }

        @Override
        public ItemStack insert(ItemStack stack) {
            IAEItemStack offer = stack.isEmpty() ? null : channel.createStack(stack);
            // Only plain stacks go in: counts()/extract() only ever see plain keys, so anything else stored
            // here (worn, enchanted, renamed...) could never be drawn again. Those fall through to the
            // player's inventory, then the feet.
            if (offer == null || !plain(channel, offer) || !shown(filter, offer)) {
                return stack;
            }
            IAEItemStack leftover = Platform.poweredInsert(energy, monitor, offer, source, Actionable.MODULATE);
            if (leftover == null || leftover.getStackSize() <= 0) {
                return ItemStack.EMPTY;
            }
            if (leftover.getStackSize() >= stack.getCount()) {
                return stack;
            }
            ItemStack rest = stack.copy();
            rest.setCount((int) leftover.getStackSize());
            return rest;
        }

        @Override
        public long acceptable(ItemStack stack, long limit) {
            // insert()'s own checks and power-capped move, simulated for limit items.
            IAEItemStack offer = stack.isEmpty() || limit <= 0 ? null : channel.createStack(stack);
            if (offer == null || !plain(channel, offer) || !shown(filter, offer)) {
                return 0;
            }
            // Probe twice the request (see below): limit is int-bounded by every caller, so 2 * limit fits.
            offer.setStackSize(2 * limit);
            IAEItemStack leftover = Platform.poweredInsert(energy, monitor, offer, source, Actionable.SIMULATE);
            long rest = leftover == null ? 0 : Math.max(0, leftover.getStackSize());
            long simulated = Math.max(0, 2 * limit - rest);
            // AE2 inserts in two passes over each priority group: first the storages that already hold
            // (or are partitioned for) the item, then every storage, those again included. A SIMULATE
            // insert stores nothing, so the second pass counts those storages' free space a second time:
            // the answer can be up to twice the real room (seen in play: a batch sized on it dropped
            // ten million items once the drives were nearly full). Real room R and the simulated S obey
            // S <= 2R, so R >= ceil(S / 2): plan on that, never on S. Halving a probe of the request itself
            // would cap every answer at half of it, even on a network with ample room; probing twice the
            // request keeps that case exact (S = 2 * limit means R >= limit) and only underestimates when
            // the network is nearly full (room under twice the request)
            // or lacks the energy to move twice it; an underestimate only shrinks a batch or stops a step.
            return Math.min(limit, (simulated + 1) / 2);
        }
    }
}
