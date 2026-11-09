package com.legoj15.sprawlcrafting.client;

import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.legoj15.sprawlcrafting.craft.ItemDemand;
import com.legoj15.sprawlcrafting.craft.ShortfallView;
import com.legoj15.solver.CountFormat;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.renderer.Rectangle2d;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;

/**
 * The chat-free "gather list" for a red/unmakeable recipe: a scrollable grid of the raw materials the
 * player still needs, each as an item icon + count, hoverable for its name. Opened by right-clicking a
 * red recipe-book entry or clicking the orange JEI transfer button (see {@link MissingIngredientsView}).
 * ESC returns to the parent screen. JEI's R/U keys work on the hovered item via the JEI gui handler.
 */
public class MissingIngredientsScreen extends Screen {

    private static final int MAX_COLS = 9;
    private static final int SLOT = 18;
    /** Screen margin kept clear on each side when the grid is narrowed to fit a small window. */
    private static final int SIDE_MARGIN = 10;
    /** Ticks each alternative is shown before cycling to the next (1 second at 20 TPS). */
    private static final int TICKS_PER_CYCLE = 20;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int MUTED_COLOR = 0xFFAAAAAA;
    private static final int WARN_COLOR = 0xFFFFAA55;

    private final Screen parent;
    private final ShortfallView view;
    private final List<Row> rows = new ArrayList<>();

    private int cols = MAX_COLS;
    /** Horizontal cell width: grows past {@link #SLOT} until the widest count fits beside its neighbours. */
    private int pitch = SLOT;
    private int gridLeft;
    private int viewportTop;
    private int viewportBottom;
    private double scrollAmount;
    private int cycleTicks;

    public MissingIngredientsScreen(Screen parent, ShortfallView view) {
        super(new TranslationTextComponent("sprawlcrafting.gather.header"));
        this.parent = parent;
        this.view = view;
        for (ItemDemand demand : view.demands()) {
            List<ItemStack> stacks = new ArrayList<>();
            for (ResourceLocation id : demand.items()) {
                Item item = MissingIngredients.itemOf(id);
                if (item != Items.AIR) {
                    stacks.add(new ItemStack(item));
                }
            }
            if (!stacks.isEmpty()) {
                rows.add(new Row(stacks, demand.count()));
            }
        }
    }

    @Override
    protected void init() {
        int widest = 0;
        for (Row row : rows) {
            widest = Math.max(widest, font.width(formatCount(row.count)));
        }
        pitch = Math.max(SLOT, widest + 2);
        cols = MathHelper.clamp((width - 2 * SIDE_MARGIN) / pitch, 1, MAX_COLS);
        gridLeft = (width - cols * pitch) / 2;
        viewportTop = 40;
        viewportBottom = Math.max(viewportTop + SLOT, height - 40);
        scrollAmount = MathHelper.clamp(scrollAmount, 0, maxScroll());
    }

    /**
     * Left edge of the icon for grid {@code index}. The icon sits at the cell's right side because the
     * count is right-aligned to it and, when wider than the icon, extends leftward into the cell's spare width.
     */
    private int iconLeft(int index) {
        return gridLeft + (index % cols) * pitch + pitch - SLOT + 1;
    }

    private int contentHeight() {
        return ((rows.size() + cols - 1) / cols) * SLOT;
    }

    private double maxScroll() {
        return Math.max(0, contentHeight() - (viewportBottom - viewportTop));
    }

    /** The alternative to show for {@code row} right now — cycles tag slots like the recipe book. */
    private ItemStack displayed(Row row) {
        List<ItemStack> stacks = row.stacks;
        return stacks.size() == 1 ? stacks.get(0) : stacks.get((cycleTicks / TICKS_PER_CYCLE) % stacks.size());
    }

    private ITextComponent titleText() {
        ItemStack target = new ItemStack(MissingIngredients.itemOf(view.targetItem()));
        return new TranslationTextComponent("sprawlcrafting.gather.title", target.getHoverName());
    }

    /**
     * The line shown when there are no demand rows: the materials are all in hand but the chain needs a
     * bigger grid, unless the tree was too complex to resolve (then say so).
     */
    private ITextComponent emptyMessage() {
        return view.approximate()
                ? new TranslationTextComponent("sprawlcrafting.gather.approximate")
                : new TranslationTextComponent("sprawlcrafting.gather.empty");
    }

