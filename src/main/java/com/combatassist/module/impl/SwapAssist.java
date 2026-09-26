package com.combatassist.module.impl;

import com.combatassist.CombatAssistClient;
import com.combatassist.config.CombatConfig;
import com.combatassist.mixin.ClientPlayerInteractionManagerInvoker;
import com.combatassist.module.CombatModule;
import com.combatassist.module.rule.RuleTable;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WeaponComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.EntityHitResult;

/**
 * 秒切：不改攻击流程，只<b>动态改键位</b>。
 *
 * <p>条件满足时把目标槽位的按键改成攻击键 —— 玩家按下攻击键时，
 * <b>原版自己的按键系统</b>会先切到目标槽，再执行攻击。换槽包由原版发，
 * 和真人按数字键无法区分。
 *
 * <p><b>长矛分两种完全不同的判定</b>：
 * <ul>
 *   <li><b>带「突进」(lunge) 附魔</b>：完全放开 —— 不看准星、不看手持、空手也切。
 *       突进本身能左键冲刺，收益不依赖命中目标。</li>
 *   <li><b>没突进</b>：为了增加攻击距离才切，必须准星命中且<b>手持是武器</b>
 *       （空手切过去没有基础攻击力可继承，没意义）。</li>
 * </ul>
 */
public class SwapAssist implements CombatModule {

    private static final String LUNGE_ID = "minecraft:lunge";

    private enum Kind { AXE, MACE, SPEAR }

    /** 被改过键位的槽，记下原键位以便还原。 */
    private final String[] savedKeys = new String[9];

    private int reboundSlot = -1;
    private int originalSlot = -1;

    /**
     * 攻击键最近被按过（还会保持几个 tick，让快速点按也算数）。
     * 用来区分「玩家切到目标槽是秒切造成的」还是「他自己手动切过去的」——
     * 不分清的话，玩家滚轮/按数字键切到目标槽会被立刻弹回去。
     */
    private int attackHoldTicks;

    @Override
    public String id() {
        return "swap";
    }

    @Override
    public String displayName() {
        return "秒切";
    }

    @Override
    public boolean isEnabled() {
        return CombatConfig.swapEnabled;
    }

    @Override
    public void setEnabled(boolean value) {
        CombatConfig.swapEnabled = value;
    }

