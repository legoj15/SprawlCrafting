package com.legoj15.sprawlcrafting.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.platform.Services;

/**
 * Client-side mod settings, persisted as {@code config/sprawlcrafting.json} (same file and keys as the
 * modern loaders). Three toggles, all defaulting to {@code true}:
 * <ul>
 *   <li>{@code sound_effects} — the per-step craft "pop" sound.</li>
 *   <li>{@code jei_integration} — master switch for the JEI hooks (Forge only; JEI has no 1.16.5 Fabric build).</li>
 *   <li>{@code needs_system} — the "what do I still need" helper: right-click a red recipe, its tooltip
 *       hint, and the orange JEI gather button.</li>
 * </ul>
 * There is no REI integration on 1.16.5, so the modern {@code rei_integration} key is not read or written.
 * Every consumer reads values live via {@link #get()}; each edit is written straight back to disk.
 */
public final class SprawlConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SprawlConfig instance;

    private boolean soundEffects = true;
    private boolean jeiIntegration = true;
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

    public boolean needsSystem() {
        return needsSystem;
    }

    public void setSoundEffects(boolean value) {
        soundEffects = value;
    }

    public void setJeiIntegration(boolean value) {
        jeiIntegration = value;
    }

    public void setNeedsSystem(boolean value) {
        needsSystem = value;
    }

    private static Path file() {
        return Services.PLATFORM.getConfigDir().resolve(Constants.MOD_ID + ".json");
    }

    /** (Re)reads the file; a missing key keeps its default, a missing/corrupt file is rewritten. */
    public void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            // Gson 2.8.0 (shipped with 1.16.5) predates the static JsonParser.parseReader.
            JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
            soundEffects = bool(json, "sound_effects", soundEffects);
            jeiIntegration = bool(json, "jei_integration", jeiIntegration);
            needsSystem = bool(json, "needs_system", needsSystem);
        } catch (Exception e) {
            Constants.LOG.warn("Could not read {}, using defaults: {}", path, e.toString());
            save();
        }
    }

    /** Writes the current values to disk (pretty-printed). Called on first run and after every edit. */
    public void save() {
        JsonObject json = new JsonObject();
        json.addProperty("_comment",
                "SprawlCrafting client settings - true = enabled. Also editable in-game from the mod list.");
        json.addProperty("sound_effects", soundEffects);
        json.addProperty("jei_integration", jeiIntegration);
        json.addProperty("needs_system", needsSystem);
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
        } catch (IOException e) {
            Constants.LOG.warn("Could not write {}: {}", path, e.toString());
        }
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsBoolean() : fallback;
    }
}
