package com.legoj15.sprawlcrafting.forge.client;

import java.io.IOException;
import java.util.List;

import com.legoj15.solver.CountFormat;
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

    /** Narrowest the panel gets; it widens past this when a row's name and need text would not fit. */
    private static final int MIN_PANEL_WIDTH = 256;
    /** Screen margin kept clear on each side when the panel is widened to fit its rows. */
    private static final int SIDE_MARGIN = 10;
    private static final int ROW_HEIGHT = 20;
    /** Item name starts this far in from the panel's left edge (after the 16-px icon). */
    private static final int NAME_INSET = 32;
    /** The "how many" text ends this far in from the panel's right edge. */
    private static final int NEED_INSET = 12;
    /** Minimum space between the end of an item name and the start of its need text. */
    private static final int NAME_NEED_GAP = 8;
    private static final String ELLIPSIS = "...";
    private static final int DONE_BUTTON = 0;

    private final ShortfallView data;
    private final GuiScreen parent;

    private int scrollOffset;
    /** Computed in {@link #initGui}: {@link #MIN_PANEL_WIDTH} widened to fit the longest row. */
    private int panelWidth = MIN_PANEL_WIDTH;
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

    /**
     * A gather count as text in the shared {@link CountFormat} scheme ({@code 1,234}, {@code 12.3K},
     * {@code 9.22e18}); a count the solver saturated at {@link Long#MAX_VALUE} reads "more than ...".
     */
    private static String countText(long count) {
        return CountFormat.isSaturated(count)
                ? I18n.format("sprawlcrafting.gather.more_than", CountFormat.format(Long.MAX_VALUE))
                : CountFormat.format(count);
    }

    /**
     * The right-hand "how many" text of a list row: {@code × 1,234}, but for a saturated count just
     * {@code more than 9.22e18}. The "×" is dropped there because "× more than ..." reads as a garbled
     * multiplier; the words already say it is a quantity.
     */
    static String needText(long count) {
        return CountFormat.isSaturated(count) ? countText(count) : "× " + countText(count);
    }

    /**
     * One JEI gather-tooltip line ({@code MissingResourcesError}): {@code 1,234× Name}, but for a
     * saturated count {@code More than 9.22e18 Name} (its own lang key, for the same reason the
     * list screen drops the "×": "more than 9.22e18× Name" reads as a garbled multiplier).
     */
    public static String lineText(long count, String name) {
        return CountFormat.isSaturated(count)
                ? I18n.format("sprawlcrafting.gather.line_more_than", CountFormat.format(Long.MAX_VALUE), name)
                : I18n.format("sprawlcrafting.gather.line", CountFormat.format(count), name);
    }

    /** The item name of a list row, with the "(or N alt.)" suffix when several items satisfy it. */
    private static String nameText(ItemDemand demand, ItemStack icon) {
        String name = icon.getDisplayName();
        if (demand.items().size() > 1) {
            name = name + TextFormatting.DARK_GRAY
                    + I18n.format("sprawlcrafting.gather.alt", demand.items().size() - 1);
        }
        return name;
    }

    /** {@code text} cut with a trailing ellipsis so it is at most {@code maxWidth} pixels wide. */
    private String fit(String text, int maxWidth) {
        if (this.fontRenderer.getStringWidth(text) <= maxWidth) {
            return text;
        }
        int room = Math.max(0, maxWidth - this.fontRenderer.getStringWidth(ELLIPSIS));
        return this.fontRenderer.trimStringToWidth(text, room) + ELLIPSIS;
    }

    /**
     * The panel width that fits the widest row (name + gap + need text) without clipping: never under
     * {@link #MIN_PANEL_WIDTH}, and past it only as far as the window's side margins allow. A row still
     * too long for a small window has its name cut short in {@link #drawScreen}.
     */
    private int fitPanelWidth() {
        int widestRow = 0;
        for (ItemDemand demand : data.demands()) {
            int name = this.fontRenderer.getStringWidth(nameText(demand, demand.representative().toStack()));
            int need = this.fontRenderer.getStringWidth(needText(demand.count()));
            widestRow = Math.max(widestRow, name + NAME_NEED_GAP + need);
        }
        int wanted = NAME_INSET + widestRow + NEED_INSET;
        int available = Math.max(MIN_PANEL_WIDTH, this.width - 2 * SIDE_MARGIN);
        return Math.max(MIN_PANEL_WIDTH, Math.min(wanted, available));
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        panelWidth = fitPanelWidth();
        panelLeft = (this.width - panelWidth) / 2;
        panelRight = panelLeft + panelWidth;
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
                String need = TextFormatting.GOLD + needText(demand.count());
                int needLeft = panelRight - NEED_INSET - this.fontRenderer.getStringWidth(need);
                int nameLeft = panelLeft + NAME_INSET;
                String fullName = nameText(demand, icon);
                String name = fit(fullName, needLeft - NAME_NEED_GAP - nameLeft);
                this.drawString(this.fontRenderer, name, nameLeft, y + 4, 0xFFFFFF);
                this.drawString(this.fontRenderer, need, needLeft, y + 4, 0xFFFFFF);
                boolean overIcon = mouseX >= panelLeft + 10 && mouseX <= panelLeft + 26;
                // A cut-short name is read in full by hovering it: the same item tooltip as the icon's.
                boolean overCutName = !name.equals(fullName) && mouseX >= nameLeft && mouseX < needLeft;
                if ((overIcon || overCutName) && mouseY >= y && mouseY <= y + 16) {
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
        return panelWidth;
    }

    public int panelHeight() {
        return panelBottom - panelTop;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
