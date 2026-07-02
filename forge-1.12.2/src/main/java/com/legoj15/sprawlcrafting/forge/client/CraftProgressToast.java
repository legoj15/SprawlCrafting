package com.legoj15.sprawlcrafting.forge.client;

import com.legoj15.sprawlcrafting.forge.network.CraftProgressMessage;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.toasts.GuiToast;
import net.minecraft.client.gui.toasts.IToast;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;

/**
 * The deferred-craft progress display as a REAL vanilla toast — replacing the hand-drawn HUD card
 * with the native system (the modern versions use toasts too). One persistent toast per job: it
 * reads {@link ClientCraftState} live every frame, so progress, pauses, and the terminal states
 * repaint in place; after a terminal state lingers ({@link ClientCraftState#TERMINAL_LINGER_MS})
 * it clears the state and slides out. {@link ClientCraftState#update} adds it when a job starts
 * (vanilla's {@code getToast} keyed lookup keeps it a singleton).
 */
public final class CraftProgressToast implements IToast {

    private static final int WIDTH = 160;

    @Override
    public Visibility draw(GuiToast toastGui, long delta) {
        if (!ClientCraftState.active) {
            return Visibility.HIDE;
        }
        long terminalSince = ClientCraftState.terminalSinceMs;
        if (terminalSince != 0L
                && System.currentTimeMillis() - terminalSince > ClientCraftState.TERMINAL_LINGER_MS) {
            ClientCraftState.clear();
            return Visibility.HIDE;
        }

        CraftProgressMessage.State state = ClientCraftState.state;
        ItemStack target = ClientCraftState.target;
        ItemStack current = ClientCraftState.current;
        int done = ClientCraftState.done;
        int total = ClientCraftState.total;

        toastGui.getMinecraft().getTextureManager().bindTexture(TEXTURE_TOASTS);
        GlStateManager.color(1.0F, 1.0F, 1.0F);
        toastGui.drawTexturedModalRect(0, 0, 0, 32, WIDTH, 32);

        toastGui.getMinecraft().fontRenderer.drawString(statusLabel(state), 30, 7, statusColor(state));
        String name = target.isEmpty() ? "" : target.getDisplayName();
        String line = trim(toastGui, name, WIDTH - 30 - 34)
                + " " + I18n.format("sprawlcrafting.toast.step", done, total);
        toastGui.getMinecraft().fontRenderer.drawString(line, 30, 18, 0xFF404040);

        // Thin progress bar along the toast's bottom edge, tinted by state.
        float fraction = total > 0 ? Math.min(1.0F, (float) done / total) : 0.0F;
        Gui.drawRect(3, 28, 3 + (int) ((WIDTH - 6) * fraction), 30, statusColor(state) | 0xFF000000);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        ItemStack icon = state == CraftProgressMessage.State.CRAFTING && !current.isEmpty()
                ? current : target;
        if (!icon.isEmpty()) {
            RenderHelper.enableGUIStandardItemLighting();
            toastGui.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(icon, 8, 8);
            RenderHelper.disableStandardItemLighting();
        }
        return Visibility.SHOW;
    }

    private static String statusLabel(CraftProgressMessage.State state) {
        switch (state) {
            case PAUSED:
                return I18n.format("sprawlcrafting.toast.paused");
            case PAUSED_STATION:
                return I18n.format("sprawlcrafting.toast.paused_station");
            case FINISHED:
                return I18n.format("sprawlcrafting.toast.finished");
            case READY_IN_GRID:
                return I18n.format("sprawlcrafting.toast.ready_in_grid");
            case CANCELLED:
                return I18n.format("sprawlcrafting.toast.cancelled");
            case CRAFTING:
            default:
                return I18n.format("sprawlcrafting.toast.crafting");
        }
    }

    private static int statusColor(CraftProgressMessage.State state) {
        switch (state) {
            case PAUSED:
            case PAUSED_STATION:
                return 0xFFB86A00; // orange
            case FINISHED:
            case READY_IN_GRID:
                return 0xFF2E9E2E; // green
            case CANCELLED:
                return 0xFFB03030; // red
            case CRAFTING:
            default:
                return 0xFF9E8A1E; // yellow, readable on the light toast background
        }
    }

    private static String trim(GuiToast toastGui, String text, int maxWidth) {
        if (toastGui.getMinecraft().fontRenderer.getStringWidth(text) <= maxWidth) {
            return text;
        }
        while (text.length() > 1
                && toastGui.getMinecraft().fontRenderer.getStringWidth(text + "...") > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "...";
    }
}
