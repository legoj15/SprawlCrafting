package com.legoj15.sprawlcrafting.forge.client;

import java.io.IOException;
import java.util.List;

import com.legoj15.sprawlcrafting.forge.craft.ItemDemand;
import com.legoj15.sprawlcrafting.forge.craft.ShortfallView;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;

import org.lwjgl.input.Mouse;

/**
 * The "missing resources" gather screen: the raw materials the player still needs to make a recipe
 * they can't (even deferred-)craft. Opened by left-clicking a red recipe in the recipe book. The
 * list is computed client-side ({@link ShortfallView}); raw-materials-only by design. Returns to the
 * screen it was opened over (the inventory/table) on Done or Escape.
 */
public class GuiMissingResources extends GuiScreen {

    private static final int PANEL_WIDTH = 256;
    private static final int ROW_HEIGHT = 20;
    private static final int DONE_BUTTON = 0;

    private final ShortfallView data;
    private final GuiScreen parent;

    private int scrollOffset;
    private int panelLeft;
    private int panelTop;
    private int panelRight;
    private int panelBottom;
    private int listTop;
    private int visibleRows;

    public GuiMissingResources(ShortfallView data, GuiScreen parent) {
        this.data = data;
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        panelLeft = (this.width - PANEL_WIDTH) / 2;
        panelRight = panelLeft + PANEL_WIDTH;
        panelTop = Math.max(16, this.height / 2 - 100);
        panelBottom = Math.min(this.height - 16, this.height / 2 + 100);
        listTop = panelTop + (data.approximate() ? 38 : 28);
        int listBottom = panelBottom - 28;
        visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        this.addButton(new GuiButton(DONE_BUTTON, this.width / 2 - 50, panelBottom - 24, 100, 20,
                I18n.format("gui.done")));
        clampScroll();
    }

    private int maxScroll() {
        return Math.max(0, data.demands().size() - visibleRows);
    }

    private void clampScroll() {
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll()));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(panelLeft, panelTop, panelRight, panelBottom, 0xE0101018);
        drawRect(panelLeft, panelTop, panelRight, panelTop + 1, 0xFF000000);

        ItemStack target = data.target();
        String title = TextFormatting.WHITE + I18n.format("sprawlcrafting.gather.title")
                + (target.isEmpty() ? "" : ": " + target.getDisplayName());
        if (!target.isEmpty()) {
            renderIcon(target, panelLeft + 8, panelTop + 6);
            this.drawString(this.fontRenderer, title, panelLeft + 28, panelTop + 10, 0xFFFFFF);
        } else {
            this.drawString(this.fontRenderer, title, panelLeft + 8, panelTop + 10, 0xFFFFFF);
        }
        if (data.approximate()) {
            this.drawString(this.fontRenderer,
                    TextFormatting.GRAY + I18n.format("sprawlcrafting.gather.approximate"),
                    panelLeft + 8, panelTop + 24, 0xA0A0A0);
        }

        List<ItemDemand> demands = data.demands();
        ItemStack hovered = null;
        if (demands.isEmpty()) {
            this.drawCenteredString(this.fontRenderer,
                    TextFormatting.GREEN + I18n.format("sprawlcrafting.gather.have_raws"),
                    this.width / 2, listTop + 12, 0xFFFFFF);
        } else {
            for (int row = 0; row < visibleRows; row++) {
                int index = scrollOffset + row;
                if (index >= demands.size()) {
                    break;
                }
                ItemDemand demand = demands.get(index);
                int y = listTop + row * ROW_HEIGHT;
                ItemStack icon = demand.representative().toStack();
                renderIcon(icon, panelLeft + 10, y);
                String name = icon.getDisplayName();
                if (demand.items().size() > 1) {
                    name = name + TextFormatting.DARK_GRAY
                            + I18n.format("sprawlcrafting.gather.alt", demand.items().size() - 1);
                }
                this.drawString(this.fontRenderer, name, panelLeft + 32, y + 4, 0xFFFFFF);
                String need = TextFormatting.GOLD + "× " + demand.count();
                this.drawString(this.fontRenderer, need,
                        panelRight - 12 - this.fontRenderer.getStringWidth(need), y + 4, 0xFFFFFF);
                if (mouseX >= panelLeft + 10 && mouseX <= panelLeft + 26 && mouseY >= y && mouseY <= y + 16) {
                    hovered = icon;
                }
            }
            if (maxScroll() > 0) {
                String hint = (scrollOffset > 0 ? "^ " : "") + (scrollOffset < maxScroll() ? "v" : "");
                this.drawString(this.fontRenderer, TextFormatting.GRAY + hint,
                        panelRight - 16, panelTop + 10, 0x808080);
            }
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
        if (hovered != null) {
            this.renderToolTip(hovered, mouseX, mouseY);
        }
    }

    private void renderIcon(ItemStack stack, int x, int y) {
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableDepth();
        this.itemRender.renderItemAndEffectIntoGUI(stack, x, y);
        RenderHelper.disableStandardItemLighting();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == DONE_BUTTON) {
            returnToParent();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1 || keyCode == this.mc.gameSettings.keyBindInventory.getKeyCode()) {
            returnToParent();
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getDWheel();
        if (wheel != 0) {
            scrollOffset += wheel > 0 ? -1 : 1;
            clampScroll();
        }
    }

    private void returnToParent() {
        this.mc.displayGuiScreen(parent);
    }

    /**
     * The demand item under the mouse, for JEI's global-handler hookup (R/U recipe lookups on the
     * gather list, like modern). Mirrors the row layout drawn in {@link #drawScreen}, over the full
     * row width rather than just the icon.
     */
    public ItemStack ingredientAt(int mouseX, int mouseY) {
        List<ItemDemand> demands = data.demands();
        if (mouseX < panelLeft + 8 || mouseX > panelRight - 8) {
            return ItemStack.EMPTY;
        }
        for (int row = 0; row < visibleRows; row++) {
            int index = scrollOffset + row;
            if (index >= demands.size()) {
                break;
            }
            int y = listTop + row * ROW_HEIGHT;
            if (mouseY >= y && mouseY <= y + 16) {
                return demands.get(index).representative().toStack();
            }
        }
        return ItemStack.EMPTY;
    }

    public int panelLeft() {
        return panelLeft;
    }

    public int panelTop() {
        return panelTop;
    }

    public int panelWidth() {
        return PANEL_WIDTH;
    }

    public int panelHeight() {
        return panelBottom - panelTop;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
