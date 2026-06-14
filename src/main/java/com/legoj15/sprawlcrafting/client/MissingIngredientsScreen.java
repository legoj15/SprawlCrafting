package com.legoj15.sprawlcrafting.client;

import java.util.ArrayList;
import java.util.List;

import com.legoj15.sprawlcrafting.craft.ItemDemand;
import com.legoj15.sprawlcrafting.craft.ShortfallView;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
//? if >=1.21.11 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
//?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

/**
 * The chat-free "gather list" for a red/unmakeable recipe: a scrollable grid of the raw materials the
 * player still needs, each as an item icon + count, hoverable for its name. Opened by right-clicking a
 * red recipe-book entry or clicking the red JEI transfer button (see {@link MissingIngredientsView}).
 *
 * <p>On 1.21.1 the list is solved locally and the screen opens already populated. On 26.x the client
 * can't read recipes, so it opens in a loading state with a request {@code token} and {@link #tick()}
 * picks up the server reply via {@link MissingIngredients#ifReady}. ESC (the default close key) returns
 * to the parent screen, preserving the JEI/recipe-book context.
 *
 * <p>1.21.1 draws from {@code render(GuiGraphics, …)}; 26.x from
 * {@code extractRenderState(GuiGraphicsExtractor, …)} — the only methods that diverge are the draw
 * calls, funneled through the two render bodies below (mirroring {@code CraftProgressToast}).
 */
public class MissingIngredientsScreen extends Screen {

    private static final int COLS = 9;
    private static final int SLOT = 18;
    /** Ticks each alternative is shown before cycling to the next (1 second at 20 TPS). */
    private static final int TICKS_PER_CYCLE = 20;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int MUTED_COLOR = 0xFFAAAAAA;
    private static final int WARN_COLOR = 0xFFFFAA55;

    private final Screen parent;
    private final int token; // 26.x reply correlation; unused on 1.21.1 (view arrives at construction)
    private ShortfallView view; // null == still loading (26.x only)
    private final List<Row> rows = new ArrayList<>();

    private int gridLeft;
    private int viewportTop;
    private int viewportBottom;
    private double scrollAmount;
    private int cycleTicks; // advances each tick to animate multi-item (tag) slots
    private double lastMouseX;
    private double lastMouseY;

    /** Optional viewer bridge (REI) for opening recipes/uses on R/U; null when none is registered. */
    private static RecipeLookup recipeLookup;

    public static void setRecipeLookup(RecipeLookup lookup) {
        recipeLookup = lookup;
    }

    public MissingIngredientsScreen(Screen parent, ShortfallView view, int token) {
        super(Component.translatable("sprawlcrafting.gather.header"));
        this.parent = parent;
        this.view = view;
        this.token = token;
        rebuild();
    }

    @Override
    protected void init() {
        layout();
    }

    private void layout() {
        gridLeft = (width - COLS * SLOT) / 2;
        viewportTop = 40;
        viewportBottom = Math.max(viewportTop + SLOT, height - 40);
        scrollAmount = Mth.clamp(scrollAmount, 0, maxScroll());
    }

    private int contentHeight() {
        return ((rows.size() + COLS - 1) / COLS) * SLOT;
    }

    private double maxScroll() {
        return Math.max(0, contentHeight() - (viewportBottom - viewportTop));
    }

    private void setView(ShortfallView ready) {
        this.view = ready;
        rebuild();
        layout();
    }

