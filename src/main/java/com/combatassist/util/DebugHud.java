package com.combatassist.util;

import com.combatassist.config.CombatConfig;
import com.combatassist.module.CombatModule;
import com.combatassist.module.ModuleRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

public final class DebugHud {

    private static final int GREEN = 0xFF55FF55;
    private static final int GRAY = 0xFFAAAAAA;

    private DebugHud() {
    }

    public static void render(DrawContext context) {
        if (!CombatConfig.debugHud) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.options.hudHidden) {
            return;
        }

        int x = 6;
        int y = 6;

        context.drawTextWithShadow(client.textRenderer,
                "CombatAssist  " + (CombatConfig.enabled ? "ON" : "OFF"),
                x, y, CombatConfig.enabled ? GREEN : GRAY);
        y += 10;

        for (CombatModule module : ModuleRegistry.all()) {
            for (String line : module.hudLines()) {
                context.drawTextWithShadow(client.textRenderer, line, x, y,
                        module.isEnabled() ? GREEN : GRAY);
                y += 10;
            }
        }
    }
}
