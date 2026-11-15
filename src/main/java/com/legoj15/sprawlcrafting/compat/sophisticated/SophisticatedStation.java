package com.legoj15.sprawlcrafting.compat.sophisticated;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.legoj15.sprawlcrafting.craft.CraftExecutor;
import com.legoj15.sprawlcrafting.craft.CraftingGrids;
import com.legoj15.sprawlcrafting.craft.StationStorage;
import com.legoj15.sprawlcrafting.craft.StationStorages;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
//? if >=1.21.11 {
/*import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;*/
//?}
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;

/**
 * Sophisticated Storage / Backpacks' own screen as a crafting station (issue #33). The rule is the one
 * issue #2 set: every source item visible in the open screen counts as craft material. A Sophisticated
 * storage or backpack screen shows its storage, so a screen that also has the crafting upgrade — a 3×3
 * grid, resolved by {@code SophisticatedGrids} — is a station while it is open: 64 acacia planks in the
 * chest then count, instead of the planner reporting an oak-log shortfall it cannot see.
 *
 * <p>{@link StationStorages.Provider#isStationMenu} is deliberately false (and {@link #menus()} empty):
 * that flag marks stations whose screen needs its own recipe-viewer station transfer handlers (the AE2
 * terminal). The final-grid hand-off no longer looks at it: any station whose grid is a
 * {@link CraftingGrids} grid takes the hand-off, drawing from the station for what the inventory lacks. The crafting upgrade IS a CraftingGrids grid with hand-off support and already has
 * its own JEI handler ({@code SophisticatedCraftingTransferHandler}; REI and EMI are not wrapped), so
 * neither use applies here.
 *
 * <p>"Usable" ({@link StationStorages.Provider}'s contract, both sides) means the menu is a
 * {@code StorageContainerMenuBase} that currently resolves to a full crafting grid
 * ({@link CraftingGrids#isFullGrid} over {@link CraftingGrids#find}): the menu has a crafting upgrade.
 * A storage screen without one shows no grid, so its storage must not count —
 * {@link StationStorages#hasUsableStation} would make {@code GridContext.current} claim a 3×3 the
 * player cannot lay out. Only plain stacks ({@link #isPlain}) that pass
 * {@code CraftExecutor.usableAsIngredient} count, are drawn or are inserted, matching
 * {@link StationStorage}'s contract.
 *
 * <p>Reached only through {@link StationStorages#bootstrap()}, by reflection, after the loader reports
 * "sophisticatedcore" loaded; this package is compiled only on nodes that pin the Sophisticated
 * versions (the NeoForge nodes and 1.20.1-forge; Fabric never). Server side reads the menu's
 * {@code InventoryHandler}, whose API is the IItemHandler style on 1.20.1 / 1.21.1 and the NeoForge
 * transfer API on 26.x — hence the stonecutter branches below.
 */
public final class SophisticatedStation implements StationStorages.Provider {

    private SophisticatedStation() {
    }

    /** Entry point, invoked reflectively by {@link StationStorages#bootstrap()}. */
    public static void init() {
        StationStorages.register(new SophisticatedStation());
    }

    /** Always false: the crafting upgrade's grid is a CraftingGrids grid (see the class javadoc). */
    @Override
    public boolean isStationMenu(AbstractContainerMenu menu) {
        return false;
    }

    /**
     * Server side: the storage of the screen {@code player} has open, or null when that screen has no
     * crafting upgrade. The handler is read fresh here ({@code getStorageWrapper().getInventoryHandler()}),
     * never cached across menus.
     */
    @Override
    public StationStorage open(ServerPlayer player) {
        if (!(player.containerMenu instanceof StorageContainerMenuBase<?> storage) || !usable(storage)) {
            return null;
        }
        return new MenuStorage(storage, storage.getStorageWrapper().getInventoryHandler());
    }

    /**
     * Client side: plain-item counts of the storage the menu's own slots mirror, or null when the menu
     * is not a usable station. The storage slots are the FIRST {@code getNumberOfStorageInventorySlots()}
     * entries of {@code menu.slots} ({@code initSlotsAndContainers} adds storage slots, then player
     * slots, then extras), and one slot can hold more than 64 (stack upgrades), so each is summed, not
     * capped. Version-independent otherwise: plain {@code Slot.getItem()} stacks.
     */
    @Override
    public Map<Item, Long> clientCounts(AbstractContainerMenu menu) {
        if (!(menu instanceof StorageContainerMenuBase<?> storage) || !usable(storage)) {
            return null;
        }
        Map<Item, Long> counts = new HashMap<>();
        for (int i = 0; i < storage.getNumberOfStorageInventorySlots(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && !storage.isInaccessibleSlot(i) && isPlain(stack) && CraftExecutor.usableAsIngredient(stack)) {
                counts.merge(stack.getItem(), (long) stack.getCount(), SophisticatedStation::saturatingAdd);
            }
        }
        return counts;
    }

    /** Always empty: these menus' recipe-viewer handlers are the mods' own, already fronted (JEI). */
    @Override
    public List<StationStorages.StationMenu> menus() {
        return List.of();
    }

    /**
     * The "usable" gate, asked live on every call like {@code Ae2Station.usable}: {@code menu}
     * currently resolves to a full crafting grid, i.e. a crafting upgrade is there (the open tab's, or
     * the first one — the same pick {@code SophisticatedGrids} makes).
     */
    private static boolean usable(StorageContainerMenuBase<?> menu) {
        return CraftingGrids.isFullGrid(CraftingGrids.find(menu));
    }

