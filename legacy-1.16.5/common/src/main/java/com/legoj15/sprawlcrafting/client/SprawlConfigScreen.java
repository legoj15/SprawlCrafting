package com.legoj15.sprawlcrafting.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.legoj15.sprawlcrafting.config.SprawlConfig;
import com.legoj15.sprawlcrafting.platform.Services;
import com.mojang.blaze3d.matrix.MatrixStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;

/**
 * The in-game settings screen for {@link SprawlConfig}: one clickable row per toggle, each showing its
 * name + ON/OFF state and a one-line description. Reachable from Forge's mod-list "Config" button and,
 * on Fabric, from Mod Menu. A click flips the toggle and persists immediately; ESC returns to the parent.
 * The JEI row only appears when JEI is installed (JEI is Forge-only on 1.16.5).
 */
public class SprawlConfigScreen extends Screen {

    private static final int ROW_HEIGHT = 40;
    private static final int HALF_WIDTH = 170;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int DESC_COLOR = 0xFFAAAAAA;
    private static final int HINT_COLOR = 0xFF808080;
    private static final int ROW_BG = 0x40000000;
    private static final int ROW_BG_HOVER = 0x60FFFFFF;
    private static final int MOUSE_BUTTON_LEFT = 0;

    private final Screen parent;
    private final List<Toggle> toggles = new ArrayList<>();
    private int firstRowY;

    public SprawlConfigScreen(Screen parent) {
        super(new TranslationTextComponent("sprawlcrafting.config.title"));
        this.parent = parent;
        final SprawlConfig cfg = SprawlConfig.get();
        toggles.add(new Toggle("sprawlcrafting.config.sound", cfg::soundEffects, cfg::setSoundEffects));
        if (Services.PLATFORM.isModLoaded("jei")) {
            toggles.add(new Toggle("sprawlcrafting.config.jei", cfg::jeiIntegration, cfg::setJeiIntegration));
        }
        toggles.add(new Toggle("sprawlcrafting.config.needs", cfg::needsSystem, cfg::setNeedsSystem));
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

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < boxLeft() || mouseX > boxRight()) {
            return -1;
        }
        for (int i = 0; i < toggles.size(); i++) {
            int top = rowTop(i);
            if (mouseY >= top && mouseY < top + ROW_HEIGHT - 6) {
                return i;
            }
        }
        return -1;
    }

    private void toggle(int row) {
        Toggle t = toggles.get(row);
        t.set(!t.get());
        SprawlConfig.get().save();
        Minecraft.getInstance().getSoundManager().play(SimpleSound.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private ITextComponent rowLabel(Toggle t) {
        ITextComponent state = new TranslationTextComponent(t.get() ? "sprawlcrafting.config.on" : "sprawlcrafting.config.off")
                .withStyle(t.get() ? TextFormatting.GREEN : TextFormatting.RED);
        IFormattableTextComponent label = new TranslationTextComponent(t.key);
        return label.append(new StringTextComponent(": ")).append(state);
    }

    @Override
    public void onClose() {
        SprawlConfig.get().save();
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(MatrixStack poseStack, int mouseX, int mouseY, float partialTick) {
        fill(poseStack, 0, 0, width, height, 0xC0000000);
        drawCenteredString(poseStack, this.font, getTitle(), width / 2, 18, TITLE_COLOR);
        int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < toggles.size(); i++) {
            int top = rowTop(i);
            fill(poseStack, boxLeft(), top, boxRight(), top + ROW_HEIGHT - 6, i == hovered ? ROW_BG_HOVER : ROW_BG);
            Toggle t = toggles.get(i);
            drawCenteredString(poseStack, this.font, rowLabel(t), width / 2, top + 6, TITLE_COLOR);
            drawCenteredString(poseStack, this.font, new TranslationTextComponent(t.key + ".desc"),
                    width / 2, top + 20, DESC_COLOR);
        }
        drawCenteredString(poseStack, this.font, new TranslationTextComponent("sprawlcrafting.config.hint"),
                width / 2, height - 20, HINT_COLOR);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == MOUSE_BUTTON_LEFT) {
            int row = rowAt(mouseX, mouseY);
            if (row >= 0) {
                toggle(row);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static final class Toggle {
        final String key;
        private final BooleanSupplier getter;
        private final BoolSetter setter;

        Toggle(String key, BooleanSupplier getter, BoolSetter setter) {
            this.key = key;
            this.getter = getter;
            this.setter = setter;
        }

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
