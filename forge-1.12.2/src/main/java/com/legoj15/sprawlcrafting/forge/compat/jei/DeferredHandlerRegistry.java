package com.legoj15.sprawlcrafting.forge.compat.jei;

import java.util.HashMap;
import java.util.Map;

/**
 * Stores SprawlCrafting's JEI transfer handler instances, keyed by container class. Referenced by
 * the {@code RecipeRegistryMixin} to bypass JEI's exact-class table lookup with a hierarchy walk —
 * so any mod that subclasses {@code ContainerWorkbench} is handled without an explicit registration
 * per subclass.
 *
 * <p>This class deliberately avoids importing any JEI types so it loads safely when JEI is absent.
 */
public final class DeferredHandlerRegistry {

    private DeferredHandlerRegistry() {
    }

    private static final Map<Class<?>, Object> HANDLERS = new HashMap<>();

    /**
     * A handler that sits in FRONT of another mod's handler for the same container rather than
     * replacing it (the AE2 Crafting Terminal: SprawlCrafting takes deferred and unsolvable recipes,
     * AE2 keeps the direct "+"). Such a handler is registered here only, never in JEI's own table, so
     * the table still holds the other mod's handler; {@code RecipeRegistryMixin} reads it from there on
     * every lookup and hands it over before returning this handler.
     */
    public interface FrontHandler {
        /** The handler JEI's own table holds for the container being resolved (null when none). */
        void setOriginal(Object original);
    }

    /**
     * A single class-less handler returned for containers that are structurally 3x3 crafters but
     * whose class isn't explicitly registered (an unknown modded station). Set once by the JEI
     * plugin when JEI is present; null otherwise. The {@code RecipeRegistryMixin} decides when it
     * applies (only for the open container, verified structurally) and memoises the resolved class
     * via {@link #register}.
     */
    private static Object structuralFallback;

    public static void register(Class<?> containerClass, Object handler) {
        HANDLERS.put(containerClass, handler);
    }

    public static void setStructuralFallback(Object handler) {
        structuralFallback = handler;
    }

    /** The structural fallback handler, or null when JEI isn't present. */
    public static Object structuralFallback() {
        return structuralFallback;
    }

    /**
     * Walks the class hierarchy looking for a registered handler. Returns the handler for the most
     * specific match (closest superclass), or {@code null} if no handler is registered for any
     * class in the hierarchy.
     */
    public static Object findHandler(Class<?> containerClass) {
        for (Class<?> cls = containerClass; cls != null; cls = cls.getSuperclass()) {
            Object handler = HANDLERS.get(cls);
            if (handler != null) {
                return handler;
            }
        }
        return null;
    }
}