    private void rebuild() {
        rows.clear();
        if (view == null) {
            return;
        }
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

    /** The alternative to show for {@code row} right now — cycles tag slots like the recipe book. */
    private ItemStack displayed(Row row) {
        List<ItemStack> stacks = row.stacks();
        return stacks.size() == 1 ? stacks.get(0)
                : stacks.get((cycleTicks / TICKS_PER_CYCLE) % stacks.size());
    }

    private Component titleText() {
        if (view == null) {
            return Component.translatable("sprawlcrafting.gather.loading");
        }
        ItemStack target = new ItemStack(MissingIngredients.itemOf(view.targetItem()));
        return Component.translatable("sprawlcrafting.gather.title", target.getHoverName());
    }

    /**
     * The line shown when there are no demand rows. An empty list means the materials are all in hand
     * but the recipe (or its chain) needs a bigger grid than where the request came from — the gather
     * query always costs the full 3×3 chain, and a recipe makeable on the current grid is never red, so
     * "empty" can only mean "you have everything, just open a 3×3 table". The exception is a recipe tree
     * too complex to resolve: this branch returns before the approximate footer, so surface it here.
     */
    private Component emptyMessage() {
        return view.approximate()
                ? Component.translatable("sprawlcrafting.gather.approximate")
                : Component.translatable("sprawlcrafting.gather.empty");
    }

    /** The demand index under the cursor (accounting for scroll + viewport clip), or -1. */
    private int hoveredIndex(double mouseX, double mouseY) {
        if (view == null || mouseX < gridLeft || mouseX >= gridLeft + COLS * SLOT
                || mouseY < viewportTop || mouseY >= viewportBottom) {
            return -1;
        }
        int col = (int) ((mouseX - gridLeft) / SLOT);
        int row = (int) ((mouseY - viewportTop + scrollAmount) / SLOT);
        int index = row * COLS + col;
        return index >= 0 && index < rows.size() ? index : -1;
    }

    /** The alternative currently shown under the cursor (EMPTY if none) — for JEI/REI R/U lookup. */
    public ItemStack hoveredStack(double mouseX, double mouseY) {
        int index = hoveredIndex(mouseX, mouseY);
        return index < 0 ? ItemStack.EMPTY : displayed(rows.get(index));
    }

    /** Screen-space icon rect of the hovered slot (null if none) — for JEI's clickable-ingredient area. */
    public Rect2i hoveredArea(double mouseX, double mouseY) {
        int index = hoveredIndex(mouseX, mouseY);
        if (index < 0) {
            return null;
        }
        int x = gridLeft + (index % COLS) * SLOT + 1;
        int y = viewportTop + (index / COLS) * SLOT - (int) Math.round(scrollAmount) + 1;
        return new Rect2i(x, y, 16, 16);
    }

    private static String formatCount(int count) {
        return count >= 1000 ? (count / 1000) + "k" : Integer.toString(count);
    }

    private List<Component> itemTooltip(ItemStack shown, int count) {
        return List.of(
                shown.getHoverName(),
                Component.translatable("sprawlcrafting.gather.need", count).withStyle(ChatFormatting.GRAY));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollAmount = Mth.clamp(scrollAmount - scrollY * SLOT, 0, maxScroll());
        return true;
    }

    @Override
    public void tick() {
        cycleTicks++;
        //? if >=1.21.11 {
        /*if (view == null) {
            MissingIngredients.ifReady(token).ifPresent(this::setView);
        }*/
        //?}
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    //? if >=1.21.11 {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.fill(0, 0, width, height, 0xC0000000);
        guiGraphics.centeredText(this.font, titleText(), width / 2, 15, TITLE_COLOR);
        if (view == null) {
            guiGraphics.centeredText(this.font, Component.translatable("sprawlcrafting.gather.loading"),
                    width / 2, viewportTop + 20, MUTED_COLOR);
            return;
        }
        if (rows.isEmpty()) {
            guiGraphics.centeredText(this.font, emptyMessage(), width / 2, viewportTop + 20, MUTED_COLOR);
            return;
        }
        guiGraphics.enableScissor(gridLeft, viewportTop, gridLeft + COLS * SLOT, viewportBottom);
        int scroll = (int) Math.round(scrollAmount);
        for (int i = 0; i < rows.size(); i++) {
            int x = gridLeft + (i % COLS) * SLOT;
            int y = viewportTop + (i / COLS) * SLOT - scroll;
            if (y + SLOT <= viewportTop || y >= viewportBottom) {
                continue;
            }
            Row row = rows.get(i);
            ItemStack shown = displayed(row);
            guiGraphics.fakeItem(shown, x + 1, y + 1);
            guiGraphics.itemDecorations(this.font, shown, x + 1, y + 1, formatCount(row.count()));
        }
        guiGraphics.disableScissor();
        if (view.approximate()) {
            guiGraphics.centeredText(this.font, Component.translatable("sprawlcrafting.gather.approximate"),
                    width / 2, height - 28, WARN_COLOR);
        }
        int hovered = hoveredIndex(mouseX, mouseY);
        if (hovered >= 0) {
            Row row = rows.get(hovered);
            guiGraphics.setComponentTooltipForNextFrame(this.font, itemTooltip(displayed(row), row.count()), mouseX, mouseY);
        }
    }*/
    //?} else {
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        lastMouseX = mouseX; // remembered for keyPressed (R/U has no mouse coords)
        lastMouseY = mouseY;
        guiGraphics.fill(0, 0, width, height, 0xC0000000);
        guiGraphics.drawCenteredString(this.font, titleText(), width / 2, 15, TITLE_COLOR);
        if (view == null) {
            guiGraphics.drawCenteredString(this.font, Component.translatable("sprawlcrafting.gather.loading"),
                    width / 2, viewportTop + 20, MUTED_COLOR);
            return;
        }
        if (rows.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, emptyMessage(), width / 2, viewportTop + 20, MUTED_COLOR);
            return;
        }
        guiGraphics.enableScissor(gridLeft, viewportTop, gridLeft + COLS * SLOT, viewportBottom);
        int scroll = (int) Math.round(scrollAmount);
        for (int i = 0; i < rows.size(); i++) {
            int x = gridLeft + (i % COLS) * SLOT;
            int y = viewportTop + (i / COLS) * SLOT - scroll;
            if (y + SLOT <= viewportTop || y >= viewportBottom) {
                continue;
            }
            Row row = rows.get(i);
            ItemStack shown = displayed(row);
            guiGraphics.renderFakeItem(shown, x + 1, y + 1);
            guiGraphics.renderItemDecorations(this.font, shown, x + 1, y + 1, formatCount(row.count()));
        }
        guiGraphics.disableScissor();
        if (view.approximate()) {
            guiGraphics.drawCenteredString(this.font, Component.translatable("sprawlcrafting.gather.approximate"),
                    width / 2, height - 28, WARN_COLOR);
        }
        int hovered = hoveredIndex(mouseX, mouseY);
        if (hovered >= 0) {
            Row row = rows.get(hovered);
            guiGraphics.renderComponentTooltip(this.font, itemTooltip(displayed(row), row.count()), mouseX, mouseY);
        }
    }
    //?}

    //? if >=1.21.11 {
    /*// 26.x has no REI, and JEI handles R/U via its registered screen handler — no override needed.*/
    //?} else {
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (recipeLookup != null && view != null) {
            ItemStack hovered = hoveredStack(lastMouseX, lastMouseY);
            if (!hovered.isEmpty() && recipeLookup.handle(keyCode, scanCode, hovered)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    //?}

    private record Row(List<ItemStack> stacks, int count) {
    }
}
