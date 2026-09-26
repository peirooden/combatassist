package com.combatassist;

import com.combatassist.config.CombatConfig;
import com.combatassist.util.DebugHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CombatAssistClient implements ClientModInitializer {

    public static final String MOD_ID = "combatassist";
    public static final Logger LOGGER = LoggerFactory.getLogger("CombatAssist");

    /** 供 Mixin 取客户端实例用。 */
    public static MinecraftClient client() {
        return MinecraftClient.getInstance();
    }

    public static void debug(String format, Object... args) {
        if (Edition.DEV && CombatConfig.debugLog) {
            LOGGER.info(format, args);
        }
    }

    @Override
    public void onInitializeClient() {
        CombatConfig.load();

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            for (var module : com.combatassist.module.ModuleRegistry.all()) {
                module.onClientTick(client);
            }
        });

        HudElementRegistry.addLast(Identifier.of(MOD_ID, "debug_hud"),
                (context, tickCounter) -> DebugHud.render(context));

        LOGGER.info("[CombatAssist] {} loaded: CLIENT-ONLY.", Edition.TITLE);
    }
}
