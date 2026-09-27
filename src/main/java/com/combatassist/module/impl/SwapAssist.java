package com.combatassist.module.impl;

import com.combatassist.CombatAssistClient;
import com.combatassist.Edition;
import com.combatassist.config.CombatConfig;
import com.combatassist.mixin.ClientPlayerInteractionManagerInvoker;
import com.combatassist.module.CombatModule;
import com.combatassist.module.rule.PriorityTable;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WeaponComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * 秒切：不改攻击流程，只<b>动态改键位</b>。
 *
 * <p>条件满足时把目标槽位的按键改成攻击键 —— 玩家按下攻击键时，
 * <b>原版自己的按键系统</b>会先切到目标槽，再执行攻击。换槽包由原版发，
 * 和真人按数字键无法区分。
 *
 * <p>选哪把由 {@link PriorityTable} 决定：当前情境 + 候选武器自身的属性/附魔，
 * 取分最高的一把。
 */
public class SwapAssist implements CombatModule {

    private static final String LUNGE_ID = "minecraft:lunge";
    private static final String BREACH_ID = "minecraft:breach";
    private static final String DENSITY_ID = "minecraft:density";

    /** 这些附魔直接帮击杀。 */
    private static final String[] TIER_GAIN = {
            "minecraft:sharpness", "minecraft:smite", "minecraft:bane_of_arthropods",
            "minecraft:density", "minecraft:breach", "minecraft:lunge",
            "minecraft:sweeping_edge", "minecraft:impaling"};
    /** 这些对击杀帮助不大，但比什么都不加的略强。 */
    private static final String[] TIER_LOW = {"minecraft:knockback", "minecraft:fire_aspect"};

    /** 硬性排除（不是分数低，是这次根本不该选它）。 */
    private static final int EXCLUDED = Integer.MIN_VALUE;

    /** 被改过键位的槽，记下原键位以便还原。 */
    private final String[] savedKeys = new String[9];

    private int reboundSlot = -1;
    private int originalSlot = -1;

    /** 破盾后等盾掉下来的窗口（服务端破盾 → 客户端同步有一段延迟）。 */
    private static final int SHIELD_WATCH_TICKS = 20;

    /** 盾破了之后「空中·盾刚破」这一行的有效期（用户定的 1 秒）。 */
    private static final int SHIELD_BROKEN_TICKS = 20;

    private int shieldWatchTicks;
    private int shieldBrokenTicks;
    private Entity shieldTarget;

    /** 上一 tick 手持的槽 —— 用来认出"键位刚把玩家挪到武装槽"（= 他按下了攻击键）。 */
    private int lastSelected = -1;

    /** 当前武装槽是哪一类武器（只有斧头那次要接"破盾→自动切重锤"）。 */
    private PriorityTable.Kind armedKind;

    /** 调试 HUD 用。 */
    private String situationLabel = "-";

