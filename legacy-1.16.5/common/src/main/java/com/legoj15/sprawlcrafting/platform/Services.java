package com.legoj15.sprawlcrafting.platform;

import java.util.Iterator;
import java.util.ServiceLoader;

import com.legoj15.sprawlcrafting.Constants;

/**
 * Resolves the loader's {@link IPlatformHelper} through {@link ServiceLoader} (each loader jar ships a
 * {@code META-INF/services} entry). The mod's own class loader is passed explicitly: under Forge's
 * ModLauncher the thread context loader is not guaranteed to see mod classes.
 */
public final class Services {

    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    private Services() {
    }

    private static <T> T load(Class<T> type) {
        Iterator<T> it = ServiceLoader.load(type, Services.class.getClassLoader()).iterator();
        if (!it.hasNext()) {
            throw new IllegalStateException("No implementation of " + type.getName() + " on this loader");
        }
        T impl = it.next();
        Constants.LOG.debug("Loaded {} for service {}", impl, type);
        return impl;
    }
}
