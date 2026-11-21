package com.legoj15.sprawlcrafting.forge.compat.ae2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.legoj15.sprawlcrafting.forge.craft.CraftExecutor;
import com.legoj15.sprawlcrafting.forge.craft.ItemKey;
import com.legoj15.sprawlcrafting.forge.craft.NetworkTerminals;

import appeng.api.config.Actionable;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.util.Platform;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Server-side, live view of the network behind an open AE2 Crafting Terminal. Reads the monitor's
 * cached storage list; moves items only through AE2's own powered helpers
 * ({@code Platform.poweredExtraction} / {@code poweredInsert}, identical in rv6 and UEL), so every
 * transfer is charged to the network's power and checked against the player's security permissions
 * exactly as a terminal click would be.
 *
 * <p>Planner counts read the stored {@link IAEItemStack#getStackSize()} (a long) through
 * {@link #counts}: a real network holding more than an int of one item counts as that finite long
 * (issue #47), and only a source a probe proves hands out any amount of it counts unbounded —
 * {@code cell.extractItems} under the powered helper, which simulates with no energy cap, is what
 * tells an AE2 creative cell from a merely huge network.
 */
final class Ae2NetworkView implements NetworkTerminals.View {

    /**
     * The probe amount for the unbounded check: a quarter of the long range, far above any real
     * stock — even a network holding billions of one item (issue #47) — so only a source that hands
     * out any amount can supply the whole request.
     */
    private static final long PROBE = Long.MAX_VALUE / 4;

    private final IItemStorageChannel channel;
    private final IMEInventoryHandler<IAEItemStack> cell;
    private final IEnergySource energy;
    private final IActionSource source;

    Ae2NetworkView(IItemStorageChannel channel, IMEInventoryHandler<IAEItemStack> cell,
                   IEnergySource energy, IActionSource source) {
        this.channel = channel;
        this.cell = cell;
        this.energy = energy;
        this.source = source;
    }

    @Override
    public List<ItemStack> stacks(Set<Item> items) {
        return copies(contents(), items);
    }

    /** The monitor's cached list when the cell is a monitor (the terminal's always is), else a fresh scan. */
    private IItemList<IAEItemStack> contents() {
        if (cell instanceof IMEMonitor) {
            return ((IMEMonitor<IAEItemStack>) cell).getStorageList();
        }
        return cell.getAvailableItems(channel.createList());
    }

    /** Shared with the client mirror: count-positive entries, optionally of {@code items} only, as int-clamped copies. */
    static List<ItemStack> copies(Iterable<IAEItemStack> list, Set<Item> items) {
        List<ItemStack> stacks = new ArrayList<ItemStack>();
        if (list == null) {
            return stacks;
        }
        for (IAEItemStack stored : list) {
            if (stored == null || stored.getStackSize() <= 0) {
                continue; // craftable-only entries carry a zero count
            }
            if (items != null && !items.contains(stored.getItem())) {
                continue;
            }
            ItemStack copy = stored.createItemStack(); // count clamped to Integer.MAX_VALUE by AE2
            if (!copy.isEmpty()) {
                stacks.add(copy);
            }
        }
        return stacks;
    }

    /**
     * The planner's counts from the live storage list: usable identities only, each counted as the
     * energy-capped delivery a plan may really draw (a finite long — no int clamp, a real network
     * can hold billions of one item, #47), or unbounded ({@link Long#MAX_VALUE}) when a probe proves
     * the network behind the identity hands out any amount of it.
     */
    @Override
    public Map<ItemKey, Long> counts(Set<Item> items) {
        Map<ItemKey, Long> counts = new HashMap<ItemKey, Long>();
        IItemList<IAEItemStack> list = contents();
        if (list == null) {
            return counts;
        }
        for (IAEItemStack stored : list) {
            if (stored == null || stored.getStackSize() <= 0) {
                continue; // craftable-only entries carry a zero count
            }
            if (items != null && !items.contains(stored.getItem())) {
                continue;
            }
            ItemStack stack = stored.createItemStack();
            if (stack.isEmpty() || !CraftExecutor.usableAsIngredient(stack)) {
                continue;
            }
            long count = count(stored);
            if (count > 0) {
                ItemKey key = ItemKey.of(stack);
                Long current = counts.get(key);
                counts.put(key, NetworkTerminals.saturatingAdd(current == null ? 0L : current.longValue(), count));
            }
        }
        return counts;
    }

    /**
     * The protected tier's counts (issue #49), from the same live storage list and with the same
     * probed long values as {@link #counts}: protected identities only ({@code CraftExecutor
     * .isProtected} — undamaged, enchanted and/or renamed), each counted as the energy-capped
     * delivery a protected plan may really draw, or unbounded when the probe proves the source
     * behind it hands out any amount. {@link #protectedSamples} keeps the default: the listing
     * this class already serves carries every stored identity.
     */
    @Override
    public Map<ItemKey, Long> protectedCounts(Set<Item> items) {
        Map<ItemKey, Long> counts = new HashMap<ItemKey, Long>();
        IItemList<IAEItemStack> list = contents();
        if (list == null) {
            return counts;
        }
        for (IAEItemStack stored : list) {
            if (stored == null || stored.getStackSize() <= 0) {
                continue; // craftable-only entries carry a zero count
            }
            if (items != null && !items.contains(stored.getItem())) {
                continue;
            }
            ItemStack stack = stored.createItemStack();
            if (stack.isEmpty() || !CraftExecutor.isProtected(stack)) {
                continue;
            }
            long count = count(stored);
            if (count > 0) {
                ItemKey key = ItemKey.of(stack);
                Long current = counts.get(key);
                counts.put(key, NetworkTerminals.saturatingAdd(current == null ? 0L : current.longValue(), count));
            }
        }
        return counts;
    }

    /**
     * The count one stored stack plans as: the energy-capped simulated delivery as a finite long
     * (no int clamp — a real network can hold billions of one item, #47), or unbounded
     * ({@link Long#MAX_VALUE}) when a probe proves the source behind the stack hands out any
     * amount of it.
     */
    private long count(IAEItemStack stored) {
        long deliverable = deliverable(stored);
        if (deliverable > 0 && stored.getStackSize() >= Integer.MAX_VALUE && unboundedIfProbed(stored)) {
            return Long.MAX_VALUE;
        }
        return deliverable;
    }

    /**
     * What extracting the stored stack's whole size would really deliver right now: the same
     * energy-capped call {@link #extract} makes, simulated, as a long (the int view stays for the
     * executor's per-craft draws). The single definition of "available" that {@link #counts} uses,
     * so a plan never counts what the extraction would refuse.
     */
    private long deliverable(IAEItemStack stored) {
        IAEItemStack request = stored.copy();
        request.setStackSize(stored.getStackSize());
        IAEItemStack got = Platform.poweredExtraction(energy, cell, request, source, Actionable.SIMULATE);
        return got == null ? 0 : Math.max(0, Math.min(got.getStackSize(), stored.getStackSize()));
    }

    /**
     * Whether the network hands out any amount of the stored identity: an UNPOWERED simulated
     * extraction of {@link #PROBE} returns at least {@code PROBE}. The call is
     * {@code cell.extractItems(request, Actionable.SIMULATE, source)} — the {@code IMEInventory} API
     * under {@code Platform.poweredExtraction}, which {@link #deliverable} wraps — because the energy
     * cap would starve even a creative cell. An AE2 creative cell (or a Sophisticated Storage
     * infinity upgrade seen through a storage bus) hands back the whole request; a real network at
     * most what it holds, and {@code PROBE} is far over any real stock. Asked only for a listing at
     * or above {@link Integer#MAX_VALUE}, which those sources report exactly — and which no longer
     * alone means unbounded, because a real network CAN hold that many (#47): without the probe the
     * plan would treat it as infinite, consume the real stock and cancel "went missing".
     */
    private boolean unboundedIfProbed(IAEItemStack stored) {
        IAEItemStack request = stored.copy();
        request.setStackSize(PROBE);
        IAEItemStack got = cell.extractItems(request, Actionable.SIMULATE, source);
        return got != null && got.getStackSize() >= PROBE;
    }

    @Override
    public int extract(ItemStack like, int count, boolean simulate) {
        if (count <= 0 || like.isEmpty()) {
            return 0;
        }
        IAEItemStack request = channel.createStack(like);
        if (request == null) {
            return 0;
        }
        request.setStackSize(count);
        IAEItemStack got = Platform.poweredExtraction(energy, cell, request, source,
                simulate ? Actionable.SIMULATE : Actionable.MODULATE);
        if (got == null) {
            return 0;
        }
        return (int) Math.max(0L, Math.min((long) count, got.getStackSize()));
    }

    @Override
    public ItemStack insert(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        IAEItemStack input = channel.createStack(stack);
        if (input == null) {
            return stack;
        }
        IAEItemStack leftover = Platform.poweredInsert(energy, cell, input, source);
        if (leftover == null || leftover.getStackSize() <= 0) {
            return ItemStack.EMPTY;
        }
        return leftover.createItemStack();
    }

    @Override
    public boolean accepts(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        IAEItemStack input = channel.createStack(stack);
        if (input == null) {
            return false;
        }
        // A SIMULATE insert can report up to twice the real room (AE2 offers the item to the storages
        // that already hold it, then to every storage again, and nothing is stored in between), so
        // the probe asks for double and requires all of it.
        input.setStackSize(2L * stack.getCount());
        IAEItemStack leftover = Platform.poweredInsert(energy, cell, input, source, Actionable.SIMULATE);
        return leftover == null || leftover.getStackSize() <= 0;
    }
}