    private int hoveredIndex(double mouseX, double mouseY) {
        if (mouseX < gridLeft || mouseX >= gridLeft + cols * pitch
                || mouseY < viewportTop || mouseY >= viewportBottom) {
            return -1;
        }
        int col = (int) ((mouseX - gridLeft) / pitch);
        int row = (int) ((mouseY - viewportTop + scrollAmount) / SLOT);
        int index = row * cols + col;
        return index >= 0 && index < rows.size() ? index : -1;
    }

    /** The alternative currently shown under the cursor (EMPTY if none) — for JEI R/U lookup. */
    public ItemStack hoveredStack(double mouseX, double mouseY) {
        int index = hoveredIndex(mouseX, mouseY);
        return index < 0 ? ItemStack.EMPTY : displayed(rows.get(index));
    }

    /** Screen-space icon rect of the hovered slot (null if none) — for JEI's clickable-ingredient area. */
    public Rectangle2d hoveredArea(double mouseX, double mouseY) {
        int index = hoveredIndex(mouseX, mouseY);
        if (index < 0) {
            return null;
        }
        int y = viewportTop + (index / cols) * SLOT - (int) Math.round(scrollAmount) + 1;
        return new Rectangle2d(iconLeft(index), y, 16, 16);
    }

    /** Slot-overlay count: at most five characters ({@code 999}, {@code 1.2K}, {@code 9e18}); {@code 9e18+} when saturated. */
    private static String formatCount(long count) {
        return CountFormat.compact(count);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scrollAmount = MathHelper.clamp(scrollAmount - delta * SLOT, 0, maxScroll());
        return true;
    }

    @Override
    public void tick() {
        cycleTicks++;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(MatrixStack poseStack, int mouseX, int mouseY, float partialTick) {
        fill(poseStack, 0, 0, width, height, 0xC0000000);
        drawCenteredString(poseStack, this.font, titleText(), width / 2, 15, TITLE_COLOR);
        super.render(poseStack, mouseX, mouseY, partialTick);
        if (rows.isEmpty()) {
            drawCenteredString(poseStack, this.font, emptyMessage(), width / 2, viewportTop + 20, MUTED_COLOR);
            return;
        }
        scissor(gridLeft, viewportTop, gridLeft + cols * pitch, viewportBottom);
        int scroll = (int) Math.round(scrollAmount);
        for (int i = 0; i < rows.size(); i++) {
            int x = iconLeft(i);
            int y = viewportTop + (i / cols) * SLOT - scroll;
            if (y + SLOT <= viewportTop || y >= viewportBottom) {
                continue;
            }
            Row row = rows.get(i);
            ItemStack shown = displayed(row);
            this.itemRenderer.renderAndDecorateFakeItem(shown, x, y + 1);
            this.itemRenderer.renderGuiItemDecorations(this.font, shown, x, y + 1, formatCount(row.count));
        }
        RenderSystem.disableScissor();
        if (view.approximate()) {
            drawCenteredString(poseStack, this.font, new TranslationTextComponent("sprawlcrafting.gather.approximate"),
                    width / 2, height - 28, WARN_COLOR);
        }
        int hovered = hoveredIndex(mouseX, mouseY);
        if (hovered >= 0) {
            Row row = rows.get(hovered);
            ItemStack shown = displayed(row);
            List<ITextComponent> tooltip = ImmutableList.<ITextComponent>of(shown.getHoverName(),
                    new TranslationTextComponent("sprawlcrafting.gather.need", GatherTooltip.countText(row.count)).withStyle(TextFormatting.GRAY));
            renderComponentTooltip(poseStack, tooltip, mouseX, mouseY);
        }
    }

    /** GUI-space scissor (1.16.5's RenderSystem.enableScissor takes window pixels, origin bottom-left). */
    private void scissor(int left, int top, int right, int bottom) {
        double scale = this.minecraft.getWindow().getGuiScale();
        int windowHeight = this.minecraft.getWindow().getHeight();
        RenderSystem.enableScissor((int) (left * scale), (int) (windowHeight - bottom * scale),
                Math.max(0, (int) ((right - left) * scale)), Math.max(0, (int) ((bottom - top) * scale)));
    }

    private static final class Row {
        final List<ItemStack> stacks;
        final long count;

        Row(List<ItemStack> stacks, long count) {
            this.stacks = stacks;
            this.count = count;
        }
    }
}
