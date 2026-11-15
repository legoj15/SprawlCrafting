package com.legoj15.sprawlcrafting.forge.compat.ae2;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;

import com.legoj15.sprawlcrafting.forge.craft.NetworkTerminals;

import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.client.gui.implementations.GuiMEMonitorable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Client-only: the network contents the open Crafting Terminal screen displays. On the client a
 * {@code ContainerMEMonitorable} holds no monitor (AE2 nulls it off-server); the screen keeps the
 * synced list in {@code GuiMEMonitorable.repo} ({@code appeng.client.me.ItemRepo}), whose private
 * {@code list} receives every {@code PacketMEInventoryUpdate} — the full network, not just the
 * search-filtered view. Both fields are read by reflection: {@code repo} is {@code private} in rv6 but
 * {@code protected} in UEL, and {@code ItemRepo.list} is private in both (UEL's public
 * {@code getList()} does not exist in rv6). Field names are AE2's own and never obfuscated. Any
 * reflection failure is logged once and then reads as "no network on the client": classification
 * falls back to the player's inventory while the server still counts the network authoritatively.
 *
 * <p>Only {@link Ae2TerminalProvider} calls this, and only when {@code world.isRemote}, and
 * {@link Ae2Compat} registers its {@link ScreenTracker} only on the client side, so a dedicated server
 * never loads it (or the client GUI classes it names).
 */
final class Ae2ClientRepo {

    private Ae2ClientRepo() {
    }

    /** How often (client ticks) the signature is recomputed: a busy network changes counts constantly. */
    private static final long SIGNATURE_INTERVAL_TICKS = 10L;

    private static Field repoField;
    private static Field listField;
    private static boolean broken;

    /** Weak: the list belongs to the terminal screen, and a closed terminal's whole item list must not stay reachable from here. */
    private static WeakReference<Object> signedList = new WeakReference<Object>(null);
    private static long signedAt = Long.MIN_VALUE;
    private static int signature;

    /** A read-only view of the terminal screen's network list, or null when unavailable. */
    static NetworkTerminals.View view(Container terminal) {
        IItemList<IAEItemStack> list = list(terminal);
        return list == null ? null : new ClientView(list);
    }

    /**
     * A hash of the displayed network (identity + count per entry), recomputed at most every
     * {@link #SIGNATURE_INTERVAL_TICKS} unless the list object itself changes (a different terminal).
     */
    static int signature(Container terminal, long worldTime) {
        IItemList<IAEItemStack> list = list(terminal);
        if (list == null) {
            return 2;
        }
        if (list == signedList.get() && worldTime >= signedAt && worldTime - signedAt < SIGNATURE_INTERVAL_TICKS) {
            return signature;
        }
        int hash = 3;
        for (IAEItemStack stored : list) {
            if (stored == null || stored.getStackSize() <= 0) {
                continue;
            }
            long count = stored.getStackSize();
            int entry = (Item.getIdFromItem(stored.getItem()) * 31 + stored.getItemDamage()) * 31
                    + (int) (count ^ (count >>> 32));
            hash += entry * 31 + (stored.hasTagCompound() ? stored.getDefinition().getTagCompound().hashCode() : 0);
        }
        signedList = new WeakReference<Object>(list);
        signedAt = worldTime;
        signature = hash;
        return hash;
    }

    /**
     * The terminal screen last seen, kept (weakly) for while another screen is in front of it. The
     * JEI "+" lives on JEI's recipe screen, which REPLACES {@code currentScreen} while the terminal's
     * container stays open underneath; without this the network would be invisible exactly when the
     * button is evaluated. AE2 delivers inventory updates only to the current screen, so the
     * remembered list is the network as of when the player left the terminal screen — the server
     * re-plans and re-checks authoritatively anyway.
     */
    private static WeakReference<GuiScreen> lastTerminalScreen = new WeakReference<GuiScreen>(null);

    /** Remembers the terminal screen as JEI (or anything) is opened over it. Client event bus only. */
    static final class ScreenTracker {
        @SubscribeEvent
        public void onGuiOpen(GuiOpenEvent event) {
            remember(Minecraft.getMinecraft().currentScreen);
            remember(event.getGui());
        }

        private static void remember(GuiScreen screen) {
            if (screen instanceof GuiMEMonitorable) {
                lastTerminalScreen = new WeakReference<GuiScreen>(screen);
            }
        }
    }

    /** The terminal screen showing {@code terminal}: the current one, else the one remembered under it. */
    private static GuiScreen terminalScreen(Container terminal) {
        GuiScreen current = Minecraft.getMinecraft().currentScreen;
        if (current instanceof GuiMEMonitorable && ((GuiContainer) current).inventorySlots == terminal) {
            return current;
        }
        GuiScreen remembered = lastTerminalScreen.get();
        if (remembered != null && ((GuiContainer) remembered).inventorySlots == terminal) {
            return remembered;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static IItemList<IAEItemStack> list(Container terminal) {
        if (broken) {
            return null;
        }
        GuiScreen screen = terminalScreen(terminal);
        if (screen == null) {
            return null;
        }
        try {
            if (repoField == null) {
                Field repo = GuiMEMonitorable.class.getDeclaredField("repo");
                repo.setAccessible(true);
                Field list = repo.getType().getDeclaredField("list");
                list.setAccessible(true);
                repoField = repo;
                listField = list;
            }
            Object repo = repoField.get(screen);
            return repo == null ? null : (IItemList<IAEItemStack>) listField.get(repo);
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            LogManager.getLogger("SprawlCrafting").warn(
                    "Cannot read the AE2 terminal's item list; the client will not count the ME network "
                            + "for recipe highlights (crafting itself is unaffected).", e);
            return null;
        }
    }

    /** The screen's mirror is display data: it can be counted, never moved. */
    private static final class ClientView implements NetworkTerminals.View {
        private final IItemList<IAEItemStack> list;

        ClientView(IItemList<IAEItemStack> list) {
            this.list = list;
        }

        @Override
        public List<ItemStack> stacks(Set<Item> items) {
            return Ae2NetworkView.copies(list, items);
        }

        @Override
        public int extract(ItemStack like, int count, boolean simulate) {
            return 0;
        }

        @Override
        public ItemStack insert(ItemStack stack) {
            return stack;
        }
    }
}
