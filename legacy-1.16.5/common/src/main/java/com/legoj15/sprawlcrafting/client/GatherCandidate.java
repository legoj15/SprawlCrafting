package com.legoj15.sprawlcrafting.client;

/**
 * Duck-type implemented by {@code RecipeButtonMixin} so {@code RecipeBookComponentMixin} can ask the
 * hovered recipe button to open its gather list on a right-click — without another mixin reaching into
 * the button's private collection/recipe fields.
 *
 * <p>Deliberately NOT in the {@code mixin} package: that package is owned by the mixin config, so
 * Mixin forbids referencing any class in it directly (it would throw {@code IllegalClassLoadError}
 * when the target class loads). A plain interface a mixin implements must live outside it.
 */
public interface GatherCandidate {

    /** If this button shows a red (unmakeable) recipe, open its gather list and return true. */
    boolean sprawlcrafting$tryOpenGatherList();
}
