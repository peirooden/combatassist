package com.combatassist.compat;

import com.combatassist.config.CombatConfig;
import com.combatassist.module.rule.RuleTable;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * 秒切规则表：一行一条规则，四格都能点。
 *
 * <p>列表有序，先命中先赢 —— <b>行号就是优先级</b>。
 *
 * <p>1.21.9+ 的 Screen 框架自己会画背景，不要再调 renderBackground。
 */
public class RuleTableScreen extends Screen {

    private static final int ROW_H = 20;
    private static final int GAP = 24;
    private static final int W_COND = 72;
    private static final int W_NUM = 56;
    private static final int W_ACT = 56;
    private static final int W_ON = 48;
    private static final int SPACING = 4;

    private final Screen parent;

    public RuleTableScreen(Screen parent) {
        super(Text.literal("秒切规则表"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int total = W_COND + W_NUM + W_ACT + W_ON + SPACING * 3;
        int x0 = this.width / 2 - total / 2;
        int top = Math.max(40, this.height / 2 - 70);

        var rules = RuleTable.rules();
        for (int i = 0; i < rules.size(); i++) {
            RuleTable.Rule rule = rules.get(i);
            int y = top + i * GAP;

            addDrawableChild(ButtonWidget.builder(Text.literal(rule.condition.label), b -> {
                int next = (rule.condition.ordinal() + 1) % RuleTable.Condition.values().length;
                rule.condition = RuleTable.Condition.values()[next];
                rule.threshold = rule.condition.defaultThreshold;
                save();
                this.clearAndInit();
            }).dimensions(x0, y, W_COND, ROW_H).build());

            if (rule.condition.usesThreshold()) {
                addDrawableChild(ButtonWidget.builder(
                        Text.literal(String.format(Locale.ROOT, "%.1f", rule.threshold)), b -> {
                            rule.threshold = RuleTable.nextStep(rule.condition.steps, rule.threshold);
                            save();
                            b.setMessage(Text.literal(String.format(Locale.ROOT, "%.1f", rule.threshold)));
                        }).dimensions(x0 + W_COND + SPACING, y, W_NUM, ROW_H).build());
            }

            addDrawableChild(ButtonWidget.builder(Text.literal(rule.action.label), b -> {
                int next = (rule.action.ordinal() + 1) % RuleTable.Action.values().length;
                rule.action = RuleTable.Action.values()[next];
                save();
                b.setMessage(Text.literal(rule.action.label));
            }).dimensions(x0 + W_COND + W_NUM + SPACING * 2, y, W_ACT, ROW_H).build());

            addDrawableChild(ButtonWidget.builder(Text.literal(rule.enabled ? "开" : "关"), b -> {
                rule.enabled = !rule.enabled;
                save();
                b.setMessage(Text.literal(rule.enabled ? "开" : "关"));
            }).dimensions(x0 + W_COND + W_NUM + W_ACT + SPACING * 3, y, W_ON, ROW_H).build());
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("恢复默认"), b -> {
            RuleTable.resetToDefaults();
            save();
            this.clearAndInit();
        }).dimensions(this.width / 2 - 78, top + rules.size() * GAP + 8, 76, ROW_H).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("完成"), b -> this.close())
                .dimensions(this.width / 2 + 2, top + rules.size() * GAP + 8, 76, ROW_H).build());
    }

    private static void save() {
        CombatConfig.save();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, 20, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("从上到下依次判断，先命中先赢"), this.width / 2, 34, 0xFFAAAAAA);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        save();
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }
}
