package com.spatialaudiosystem.screen;

import com.manta.api.screen.JsonLayoutScreen;

/**
 * Loads this mod's layout JSON through Manta's resource loader. Mirrors TSU's
 * {@code TsuLayouts}; the namespace is the only difference.
 */
public final class SasLayouts {
    public static final String NAMESPACE = "spatialaudiosystem";

    private SasLayouts() {}

    public static String load(String path) {
        return JsonLayoutScreen.loadModResourceJson(NAMESPACE, path);
    }
}
