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
import net.minecraft.entity.LivingEntity;
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

    /** 破盾后等盾掉下来的窗口（服务端破盾 → 客户端同步有一段延迟）。 */
    private static final int SHIELD_WATCH_TICKS = 20;

    private int shieldWatchTicks;
    private Entity shieldTarget;

    /** 上一 tick 手持的槽 —— 用来认出"键位刚把玩家挪到武装槽"（= 他按下了攻击键）。 */
    private int lastSelected = -1;

    /** 当前武装槽是被哪条规则武装的（只有斧头那条要接"破盾→自动切重锤"）。 */
    private RuleTable.Action armedAction;

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
            restoreKeys(client, false);
            lastSelected = -1;
            return;
        }
        ClientPlayerEntity player = client.player;
        PlayerInventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        int previous = lastSelected;
        lastSelected = selected;

        if (client.options.attackKey.isPressed()) {
            attackHoldTicks = 4;
        } else if (attackHoldTicks > 0) {
            attackHoldTicks--;
        }

        // 那个槽位自己的键被我们顶掉了，按下去收不到任何事件。这里直读键盘状态，
        // 玩家一按就把键位还给他并切过去 —— 否则他永远切不进这个槽位。
        if (manualSlotKeyPressed(client)) {
            int manual = reboundSlot;
            restoreKeys(client, false);
            selectSlot(client, manual);
            return;
        }

        // ★ 破盾的后续：盾一掉就自动把重锤切到手上（只有这一条规则是这样）。
        if (handleShieldBreak(client, inventory, selected, previous)) {
            return;
        }

        // ① 带「突进」的长矛：不看准星，但【手持其他武器时不切】
        //    否则一按左键就被抢去长矛，其他武器没法正常用。
        //    只在空手 / 拿着非武器（方块、杂物等）时才放开。
        int lunge = findLungeSpear(inventory);
        if (lunge >= 0 && !isWeapon(inventory.getStack(inventory.getSelectedSlot()))) {
            arm(client, player, lunge, RuleTable.Action.SPEAR, "长矛·突进");
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
            restoreKeys(client, true);
            return;
        }
        arm(client, player, slot, action, action == null ? "?" : action.label);
    }

    /**
     * 破盾的后续：<b>盾一破就自动把重锤切到手上</b>（不用玩家再按一下 ——
     * 那一下是"按攻击时键位才把他换过去"，用户明确不要那种）。
     *
     * <p>触发条件是<b>目标真的从举盾变成没举盾</b>，不是"斧头挥了一下"：挥空/够不着时
     * 盾不会掉，也就不会切。只有「目标举盾→斧头」这一条规则有这个待遇，
     * 其余规则仍然是"武装键位、等玩家按"。
     *
     * <p>**换过去就不回收**（用户要求）：键位全还给玩家、不留"回切"目标 ——
     * 之后什么时候切回去由玩家自己决定。
     *
     * @return true = 这一 tick 已经把重锤切上来了，别再走规则表
     */
    private boolean handleShieldBreak(MinecraftClient client, PlayerInventory inventory,
                                      int selected, int previous) {
        // ① 键位这一 tick 把玩家挪到了武装槽，而且那把是斧头 → 他刚用斧头打了一下举盾的人
        if (armedAction == RuleTable.Action.AXE && reboundSlot >= 0 && selected == reboundSlot
                && previous >= 0 && previous != reboundSlot && attackHoldTicks > 0) {
            shieldTarget = client.crosshairTarget instanceof EntityHitResult hit ? hit.getEntity() : null;
            shieldWatchTicks = SHIELD_WATCH_TICKS;
        }
        if (shieldWatchTicks <= 0) {
            return false;
        }
        shieldWatchTicks--;

        // ② 等盾掉下来（服务端破盾后会把目标的"使用物品"清掉，客户端同步得到）
        if (!(shieldTarget instanceof LivingEntity living) || living.isBlocking()) {
            return false;
        }
        shieldWatchTicks = 0;
        int mace = findSlot(inventory, Kind.MACE);
        if (mace < 0 || mace == selected) {
            return false;
        }
        // 换过去就不管了：键位全部还给玩家，也不留"回切"目标 ——
        // 手上已经是重锤，之后什么时候切回去由玩家自己决定。
        restoreKeys(client, false);
        armedAction = null;
        selectSlot(client, mace);
        CombatAssistClient.debug("[CombatAssist] 秒切 盾破了 → 自动切到重锤（槽{}），之后不再干预",
                mace + 1);
        return true;
    }

    /** 把目标槽武装成攻击键。 */
    private void arm(MinecraftClient client, ClientPlayerEntity player, int slot,
                     RuleTable.Action action, String reason) {
        PlayerInventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        armedAction = action;

        if (selected == slot) {
            // 玩家已经在目标槽上。
            if (attackHoldTicks > 0 && originalSlot >= 0 && originalSlot != slot) {
                // 是【秒切】刚把他切过来的（他正按着攻击键）→ 打完切回原槽，键位保持武装。
                selectSlot(client, originalSlot);
                CombatAssistClient.debug("[CombatAssist] 秒切 打完，切回槽{}（键位保持武装）",
                        originalSlot + 1);
                originalSlot = -1;
                return;
            }
            // 他是自己切过来的 → 把键位还给他，否则他按数字键回不到这个槽位。
            restoreKeys(client, false);
            return;
        }

        if (reboundSlot == slot) {
            // 玩家还在原武器上 —— 记下这一格。
            // ⚠️ 必须每 tick 刷新：只记一次的话，之后你换了手持位置，它还会把你切回
            // 那个过期值（表现为"回到一个固定的、像是缓存过的槽位"）。
            originalSlot = selected;
            return;
        }

        // 换一把武器（例如斧头破完盾 → 重锤）：玩家如果正停在我们武装的那个槽上，
        // 说明上一把已经打出去了 —— 起点沿用上一次的，中间不弹回主手，
        // 打完最后一把也还能回到真正的主手。
        int carriedOrigin = (reboundSlot >= 0 && selected == reboundSlot && attackHoldTicks > 0)
                ? originalSlot : -1;
        restoreKeys(client, false);
        int keep = carriedOrigin >= 0 ? carriedOrigin : inventory.getSelectedSlot();
        bindKey(client, slot);
        originalSlot = keep;
        CombatAssistClient.debug("[CombatAssist] 秒切 把槽{}的键位改成了攻击键  ({})",
                slot + 1, reason);
    }

    /** 把某个槽的键位改成攻击键（键位互换的引擎）。 */
    private void bindKey(MinecraftClient client, int slot) {
        if (savedKeys[slot] == null) {
            savedKeys[slot] = client.options.hotbarKeys[slot].getBoundKeyTranslationKey();
        }
        client.options.hotbarKeys[slot].setBoundKey(client.options.attackKey.getDefaultKey());
        // 必须重建按键查询表：setBoundKey 只改了绑定字段，而按键事件是按
        // 「键 → 绑定」的静态表分发的，不重建的话新绑定的键收不到任何事件。
        KeyBinding.updateKeysByCode();
        reboundSlot = slot;
    }

    /**
     * 还原所有被改过的键位。
     *
     * <p>{@code maySwitchBack} = false 时<b>绝不动玩家当前槽位</b>（关模块、开界面、
     * 把键位还给玩家时用）。只有 true 且「玩家停在我们武装的那个槽上 + 刚按过攻击键」
     * 才认得出是秒切把他切过去的 —— 玩家自己滚轮/数字键切过去的绝不能弹回去。
     */
    private void restoreKeys(MinecraftClient client, boolean maySwitchBack) {
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
        if (maySwitchBack && attackHoldTicks > 0 && originalSlot >= 0
                && client.player != null
                && client.player.getInventory().getSelectedSlot() == hold) {
            selectSlot(client, originalSlot);
            CombatAssistClient.debug("[CombatAssist] 秒切 打完，切回槽{}", originalSlot + 1);
        }
        originalSlot = -1;
    }

    /** 切槽并把新槽位同步给服务端（走原版方法，不自己发包）。 */
    private static void selectSlot(MinecraftClient client, int slot) {
        if (client.player == null) {
            return;
        }
        client.player.getInventory().setSelectedSlot(slot);
        if (client.interactionManager != null) {
            ((ClientPlayerInteractionManagerInvoker) client.interactionManager)
                    .combatassist$syncSelectedSlot();
        }
    }

    /** 玩家按下了被我们顶掉的那个原始键位（直读键盘，因为原键位收不到事件）。 */
    private boolean manualSlotKeyPressed(MinecraftClient client) {
        if (reboundSlot < 0 || savedKeys[reboundSlot] == null) {
            return false;
        }
        InputUtil.Key key = InputUtil.fromTranslationKey(savedKeys[reboundSlot]);
        return key.getCategory() == InputUtil.Type.KEYSYM && key.getCode() >= 0
                && InputUtil.isKeyPressed(client.getWindow(), key.getCode());
    }

    @Override
    public String[] hudLines() {
        return new String[]{"秒切  " + (reboundSlot < 0 ? "待命" : "武装槽" + (reboundSlot + 1))};
    }
}
