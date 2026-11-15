package com.legoj15.sprawlcrafting.compat.ae2;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.legoj15.sprawlcrafting.craft.CraftExecutor;
import com.legoj15.sprawlcrafting.craft.StationStorage;
import com.legoj15.sprawlcrafting.craft.StationStorages;

import appeng.api.config.Actionable;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.menu.me.common.IClientRepo;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.me.items.CraftingTermMenu;
import appeng.menu.me.items.WirelessCraftingTermMenu;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
 * the decompiled AE2 19.2.17 / 19.2.19 jar:
 * <ul>
 *   <li>{@code CraftingTermMenu extends MEStorageMenu}; {@code getEnergySource()} is public on it.</li>
 *   <li>{@code MEStorageMenu.storage} is {@code protected final MEStorage}, assigned once from
 *       {@code host.getInventory()} (which must not be called again), so it is read reflectively.</li>
 *   <li>{@code AEBaseMenu.getActionSource()} is the menu's own {@code PlayerSource(player, actionHost)},
 *       the source AE2 itself uses for terminal extractions ({@code moveOneStackToPlayer}).</li>
 *   <li>{@code MEStorageMenu.getLinkStatus().connected()} is what AE2's own {@code canInteractWithGrid}
 *       and client {@code hasIngredient} check; an unlinked terminal is not a usable station.</li>
 *   <li>Client side, {@code MEStorageMenu.getClientRepo()} is the screen's repo;
 *       {@code IClientRepo.getAllEntries()} gives {@code GridInventoryEntry}s with the key and stored
 *       amount the server synced (already filtered by the menu's view).</li>
 * </ul>
 * Only plain {@link AEItemKey}s (no component patch) count, matching {@link StationStorage}'s contract.
 */
public final class Ae2Station implements StationStorages.Provider {

    private static final Field STORAGE_FIELD = storageField();

    private Ae2Station() {
    }

    /** Entry point, invoked reflectively by {@link StationStorages#bootstrap()}. */
    public static void init() {
        StationStorages.register(new Ae2Station());
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

    @Override
    public boolean isStationMenu(AbstractContainerMenu menu) {
        return menu instanceof CraftingTermMenu;
    }

    @Override
    public StationStorage open(ServerPlayer player) {
        if (!(player.containerMenu instanceof CraftingTermMenu menu) || !menu.getLinkStatus().connected()) {
            return null;
        }
        MEStorage storage;
        try {
            storage = (MEStorage) STORAGE_FIELD.get(menu);
        } catch (IllegalAccessException e) {
            return null;
        }
        return storage == null ? null : new NetworkStorage(menu, storage, menu.getEnergySource(), menu.getActionSource());
    }

    @Override
    public Map<Item, Long> clientCounts(AbstractContainerMenu menu) {
        if (!(menu instanceof CraftingTermMenu terminal) || !terminal.getLinkStatus().connected()) {
            return null;
        }
        IClientRepo repo = terminal.getClientRepo();
        if (repo == null) {
            return null;
        }
        Map<Item, Long> counts = new HashMap<>();
        for (GridInventoryEntry entry : repo.getAllEntries()) {
            if (entry.getWhat() instanceof AEItemKey key && !key.hasComponents() && entry.getStoredAmount() > 0) {
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

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    /** The ME network behind one open terminal, for the duration of one plan or craft. */
    private record NetworkStorage(CraftingTermMenu menu, MEStorage storage, IEnergySource energy,
                                  IActionSource source) implements StationStorage {

        /** A plain item the terminal is allowed to show (a portable cell's key-type filter), or null. */
        private AEItemKey key(Item item) {
            AEItemKey key = AEItemKey.of(item);
            return key != null && menu.isKeyVisible(key) ? key : null;
        }

        @Override
        public Map<Item, Long> counts() {
            Map<Item, Long> counts = new HashMap<>();
            for (Object2LongMap.Entry<AEKey> entry : storage.getAvailableStacks()) {
                if (entry.getKey() instanceof AEItemKey key && !key.hasComponents() && entry.getLongValue() > 0
                        && menu.isKeyVisible(key) && CraftExecutor.usableAsIngredient(key.toStack())) {
                    counts.merge(key.getItem(), entry.getLongValue(), Ae2Station::saturatingAdd);
                }
            }
            return counts;
        }

        @Override
        public long available(Item item, long limit) {
            AEItemKey key = key(item);
            return key == null || limit <= 0 ? 0
                    : StorageHelper.poweredExtraction(energy, storage, key, limit, source, Actionable.SIMULATE);
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
            if (key == null || !menu.isKeyVisible(key)) {
                return stack;
            }
            long inserted = StorageHelper.poweredInsert(energy, storage, key, stack.getCount(), source,
                    Actionable.MODULATE);
            if (inserted <= 0) {
                return stack;
            }
            return inserted >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - (int) inserted);
        }
    }
}