    /**
     * 按数据组件识别武器，不硬编码物品 id（模组武器也能认）。
     *
     * <p>⚠️ 重锤用物品判断：实测日志确认 {@code KINETIC_WEAPON} 实际挂在长矛上。
     */
    private static Kind kindOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        if (stack.isOf(Items.MACE)) {
            return Kind.MACE;
        }
        if (stack.get(DataComponentTypes.KINETIC_WEAPON) != null
                || stack.get(DataComponentTypes.PIERCING_WEAPON) != null) {
            return Kind.SPEAR;
        }
        WeaponComponent weapon = stack.get(DataComponentTypes.WEAPON);
        if (weapon != null && weapon.disableBlockingForSeconds() > 0.0F) {
            return Kind.AXE;
        }
        return null;
    }

    /** 是不是"能当基础武器用"的东西（剑/斧/锤/矛）。 */
    private static boolean isWeapon(ItemStack stack) {
        return kindOf(stack) != null
                || (!stack.isEmpty() && stack.get(DataComponentTypes.WEAPON) != null);
    }

    private static boolean hasLunge(ItemStack stack) {
        for (var entry : stack.getEnchantments().getEnchantments()) {
            if (LUNGE_ID.equals(entry.getIdAsString())) {
                return true;
            }
        }
        return false;
    }

    private static int findSlot(PlayerInventory inventory, Kind kind) {
        for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
            if (kindOf(inventory.getStack(i)) == kind) {
                return i;
            }
        }
        return -1;
    }

    private static int findLungeSpear(PlayerInventory inventory) {
        for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (kindOf(stack) == Kind.SPEAR && hasLunge(stack)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void onClientTick(MinecraftClient client) {
        if (!CombatConfig.swapEnabled || client.player == null || client.currentScreen != null) {
            restoreKeys(client);
            return;
        }
        ClientPlayerEntity player = client.player;
        PlayerInventory inventory = player.getInventory();

        if (client.options.attackKey.isPressed()) {
            attackHoldTicks = 4;
        } else if (attackHoldTicks > 0) {
            attackHoldTicks--;
        }

        // ① 带「突进」的长矛：不看准星，但【手持其他武器时不切】
        //    否则一按左键就被抢去长矛，其他武器没法正常用。
        //    只在空手 / 拿着非武器（方块、杂物等）时才放开。
        int lunge = findLungeSpear(inventory);
        if (lunge >= 0 && !isWeapon(inventory.getStack(inventory.getSelectedSlot()))) {
            arm(client, player, lunge, "长矛·突进");
            return;
        }

        // ② 其余走规则表
        Entity target = client.crosshairTarget instanceof EntityHitResult hit ? hit.getEntity() : null;
        RuleTable.Action action = RuleTable.firstMatch(player, target);
        Kind wanted = action == null ? null : switch (action) {
            case AXE -> Kind.AXE;
            case MACE -> Kind.MACE;
            case SPEAR -> Kind.SPEAR;
            case NONE -> null;
        };

        // 准星门槛：斧头和重锤的作用对象必须真的存在。
        // 长矛豁免（它走穿透攻击路径，doAttack 拿到就 return，crosshairTarget 不参与）。
        if (wanted != null && wanted != Kind.SPEAR && target == null) {
            wanted = null;
        }
        // 没突进的长矛：只为增加攻击距离，必须手持武器才切
        //（空手切过去没有基础攻击力可继承，没意义）
        if (wanted == Kind.SPEAR && !isWeapon(inventory.getStack(inventory.getSelectedSlot()))) {
            wanted = null;
        }

        int slot = wanted == null ? -1 : findSlot(inventory, wanted);
        if (slot < 0) {
            restoreKeys(client);
            return;
        }
        arm(client, player, slot, action == null ? "?" : action.label);
    }

    /** 把目标槽武装成攻击键，并把玩家切回原槽（如果已经打过了）。 */
    private void arm(MinecraftClient client, ClientPlayerEntity player, int slot, String reason) {
        PlayerInventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();

        if (selected == slot) {
            // 玩家已经在目标槽上。只有确认是【秒切】把他带过来的才切回原槽 ——
            // 否则玩家自己滚轮/按数字键切过来也会被立刻弹回去，等于手动切不过去。
            // ⚠️ 键位【保持武装】：解除到重新武装之间有个空窗，
            // 玩家正好在那时按攻击就不会触发（表现为"有时候触发有时候不触发"）。
            if (attackHoldTicks > 0 && originalSlot >= 0 && originalSlot != slot) {
                inventory.setSelectedSlot(originalSlot);
                if (client.interactionManager != null) {
                    ((ClientPlayerInteractionManagerInvoker) client.interactionManager)
                            .combatassist$syncSelectedSlot();
                }
                CombatAssistClient.debug("[CombatAssist] 秒切 打完，切回槽{}（键位保持武装）",
                        originalSlot + 1);
                originalSlot = -1;
            }
            return;
        }

        // 玩家还在原武器上 —— 记下这一格。
        // ⚠️ 必须每 tick 刷新：只记一次的话，之后你换了手持位置，它还会把你切回
        // 那个过期值（表现为"回到一个固定的、像是缓存过的槽位"）。
        int keep = selected;

        if (reboundSlot == slot) {
            originalSlot = keep;
            return;
        }
        restoreKeys(client);
        KeyBinding[] hotbar = client.options.hotbarKeys;
        if (savedKeys[slot] == null) {
            savedKeys[slot] = hotbar[slot].getBoundKeyTranslationKey();
        }
        hotbar[slot].setBoundKey(client.options.attackKey.getDefaultKey());
        // 必须重建按键查询表：setBoundKey 只改了绑定字段，而按键事件是按
        // 「键 → 绑定」的静态表分发的，不重建的话新绑定的键收不到任何事件。
        KeyBinding.updateKeysByCode();
        originalSlot = keep;
        reboundSlot = slot;
        CombatAssistClient.debug("[CombatAssist] 秒切 把槽{}的键位改成了攻击键  ({})",
                slot + 1, reason);
    }

    /** 把改过的键位全部还原，并把玩家切回原槽。 */
    private void restoreKeys(MinecraftClient client) {
        if (reboundSlot < 0) {
            return;
        }
        int hold = reboundSlot;
        KeyBinding[] hotbar = client.options.hotbarKeys;
        for (int i = 0; i < savedKeys.length; i++) {
            if (savedKeys[i] != null) {
                hotbar[i].setBoundKey(InputUtil.fromTranslationKey(savedKeys[i]));
                savedKeys[i] = null;
            }
        }
        KeyBinding.updateKeysByCode();
        reboundSlot = -1;
        if (originalSlot >= 0 && client.player != null
                && client.player.getInventory().getSelectedSlot() == hold) {
            client.player.getInventory().setSelectedSlot(originalSlot);
            if (client.interactionManager != null) {
                ((ClientPlayerInteractionManagerInvoker) client.interactionManager)
                        .combatassist$syncSelectedSlot();
            }
        }
        CombatAssistClient.debug("[CombatAssist] 秒切 键位已还原（槽{}）", hold + 1);
        originalSlot = -1;
    }

    @Override
    public String[] hudLines() {
        return new String[]{"秒切  " + (reboundSlot < 0 ? "待命" : "武装槽" + (reboundSlot + 1))};
    }
}
