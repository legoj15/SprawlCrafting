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

    public static void register(Class<?> containerClass, Object handler) {
        HANDLERS.put(containerClass, handler);
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
