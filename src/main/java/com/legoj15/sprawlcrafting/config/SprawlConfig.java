package com.legoj15.sprawlcrafting.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.platform.Services;

/**
 * Client-side mod settings, persisted as {@code config/sprawlcrafting.json}. Four toggles, all
 * defaulting to {@code true} (the original behaviour):
 * <ul>
 *   <li>{@code sound_effects} — the per-step craft "pop" sound.</li>
 *   <li>{@code jei_integration} — master switch for every JEI hook (the yellow deferred-craft
 *       transfer button and, with {@code needs_system}, the orange gather button).</li>
 *   <li>{@code rei_integration} — the same master switch for REI.</li>
 *   <li>{@code needs_system} — the "what do I still need" helper: right-clicking a red recipe,
 *       its tooltip hint, and the orange gather button in JEI/REI.</li>
 * </ul>
 *
 * <p>Every consumer reads the value live (via {@link #get()}), so the in-game settings screen's
 * edits take effect immediately, and each edit is written straight back to disk.
 *
 * <p>Lives in shared code: it uses only GSON (bundled with vanilla) and the platform-resolved
 * config directory via {@link Services}. Nothing here is loader- or side-specific, but every
 * feature it gates is client-side, so in practice it is only ever loaded on the client.
 */
public final class SprawlConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SprawlConfig instance;

    private boolean soundEffects = true;
    private boolean jeiIntegration = true;
    private boolean reiIntegration = true;
    private boolean needsSystem = true;

    private SprawlConfig() {
    }

    /** The singleton, loaded (and the file created with defaults) on first access. */
    public static synchronized SprawlConfig get() {
        if (instance == null) {
            instance = new SprawlConfig();
            instance.load();
        }
        return instance;
    }

    public boolean soundEffects() {
        return soundEffects;
    }

    public boolean jeiIntegration() {
        return jeiIntegration;
    }

    public boolean reiIntegration() {
        return reiIntegration;
    }

    public boolean needsSystem() {
        return needsSystem;
    }

    public void setSoundEffects(boolean value) {
        soundEffects = value;
    }

    public void setJeiIntegration(boolean value) {
        jeiIntegration = value;
    }

    public void setReiIntegration(boolean value) {
        reiIntegration = value;
    }

    public void setNeedsSystem(boolean value) {
        needsSystem = value;
    }

    private static Path file() {
        return Services.PLATFORM.getConfigDir().resolve(Constants.MOD_ID + ".json");
    }

    /**
     * (Re)reads the config file. A missing key keeps its current (default) value; a missing or
     * unreadable file is (re)written with the defaults so the next hand-edit has a template.
     */
    public void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            soundEffects = bool(json, "sound_effects", soundEffects);
            jeiIntegration = bool(json, "jei_integration", jeiIntegration);
            reiIntegration = bool(json, "rei_integration", reiIntegration);
            needsSystem = bool(json, "needs_system", needsSystem);
        } catch (Exception e) {
            Constants.LOG.warn("Could not read {}, using defaults: {}", path, e.toString());
            save(); // rewrite a clean file over the corrupt one
        }
    }

    /** Writes the current values to disk (pretty-printed). Called on first run and after every edit. */
    public void save() {
        JsonObject json = new JsonObject();
        json.addProperty("_comment",
                "SprawlCrafting client settings — true = enabled. Also editable in-game from the mod menu.");
        json.addProperty("sound_effects", soundEffects);
        json.addProperty("jei_integration", jeiIntegration);
        json.addProperty("rei_integration", reiIntegration);
        json.addProperty("needs_system", needsSystem);
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(json, writer);
            }
        } catch (IOException e) {
            Constants.LOG.warn("Could not write {}: {}", path, e.toString());
        }
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive()
                ? json.get(key).getAsBoolean() : fallback;
    }
}
