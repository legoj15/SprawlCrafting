package com.legoj15.sprawlcrafting.forge.compat.ae2;

import com.legoj15.sprawlcrafting.forge.craft.NetworkTerminals;

import appeng.api.AEApi;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.container.implementations.ContainerCraftingTerm;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;

/**
 * {@link NetworkTerminals.Provider} for the AE2 Crafting Terminal ({@link ContainerCraftingTerm} and
 * any subclass). Decided by container class only: a plain ME terminal ({@code ContainerMEMonitorable})
 * has no crafting grid, and AE2-UEL's wireless crafting terminal is a different container family
 * ({@code ContainerWirelessCraftingTerminal extends ContainerMEPortableTerminal}), so neither counts.
 *
 * <p>Server: the network is the container's own cell inventory — {@code AEBaseContainer
 * .getCellInventory()}, which {@code ContainerMEMonitorable}'s constructor sets to the terminal's
 * {@code IMEMonitor} — with the container's power source and action source (the player, so AE2's
 * security permissions apply). All three getters are public in both rv6 and UEL, so the private
 * {@code ContainerMEMonitorable.monitor} field never has to be touched. Client: the terminal screen's
 * own item mirror ({@link Ae2ClientRepo}). An unpowered terminal ({@code isPowered()}, synced to the
 * client by AE2's GuiSync) counts as no network on both sides, matching its empty screen.
 */
final class Ae2TerminalProvider implements NetworkTerminals.Provider {

    /**
     * Resolved on first use, never at construction: {@code AEApi.instance()} bootstraps AE2's core
     * registries, which read AE2's config — touching it from our preInit (which can run before AE2's)
     * crashes the game with an NPE in {@code AELog} (reproduced in a dev client with rv6).
     */
    private IItemStorageChannel channel;

    private IItemStorageChannel channel() {
        if (channel == null) {
            channel = AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
        }
        return channel;
    }

    @Override
    public boolean isTerminal(Container container) {
        return container instanceof ContainerCraftingTerm;
    }

    /**
     * The player's open terminal when its network is reachable right now, else null: powered (both
     * sides; synced to the client by GuiSync) and, on the server, a valid container with a cell
     * inventory, power source and action source. Shared by {@link #usable} and {@link #view} so the
     * two can never disagree.
     */
    private static ContainerCraftingTerm reachable(EntityPlayer player) {
        if (!(player.openContainer instanceof ContainerCraftingTerm)) {
            return null;
        }
        ContainerCraftingTerm terminal = (ContainerCraftingTerm) player.openContainer;
        if (!terminal.isPowered()) {
            return null;
        }
        if (!player.world.isRemote
                && (!terminal.isValidContainer() || terminal.getCellInventory() == null
                        || terminal.getPowerSource() == null || terminal.getActionSource() == null)) {
            return null;
        }
        return terminal;
    }

    @Override
    public boolean usable(EntityPlayer player) {
        return reachable(player) != null;
    }

    @Override
    public NetworkTerminals.View view(EntityPlayer player) {
        ContainerCraftingTerm terminal = reachable(player);
        if (terminal == null) {
            return null;
        }
        if (player.world.isRemote) {
            return Ae2ClientRepo.view(terminal);
        }
        IMEInventoryHandler<IAEItemStack> cell = terminal.getCellInventory();
        IEnergySource energy = terminal.getPowerSource();
        IActionSource source = terminal.getActionSource();
        return new Ae2NetworkView(channel(), cell, energy, source);
    }

    @Override
    public int clientSignature(EntityPlayer player) {
        if (!(player.openContainer instanceof ContainerCraftingTerm) || !player.world.isRemote) {
            return 0;
        }
        ContainerCraftingTerm terminal = (ContainerCraftingTerm) player.openContainer;
        return terminal.isPowered() ? Ae2ClientRepo.signature(terminal, player.world.getTotalWorldTime()) : 1;
    }
}
