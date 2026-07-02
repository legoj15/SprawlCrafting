package com.legoj15.sprawlcrafting.forge.client;

import com.legoj15.sprawlcrafting.forge.network.CraftProgressMessage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Top-right HUD flyout showing the active deferred craft's progress — the 1.12.2 analog of the
 * modern toast. Reads {@link ClientCraftState} (fed by {@link CraftProgressMessage}) and draws a
 * compact card with the item being crafted, the target, a progress bar, and a status line. A
 * finished/cancelled card lingers briefly, then hides itself.
 */
public final class HudOverlay {

    private static final int BOX_WIDTH = 132;
    private static final int BOX_HEIGHT = 44;
    private static final int MARGIN = 6;

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        if (!ClientCraftState.active) {
            return;
        }
        long terminalSince = ClientCraftState.terminalSinceMs;
        if (terminalSince != 0L
                && System.currentTimeMillis() - terminalSince > ClientCraftState.TERMINAL_LINGER_MS) {
            ClientCraftState.clear();
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.gameSettings.showDebugInfo) {
            return;
        }
        draw(mc, event.getResolution());
    }

    private void draw(Minecraft mc, ScaledResolution resolution) {
        ItemStack target = ClientCraftState.target;
        ItemStack current = ClientCraftState.current;
        CraftProgressMessage.State state = ClientCraftState.state;
        int done = ClientCraftState.done;
        int total = ClientCraftState.total;

        int x = resolution.getScaledWidth() - BOX_WIDTH - MARGIN;
        int y = MARGIN;

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        Gui.drawRect(x, y, x + BOX_WIDTH, y + BOX_HEIGHT, 0xC0101018);
        Gui.drawRect(x, y, x + BOX_WIDTH, y + 1, 0xFF000000);

        int textX = x + 24;
        mc.fontRenderer.drawStringWithShadow(statusLabel(state), textX, y + 5, statusColor(state));
        String name = target.isEmpty() ? "" : trim(mc, target.getDisplayName(), BOX_WIDTH - 28);
        mc.fontRenderer.drawStringWithShadow(name, textX, y + 16, 0xFFFFFFFF);
        mc.fontRenderer.drawStringWithShadow(done + " / " + total, textX, y + 27, 0xFFB0B0B0);

        int barX = x + 5;
        int barY = y + BOX_HEIGHT - 5;
        int barWidth = BOX_WIDTH - 10;
        Gui.drawRect(barX, barY, barX + barWidth, barY + 3, 0xFF2A2A2A);
        float fraction = total > 0 ? Math.min(1.0F, (float) done / total) : 0.0F;
        Gui.drawRect(barX, barY, barX + (int) (barWidth * fraction), barY + 3, statusColor(state) | 0xFF000000);

        ItemStack icon = state == CraftProgressMessage.State.CRAFTING && !current.isEmpty() ? current : target;
        if (!icon.isEmpty()) {
            RenderHelper.enableGUIStandardItemLighting();
            mc.getRenderItem().renderItemAndEffectIntoGUI(icon, x + 4, y + 13);
            RenderHelper.disableStandardItemLighting();
        }

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    private static String statusLabel(CraftProgressMessage.State state) {
        switch (state) {
            case PAUSED:
                return "Paused — need table";
            case PAUSED_STATION:
                return "Paused — open station";
            case FINISHED:
                return "Done!";
            case READY_IN_GRID:
                return "Ready — grab it!";
            case CANCELLED:
                return "Cancelled";
            case CRAFTING:
            default:
                return "Sprawl-crafting…";
        }
    }

    private static int statusColor(CraftProgressMessage.State state) {
        switch (state) {
            case PAUSED:
            case PAUSED_STATION:
                return 0xFFFFC04D; // orange
            case FINISHED:
            case READY_IN_GRID:
                return 0xFF5CE65C; // green
            case CANCELLED:
                return 0xFFE65C5C; // red
            case CRAFTING:
            default:
                return 0xFFE6D24D; // yellow
        }
    }

    private static String trim(Minecraft mc, String text, int maxWidth) {
        if (mc.fontRenderer.getStringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        while (text.length() > 1 && mc.fontRenderer.getStringWidth(text + ellipsis) > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + ellipsis;
    }
}
