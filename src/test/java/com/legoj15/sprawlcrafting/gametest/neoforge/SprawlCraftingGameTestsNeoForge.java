package com.legoj15.sprawlcrafting.gametest.neoforge;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.gametest.CraftGameScenarios;

import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * NeoForge registration for the {@link CraftGameScenarios} server game tests, mirroring the proven
 * BuildCraft pattern: one {@code registerAll()} source of truth, with the registrar differing by MC
 * line. 26.x uses the dynamic {@code Registries.TEST_FUNCTION} registry (the {@code data/
 * sprawlcrafting/test_instance/*.json} manifests bind each function to {@code minecraft:empty});
 * 1.21.1 — which has neither that registry nor JSON test manifests — reflects {@code TestFunction}s
 * straight into {@code GameTestRegistry} against the {@code sprawlcrafting:empty} arena
 * (data/sprawlcrafting/structure/empty.nbt). Excluded from the Fabric nodes' compile (it imports
 * NeoForge event types); the Fabric registration is a separate entry point.
 */
@EventBusSubscriber(modid = Constants.MOD_ID)
public final class SprawlCraftingGameTestsNeoForge {

    private SprawlCraftingGameTestsNeoForge() {
    }

    //? if >=1.21.11 {
    /*@SubscribeEvent
    public static void onRegister(net.neoforged.neoforge.registries.RegisterEvent event) {
        if (event.getRegistryKey().equals(net.minecraft.core.registries.Registries.TEST_FUNCTION)) {
            registerAll((id, sup) -> event.register(net.minecraft.core.registries.Registries.TEST_FUNCTION,
                    net.minecraft.resources.ResourceLocation.parse(id), sup));
        }
    }*/
    //?} else {
    @SubscribeEvent
    public static void onRegisterGameTests(net.neoforged.neoforge.event.RegisterGameTestsEvent event) {
        registerAll((id, sup) -> addReflectively(id, sup));
    }

    // 1.21.1 has no dynamic TEST_FUNCTION registry; GameTestRegistry.TEST_FUNCTIONS is a private
    // mutable collection the runner reads. Build a TestFunction from the same (id, supplier) the
    // modern path registers and add it directly, against the sprawlcrafting:empty arena. 12-arg ctor:
    // batch, name, structure, rotation, maxTicks, setupTicks, required, manualOnly, maxAttempts,
    // requiredSuccesses, skyAccess, fn.
    private static void addReflectively(String id, Supplier<Consumer<GameTestHelper>> sup) {
        try {
            java.lang.reflect.Field f = net.minecraft.gametest.framework.GameTestRegistry.class
                    .getDeclaredField("TEST_FUNCTIONS");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Collection<net.minecraft.gametest.framework.TestFunction> coll =
                    (java.util.Collection<net.minecraft.gametest.framework.TestFunction>) f.get(null);
            coll.add(new net.minecraft.gametest.framework.TestFunction(
                    "defaultBatch", id, "sprawlcrafting:empty",
                    net.minecraft.world.level.block.Rotation.NONE, 100, 0L, true, false, 1, 1, true, sup.get()));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to register 1.21.1 game test " + id, e);
        }
    }
    //?}

    /** Single source of truth for every game-test registration; both MC lines call this. */
    private static void registerAll(BiConsumer<String, Supplier<Consumer<GameTestHelper>>> reg) {
        reg.accept("sprawlcrafting:all_crafting_recipes_resolve_result",
                () -> CraftGameScenarios::allCraftingRecipesResolveResultWithoutThrowing);
        reg.accept("sprawlcrafting:known_recipe_resolves_expected_result",
                () -> CraftGameScenarios::knownRecipeResolvesExpectedResult);
    }
}
