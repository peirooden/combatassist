package com.combatassist.module.rule;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用「条件 → 动作」规则表。列表有序，**先命中先赢**，所以顺序就是优先级。
 *
 * <p>故意做成公共能力而不是塞进秒切模块：以后别的辅助要判断「对面在举盾 / 我在下落」
 * 可以直接复用同一套条件和界面。
 */
public final class RuleTable {

    public enum Condition {
        TARGET_BLOCKING("目标举盾", 0.0D, new double[]{0.0D}),
        FALL("下落高度", 1.5D, new double[]{0.0D, 1.0D, 1.5D, 2.0D, 3.0D, 5.0D}),
        DISTANCE("目标距离", 3.0D, new double[]{2.0D, 3.0D, 3.5D, 4.0D, 4.5D, 6.0D}),
        TARGET_HEALTH("目标血量", 10.0D, new double[]{2.0D, 4.0D, 6.0D, 10.0D, 14.0D, 20.0D}),
        SELF_HEALTH("自身血量", 10.0D, new double[]{2.0D, 4.0D, 6.0D, 10.0D, 14.0D, 20.0D}),
        ALWAYS("总是", 0.0D, new double[]{0.0D});

        public final String label;
        public final double defaultThreshold;
        public final double[] steps;

        Condition(String label, double defaultThreshold, double[] steps) {
            this.label = label;
            this.defaultThreshold = defaultThreshold;
            this.steps = steps;
        }

        public boolean usesThreshold() {
            return this != TARGET_BLOCKING && this != ALWAYS;
        }
    }

    public enum Action {
        NONE("不切"),
        AXE("斧头"),
        MACE("重锤"),
        SPEAR("长矛");

        public final String label;

        Action(String label) {
            this.label = label;
        }
    }

    public static final class Rule {
        public Condition condition;
        public double threshold;
        public Action action;
        public boolean enabled;

        public Rule(Condition condition, double threshold, Action action, boolean enabled) {
            this.condition = condition;
            this.threshold = threshold;
            this.action = action;
            this.enabled = enabled;
        }
    }

    private static final List<Rule> RULES = new ArrayList<>();

    static {
        resetToDefaults();
    }

    private RuleTable() {
    }

    /** 默认顺序按「机会窗口的短暂程度」排：举盾和下落都是一次性机会，长矛是无条件兜底、排最后。 */
    public static void resetToDefaults() {
        RULES.clear();
        RULES.add(new Rule(Condition.TARGET_BLOCKING, 0.0D, Action.AXE, true));
        RULES.add(new Rule(Condition.FALL, 1.5D, Action.MACE, true));
        RULES.add(new Rule(Condition.ALWAYS, 0.0D, Action.SPEAR, true));
    }

    public static List<Rule> rules() {
        return RULES;
    }

    /**
     * 判定单条规则。
     *
     * <p>⚠️ {@code target} <b>可以是 null</b> —— 在 {@code doAttack} 的 HEAD 判定时准星还是
     * 上一帧的值，够不着目标时就是 MISS，拿不到实体。凡是需要目标的条件都必须先判空。
     */
    public static boolean test(Condition condition, PlayerEntity player, Entity target, double threshold) {
        if (target == null) {
            return condition == Condition.ALWAYS
                    || condition == Condition.FALL
                    || condition == Condition.SELF_HEALTH;
        }
        return switch (condition) {
            case TARGET_BLOCKING -> target instanceof LivingEntity living && living.isBlocking();
            case FALL -> player.fallDistance >= threshold;
            case DISTANCE -> player.distanceTo(target) >= threshold;
            case TARGET_HEALTH -> target instanceof LivingEntity living && living.getHealth() <= threshold;
            case SELF_HEALTH -> player.getHealth() <= threshold;
            case ALWAYS -> true;
        };
    }

    /** 第一条命中的规则的动作；没有命中的返回 null。 */
    public static Action firstMatch(PlayerEntity player, Entity target) {
        for (Rule rule : RULES) {
            if (rule.enabled && rule.action != Action.NONE
                    && test(rule.condition, player, target, rule.threshold)) {
                return rule.action;
            }
        }
        return null;
    }

    /** 规则表存成一行文本：条件:阈值:动作:开关，用 ; 分隔。 */
    public static String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Rule rule : RULES) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(rule.condition.name()).append(':')
                    .append(rule.threshold).append(':')
                    .append(rule.action.name()).append(':')
                    .append(rule.enabled ? '1' : '0');
        }
        return sb.toString();
    }

    /** 解析失败就保留默认规则表，绝不半途生效。 */
    public static boolean parse(String raw) {
        if (raw == null || raw.isBlank()) {
            resetToDefaults();
            return true;
        }
        List<Rule> parsed = new ArrayList<>();
        try {
            for (String chunk : raw.split(";")) {
                if (chunk.isBlank()) {
                    continue;
                }
                String[] parts = chunk.split(":");
                if (parts.length != 4) {
                    return false;
                }
                parsed.add(new Rule(
                        Condition.valueOf(parts[0]),
                        Double.parseDouble(parts[1]),
                        Action.valueOf(parts[2]),
                        "1".equals(parts[3])));
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (parsed.isEmpty()) {
            return false;
        }
        RULES.clear();
        RULES.addAll(parsed);
        return true;
    }

    /** 在档位表里取下一个值；当前值不在表里就取第一个比它大的。 */
    public static double nextStep(double[] steps, double current) {
        for (int i = 0; i < steps.length; i++) {
            if (Math.abs(steps[i] - current) < 1.0E-6D) {
                return steps[(i + 1) % steps.length];
            }
        }
        for (double step : steps) {
            if (step > current) {
                return step;
            }
        }
        return steps[0];
    }
}