    /**
     * Whether {@code stack} is nothing but its item: no component patch; on 1.20.1, exactly the tag of a
     * fresh stack, because a new damageable item already carries {@code Damage:0}
     * ({@code ItemStack(ItemLike, int)} calls {@code setDamageValue}), so a "no tag" test would skip every
     * pristine tool (the same trap {@code Ae2Station.canonical} handles).
     */
    private static boolean isPlain(ItemStack stack) {
        //? if >=1.20.5 {
        return stack.getComponentsPatch().isEmpty();
        //?} else {
        /*return ItemStack.isSameItemSameTags(stack, new ItemStack(stack.getItem()));*/
        //?}
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    /** The storage behind one open Sophisticated screen, for the duration of one plan or craft. */
    private record MenuStorage(StorageContainerMenuBase<?> menu, InventoryHandler handler)
            implements StationStorage {

        @Override
        public Map<Item, Long> counts() {
            Map<Item, Long> counts = new HashMap<>();
            if (!usable(menu)) {
                return counts;
            }
            //? if >=1.21.11 {
            /*for (int slot = 0; slot < handler.size(); slot++) {
                ItemResource resource = handler.getResource(slot);
                if (!resource.isEmpty() && handler.isSlotAccessible(slot) && resource.isComponentsPatchEmpty()
                        && handler.getAmountAsLong(slot) > 0
                        && CraftExecutor.usableAsIngredient(resource.toStack())) {
                    counts.merge(resource.getItem(), handler.getAmountAsLong(slot), SophisticatedStation::saturatingAdd);
                }
            }*/
            //?} else {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty() && handler.isSlotAccessible(slot) && isPlain(stack)
                        && CraftExecutor.usableAsIngredient(stack)) {
                    counts.merge(stack.getItem(), (long) stack.getCount(), SophisticatedStation::saturatingAdd);
                }
            }
            //?}
            return counts;
        }

        @Override
        public long available(Item item, long limit) {
            if (limit <= 0 || !usable(menu)) {
                return 0;
            }
            return drawSlots(item, (int) Math.min(limit, Integer.MAX_VALUE), true);
        }

        @Override
        public int extract(Item item, int count) {
            if (count <= 0) {
                return 0;
            }
            return Math.max(0, Math.min(drawSlots(item, count, false), count));
        }

        /**
         * Draws (or, {@code simulate}, sizes) up to {@code count} plain {@code item}, slot by slot. Not the
         * handler's whole-inventory extract: that is answered from its slot tracker, and Core's
         * {@code ISlotTracker.Noop} answers it with nothing at all, on every version. Blocked slots (a
         * slot Core holds back, such as for a mob catcher) neither count nor give.
         */
        private int drawSlots(Item item, int count, boolean simulate) {
            int got = 0;
            //? if >=1.21.11 {
            /*ItemResource resource = ItemResource.of(item);
            try (Transaction tx = Transaction.openRoot()) {
                for (int slot = 0; slot < handler.size() && got < count; slot++) {
                    if (resource.equals(handler.getResource(slot))) {
                        got += handler.extract(slot, resource, count - got, tx);
                    }
                }
                if (!simulate) {
                    tx.commit(); // a simulate closes uncommitted, which rolls the extraction back
                }
            }*/
            //?} else {
            // One extractItem call yields at most a vanilla stack, though a stack-upgraded slot holds more:
            // a real draw repeats until the slot is spent; a simulate (which would answer the same capped
            // amount every time) probes that the slot gives anything (blocked slots give nothing) and
            // takes the slot's count, as counts() does.
            for (int slot = 0; slot < handler.getSlots() && got < count; slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.is(item) || !isPlain(stack)) {
                    continue;
                }
                if (simulate) {
                    if (!handler.extractItem(slot, 1, true).isEmpty()) {
                        got += Math.min(count - got, stack.getCount());
                    }
                    continue;
                }
                while (got < count) {
                    int took = handler.extractItem(slot, count - got, false).getCount();
                    if (took <= 0) {
                        break;
                    }
                    got += took;
                }
            }
            //?}
            return got;
        }

        @Override
        public ItemStack insert(ItemStack stack) {
            // Only plain stacks go in: counts()/extract() only ever see plain stacks, so anything
            // carrying components stored here could never be drawn again. Those stay with the player.
            if (stack.isEmpty() || !isPlain(stack)) {
                return stack;
            }
            // The whole-handler insert goes through the slot tracker too (see drawSlots); whatever it leaves
            // is offered slot by slot, so a no-op tracker cannot send the give-back to the player's feet.
            //? if >=1.21.11 {
            /*ItemResource resource = ItemResource.of(stack.getItem());
            int left = stack.getCount();
            try (Transaction tx = Transaction.openRoot()) {
                left -= handler.insert(resource, left, tx);
                for (int slot = 0; slot < handler.size() && left > 0; slot++) {
                    left -= handler.insert(slot, resource, left, tx);
                }
                tx.commit();
            }
            return left <= 0 ? ItemStack.EMPTY : stack.copyWithCount(left);*/
            //?} else {
            ItemStack rest = handler.insertItem(stack.copy(), false);
            for (int slot = 0; slot < handler.getSlots() && !rest.isEmpty(); slot++) {
                rest = handler.insertItem(slot, rest, false);
            }
            return rest;
            //?}
        }
    }
}
