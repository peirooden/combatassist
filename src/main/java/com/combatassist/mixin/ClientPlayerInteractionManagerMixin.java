package com.combatassist.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 秒切改用「动态改键位」实现后不再需要注入攻击流程，这里留空占位。
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
}
