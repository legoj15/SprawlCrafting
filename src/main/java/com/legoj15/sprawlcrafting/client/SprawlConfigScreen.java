package com.legoj15.sprawlcrafting.client;

import java.util.List;
import java.util.function.BooleanSupplier;

import com.legoj15.sprawlcrafting.config.SprawlConfig;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
//? if >=1.21.11 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;*/
//?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

/**
 * The in-game settings screen for {@link SprawlConfig}: one clickable row per toggle, each showing
 * its name + ON/OFF state and a one-line description. Reachable from NeoForge's mod-list "Config"
 * button (registered in {@code NeoForgeClient}) and, on Fabric, from Mod Menu (via
 * {@code SprawlModMenuIntegration}). A click flips the toggle and persists immediately; ESC returns
 * to the parent screen.
 *
 * <p>Like {@link MissingIngredientsScreen}, everything is drawn by hand and the only methods that
 * diverge across MC lines are the draw + click bodies: 1.21.1 uses {@code render(GuiGraphics, …)}
 * and {@code mouseClicked(double, double, int)}; 26.x uses
 * {@code extractRenderState(GuiGraphicsExtractor, …)} and {@code mouseClicked(MouseButtonEvent, …)}.
 */
public class SprawlConfigScreen extends Screen {

    private static final int ROW_HEIGHT = 40;
    private static final int HALF_WIDTH = 170;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int DESC_COLOR = 0xFFAAAAAA;
    private static final int HINT_COLOR = 0xFF808080;
    private static final int ROW_BG = 0x40000000;
    private static final int ROW_BG_HOVER = 0x60FFFFFF;

    private final Screen parent;
    private final List<Toggle> toggles;
    private int firstRowY;

    public SprawlConfigScreen(Screen parent) {
        super(Component.translatable("sprawlcrafting.config.title"));
        this.parent = parent;
        SprawlConfig cfg = SprawlConfig.get();
        this.toggles = List.of(
                new Toggle("sprawlcrafting.config.sound", cfg::soundEffects, cfg::setSoundEffects),
                new Toggle("sprawlcrafting.config.jei", cfg::jeiIntegration, cfg::setJeiIntegration),
                new Toggle("sprawlcrafting.config.rei", cfg::reiIntegration, cfg::setReiIntegration),
                new Toggle("sprawlcrafting.config.needs", cfg::needsSystem, cfg::setNeedsSystem));
    }

    @Override
    protected void init() {
        int totalHeight = toggles.size() * ROW_HEIGHT;
        firstRowY = Math.max(48, (height - totalHeight) / 2);
    }

    private int rowTop(int index) {
        return firstRowY + index * ROW_HEIGHT;
    }

    private int boxLeft() {
        return width / 2 - HALF_WIDTH;
    }

    private int boxRight() {
        return width / 2 + HALF_WIDTH;
    }

    /** The toggle row under the cursor, or -1. */
    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < boxLeft() || mouseX > boxRight()) {
            return -1;
        }
        for (int i = 0; i < toggles.size(); i++) {
            int top = rowTop(i);
            if (mouseY >= top && mouseY < top + ROW_HEIGHT - 6) { // match the drawn row box exactly
                return i;
            }
        }
        return -1;
    }

    private void toggle(int row) {
        Toggle t = toggles.get(row);
        t.set(!t.get());
        SprawlConfig.get().save();
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private Component rowLabel(Toggle t) {
        Component state = Component.translatable(t.get() ? "sprawlcrafting.config.on" : "sprawlcrafting.config.off")
                .withStyle(t.get() ? ChatFormatting.GREEN : ChatFormatting.RED);
        return Component.translatable(t.key()).append(Component.literal(": ")).append(state);
    }

    @Override
    public void onClose() {
        SprawlConfig.get().save(); // belt-and-suspenders: each toggle already persists on change
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
        guiGraphics.centeredText(this.font, getTitle(), width / 2, 18, TITLE_COLOR);
        int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < toggles.size(); i++) {
            int top = rowTop(i);
            guiGraphics.fill(boxLeft(), top, boxRight(), top + ROW_HEIGHT - 6, i == hovered ? ROW_BG_HOVER : ROW_BG);
            Toggle t = toggles.get(i);
            guiGraphics.centeredText(this.font, rowLabel(t), width / 2, top + 6, TITLE_COLOR);
            guiGraphics.centeredText(this.font, Component.translatable(t.key() + ".desc"), width / 2, top + 20, DESC_COLOR);
        }
        guiGraphics.centeredText(this.font, Component.translatable("sprawlcrafting.config.hint"),
                width / 2, height - 20, HINT_COLOR);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int row = rowAt(event.x(), event.y());
            if (row >= 0) {
                toggle(row);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }*/
    //?} else {
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.fill(0, 0, width, height, 0xC0000000);
        guiGraphics.drawCenteredString(this.font, getTitle(), width / 2, 18, TITLE_COLOR);
        int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < toggles.size(); i++) {
            int top = rowTop(i);
            guiGraphics.fill(boxLeft(), top, boxRight(), top + ROW_HEIGHT - 6, i == hovered ? ROW_BG_HOVER : ROW_BG);
            Toggle t = toggles.get(i);
            guiGraphics.drawCenteredString(this.font, rowLabel(t), width / 2, top + 6, TITLE_COLOR);
            guiGraphics.drawCenteredString(this.font, Component.translatable(t.key() + ".desc"), width / 2, top + 20, DESC_COLOR);
        }
        guiGraphics.drawCenteredString(this.font, Component.translatable("sprawlcrafting.config.hint"),
                width / 2, height - 20, HINT_COLOR);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int row = rowAt(mouseX, mouseY);
            if (row >= 0) {
                toggle(row);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    //?}

    private record Toggle(String key, BooleanSupplier getter, BoolSetter setter) {
        boolean get() {
            return getter.getAsBoolean();
        }

        void set(boolean value) {
            setter.set(value);
        }
    }

    @FunctionalInterface
    private interface BoolSetter {
        void set(boolean value);
    }
}