    /** 按攻击键的上升沿检测 + 每次"按下攻击"打一行的诊断内容。 */
    private boolean attackHeld;
    private String lastDetail = "-";

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
     * 空槽算「空手」（也是候选之一）；方块、食物这类非武器返回 null（不参与排序）。
     */
    private static PriorityTable.Kind kindOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return PriorityTable.Kind.EMPTY;
        }
        if (stack.isOf(Items.MACE)) {
            return PriorityTable.Kind.MACE;
        }
        if (stack.get(DataComponentTypes.KINETIC_WEAPON) != null
                || stack.get(DataComponentTypes.PIERCING_WEAPON) != null) {
            return PriorityTable.Kind.SPEAR;
        }
        WeaponComponent weapon = stack.get(DataComponentTypes.WEAPON);
        if (weapon == null) {
            return null;
        }
        return weapon.disableBlockingForSeconds() > 0.0F
                ? PriorityTable.Kind.AXE : PriorityTable.Kind.SWORD;
    }

    private static boolean hasEnchant(ItemStack stack, String id) {
        for (var entry : stack.getEnchantments().getEnchantments()) {
            if (id.equals(entry.getIdAsString())) {
                return true;
            }
        }
        return false;
    }

    /** 附魔分级：2 = 增益，1 = 对击杀帮助小，0 = 中性（或没附魔）。同分时用。 */
    private static int enchantTier(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        int tier = 0;
        for (var entry : stack.getEnchantments().getEnchantments()) {
            String id = entry.getIdAsString();
            for (String gain : TIER_GAIN) {
                if (gain.equals(id)) {
                    return 2;
                }
            }
            for (String low : TIER_LOW) {
                if (low.equals(id)) {
                    tier = 1;
                }
            }
        }
        return tier;
    }

    /** 手持是不是武器（不是空手、也不是方块杂物）。 */
    private static boolean holdingWeapon(PlayerEntity player) {
        PriorityTable.Kind kind = kindOf(player.getMainHandStack());
        return kind != null && kind != PriorityTable.Kind.EMPTY;
    }

    /**
     * 候选武器自身的修正分（读物品数据算，不写死）。
     *
     * @return 修正值；{@link #EXCLUDED} = 这次不该选它
     */
    private static int modifier(PriorityTable.Kind kind, ItemStack stack, PlayerEntity player,
                                PriorityTable.Situation situation) {
        int mod = 0;
        switch (kind) {
            case MACE -> {
                if (situation == PriorityTable.Situation.TARGET_ARMORED
                        || situation == PriorityTable.Situation.SHIELD_BROKEN_AIR) {
                    // 破甲重锤在这两个情境额外加分；普通重锤反而降下来（用户：破甲锤才加分）
                    mod += hasEnchant(stack, BREACH_ID) ? 20 : -30;
                }
                if (situation == PriorityTable.Situation.FALLING && hasEnchant(stack, DENSITY_ID)) {
                    mod += 10;
                }
            }
            case SPEAR -> {
                boolean lunge = hasEnchant(stack, LUNGE_ID);
                boolean weapon = holdingWeapon(player);
                // 突进矛：只在空手/拿着非武器时切（拿着别的武器时切过去会抢走攻击）；
                // 没突进的矛：只为加攻击距离，必须手持是武器才切（空手切过去没基础攻击力）
                if (lunge == weapon) {
                    return EXCLUDED;
                }
                if (lunge) {
                    mod += 20;
                }
            }
            default -> {
            }
        }
        return mod;
    }

    /**
     * 选出这一 tick 该切到的槽。
     *
     * <p>分 = (情境分 + 武器修正) × 手持权重；取最高，同分看附魔分级。
     *
     * @return 目标槽；-1 = 不需要切
     */
    private int chooseSlot(ClientPlayerEntity player, PlayerInventory inventory, Entity target,
                           Entity inFront, boolean airborne) {
        PriorityTable.Situation situation =
                PriorityTable.situation(player, target, inFront, shieldBrokenTicks, airborne);
        situationLabel = situation.label;

        PriorityTable.Kind held = kindOf(inventory.getStack(inventory.getSelectedSlot()));
        if (held == null) {
            held = PriorityTable.Kind.EMPTY;
        }

        int bestSlot = -1;
        int bestScore = 0;
        int bestTier = -1;
        StringBuilder detail = CombatConfig.debugLog && Edition.DEV ? new StringBuilder() : null;
        for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
            ItemStack stack = inventory.getStack(i);
            PriorityTable.Kind kind = kindOf(stack);
            if (kind == null) {
                continue;
            }
            int mod = modifier(kind, stack, player, situation);
            int score = mod == EXCLUDED ? 0
                    : (PriorityTable.score(situation, kind) + mod)
                            * PriorityTable.weight(held, kind);
            if (detail != null && kind != PriorityTable.Kind.EMPTY) {
                detail.append(' ').append(kind.label).append('=').append(score);
            }
            if (mod == EXCLUDED || score <= 0) {
                continue;
            }
            int tier = enchantTier(stack);
            if (score > bestScore || (score == bestScore && tier > bestTier)) {
                bestScore = score;
                bestTier = tier;
                bestSlot = i;
            }
        }
        // 手上就是最高分那把 = 不需要切
        int result = bestSlot == inventory.getSelectedSlot() ? -1 : bestSlot;
        if (detail != null) {
            detail.insert(0, "[" + situation.label + "] 瞄=" + (target == null ? "无" : "有")
                    + " 前=" + (inFront == null ? "无" : "有")
                    + " fall=" + Math.round(player.fallDistance * 100.0D) / 100.0D
                    + " 地面=" + player.isOnGround()
                    + " → " + (result < 0 ? "不切" : "切槽" + (result + 1)) + " |");
            lastDetail = detail.toString();
        }
        return result;
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
        if (shieldBrokenTicks > 0) {
            shieldBrokenTicks--;
        }

        // 那个槽位自己的键被我们顶掉了，按下去收不到任何事件。这里直读键盘状态，
        // 玩家一按就把键位还给他并切过去 —— 否则他永远切不进这个槽位。
        if (manualSlotKeyPressed(client)) {
            int manual = reboundSlot;
            restoreKeys(client, false);
            selectSlot(client, manual);
            return;
        }

        // ★ 破盾的后续：盾一掉就自动把重锤切到手上（只有破盾是这样）。
        if (handleShieldBreak(client, inventory, selected, previous)) {
            return;
        }

        Entity target = looseAimTarget(client, player);
        Entity inFront = nearbyTarget(player);
        boolean airborne = !player.isOnGround();
        int slot = chooseSlot(player, inventory, target, inFront, airborne);

        boolean pressed = client.options.attackKey.isPressed();
        if (pressed && !attackHeld) {
            CombatAssistClient.debug("[CombatAssist] 秒切 按下攻击 {}", lastDetail);
        }
        attackHeld = pressed;

        if (slot < 0) {
            restoreKeys(client, true);
            return;
        }
        arm(client, player, slot, kindOf(inventory.getStack(slot)));
    }

    /**
     * 「大致瞄着某个生物」。
     *
     * <p>比原版 {@code crosshairTarget} 宽一点：原版准星是<b>渲染帧</b>按<b>当时手持武器</b>的射程
     * 算出来的，手持切换 / 帧率都会让它比实际攻击判定更早失效 —— 表现为"明明打到了却没秒切"。
     * 这里用同一根视线射线，把盒子放大、射程加长一点再问一次。
     */
    private static Entity looseAimTarget(MinecraftClient client, ClientPlayerEntity player) {
        if (client.crosshairTarget instanceof EntityHitResult hit) {
            return hit.getEntity();
        }
        double range = player.getEntityInteractionRange() + 1.5D;
        Vec3d start = player.getEyePos();
        Vec3d dir = player.getRotationVec(1.0F);
        Vec3d end = start.add(dir.multiply(range));
        Box box = player.getBoundingBox().stretch(dir.multiply(range)).expand(1.0D);
        EntityHitResult hit = ProjectileUtil.raycast(player, start, end, box,
                e -> !e.isSpectator() && e.canHit(), range * range);
        return hit == null ? null : hit.getEntity();
    }

    /**
     * 前方<b>一大片范围</b>里有没有人 —— 给「下落中 / 空中」两行用。
     *
     * <p>准星判定太严：用户实测"跳着打人经常不切"，日志里能看到
     * {@code [其他] 目标=无 fall=2.99 地面=false → 不切}。这里换成一根又长又粗的射线，
     * 只要人在正前方大概方向就算数。
     */
    private static Entity nearbyTarget(ClientPlayerEntity player) {
        double range = 6.0D;
        Vec3d start = player.getEyePos();
        Vec3d dir = player.getRotationVec(1.0F);
        Vec3d end = start.add(dir.multiply(range));
        Box box = player.getBoundingBox().stretch(dir.multiply(range)).expand(2.0D);
        EntityHitResult hit = ProjectileUtil.raycast(player, start, end, box,
                e -> !e.isSpectator() && e.canHit(), range * range);
        return hit == null ? null : hit.getEntity();
    }

    /**
     * 破盾的后续：<b>盾一破就自动把重锤切到手上</b>（不用玩家再按一下 ——
     * 那一下是"按攻击时键位才把他换过去"，用户明确不要那种）。
     *
     * <p>触发条件是<b>目标真的从举盾变成没举盾</b>，不是"斧头挥了一下"：挥空/够不着时
     * 盾不会掉，也就不会切。
     *
     * <p>**换过去就不回收**（用户要求）：键位全还给玩家、不留"回切"目标 ——
     * 之后什么时候切回去由玩家自己决定。
     *
     * @return true = 这一 tick 已经把重锤切上来了，别再走优先级表
     */
    private boolean handleShieldBreak(MinecraftClient client, PlayerInventory inventory,
                                      int selected, int previous) {
        // ① 键位这一 tick 把玩家挪到了武装槽，而且那把是斧头 → 他刚用斧头打了一下举盾的人
        if (armedKind == PriorityTable.Kind.AXE && reboundSlot >= 0 && selected == reboundSlot
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
        shieldBrokenTicks = SHIELD_BROKEN_TICKS;
        int mace = -1;
        for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
            if (kindOf(inventory.getStack(i)) == PriorityTable.Kind.MACE) {
                mace = i;
                break;
            }
        }
        if (mace < 0 || mace == selected) {
            return false;
        }
        // 换过去就不管了：键位全部还给玩家，也不留"回切"目标 ——
        // 手上已经是重锤，之后什么时候切回去由玩家自己决定。
        restoreKeys(client, false);
        armedKind = null;
        selectSlot(client, mace);
        CombatAssistClient.debug("[CombatAssist] 秒切 盾破了 → 自动切到重锤（槽{}），之后不再干预",
                mace + 1);
        return true;
    }

    /** 把目标槽武装成攻击键。 */
    private void arm(MinecraftClient client, ClientPlayerEntity player, int slot,
                     PriorityTable.Kind kind) {
        PlayerInventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        armedKind = kind;

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

        // 换一把武器：玩家如果正停在我们武装的那个槽上，说明上一把已经打出去了 ——
        // 起点沿用上一次的，中间不弹回主手。
        int carriedOrigin = (reboundSlot >= 0 && selected == reboundSlot && attackHoldTicks > 0)
                ? originalSlot : -1;
        restoreKeys(client, false);
        int keep = carriedOrigin >= 0 ? carriedOrigin : inventory.getSelectedSlot();
        bindKey(client, slot);
        originalSlot = keep;
        CombatAssistClient.debug("[CombatAssist] 秒切 [{}] 把槽{}的键位改成了攻击键  ({})",
                situationLabel, slot + 1, kind.label);
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
        String state = reboundSlot < 0 ? "待命" : "武装槽" + (reboundSlot + 1);
        return new String[]{"秒切  " + situationLabel + " / " + state};
    }
}
