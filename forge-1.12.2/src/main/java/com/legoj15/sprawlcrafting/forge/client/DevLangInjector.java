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
 * <p>In the RetroFuturaGradle dev run, FML wraps the mod in an {@code FMLFolderResourcePack}
 * rooted at the classes directory ({@code build/classes/java/main}), but resources live in a
 * separate directory ({@code build/resources/main}), so the resource pack never finds
 * {@code assets/sprawlcrafting/lang/en_us.lang}. Production installs don't have this problem
 * because the jar bundles classes and resources together, and the mod's {@code pack.mcmeta}
 * ({@code pack_format: 3}) prevents the {@code LegacyV2Adapter} wrapping that would otherwise
 * transform {@code en_us} lookups into {@code en_US}.
 *
 * <p>The fix injects the lang straight into the translation {@link LanguageMap} from the
 * classpath (where the file always resolves), and re-injects if a resource reload clears the
 * map. Registered only in a deobfuscated (dev) environment.
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
