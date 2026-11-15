package com.legoj15.sprawlcrafting.forge.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
 */
final class Ae2NetworkView implements NetworkTerminals.View {

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
}
