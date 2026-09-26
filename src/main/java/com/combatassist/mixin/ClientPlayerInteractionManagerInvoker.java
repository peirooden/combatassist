package com.combatassist.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 原版 syncSelectedSlot 是 private —— 调用它必须走 @Invoker，不能用 @Shadow。 */
@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerInvoker {

    @Invoker("syncSelectedSlot")
    void combatassist$syncSelectedSlot();
}
