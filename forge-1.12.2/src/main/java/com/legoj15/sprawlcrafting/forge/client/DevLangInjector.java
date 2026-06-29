package com.legoj15.sprawlcrafting.forge.client;

import java.io.InputStream;

import net.minecraft.util.text.translation.I18n;
import net.minecraft.util.text.translation.LanguageMap;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Dev-environment workaround for translation keys rendering raw in {@code runClient}.
 *
 * <p>In the RetroFuturaGradle dev run the mod is loaded from the classpath dev jar, and FML's
 * resource layer fails to serve the lang file from that jar's {@code FMLFileResourcePack} — its
 * {@code getAllResources("sprawlcrafting:lang/en_us.lang")} (exactly what {@code LanguageManager}
 * calls) finds nothing, even though the entry is verifiably present in the jar. So the deferred
 * {@code TextComponentTranslation}s show their raw keys. A production install (mod jar in
 * {@code mods/}) loads the lang the normal way, so this only affects the dev environment.
 *
 * <p>The fix injects the lang straight into the translation {@link LanguageMap} from the classpath
 * (where the file always resolves), and re-injects if a resource reload clears the map. It is
 * registered only in a deobfuscated (dev) environment, so production — where the lang loads
 * normally — never runs it (the {@code canTranslate} guard would no-op there anyway).
 */
public final class DevLangInjector {

    private static final Logger LOG = LogManager.getLogger("sprawlcrafting");
    private static final String PROBE_KEY = "sprawlcrafting.craft.started";
    private static final String LANG_PATH = "/assets/sprawlcrafting/lang/en_us.lang";

    private boolean loggedOnce = false;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // Cheap HashMap lookup; once present (loaded normally or already injected) this no-ops.
        if (I18n.canTranslate(PROBE_KEY)) {
            return;
        }
        try (InputStream in = DevLangInjector.class.getResourceAsStream(LANG_PATH)) {
            if (in != null) {
                LanguageMap.inject(in);
                if (!loggedOnce) {
                    loggedOnce = true;
                    LOG.info("Injected en_us.lang into the translation map (dev resource-pack workaround).");
                }
            }
        } catch (Exception ignored) {
            // Best-effort; never break the client tick over a missing dev convenience.
        }
    }
}
