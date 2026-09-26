package com.combatassist.compat;

import com.combatassist.config.CombatConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * 参数都在这里轮换档位。
 *
 * <p>1.21.9+ 的 Screen 框架自己会画背景（含模糊），这里<b>不要</b>再调
 * renderBackground —— 会在同一帧模糊第二次，直接崩 Can only blur once per frame。
 */
public class CombatAssistConfigScreen extends Screen {

    private static final int COL_W = 170;
    private static final int ROW_H = 20;
    private static final int GAP = 24;

    private final Screen parent;

    public CombatAssistConfigScreen(Screen parent) {
        super(Text.literal("CombatAssist 设置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - COL_W / 2;
        int top = this.height / 2 - 60;
        int row = 0;

        addBool(x, top + row++ * GAP, "总开关",
                v -> v ? "开" : "关",
                () -> CombatConfig.enabled, v -> CombatConfig.enabled = v);

        addBool(x, top + row++ * GAP, "秒切",
                v -> v ? "开" : "关",
                () -> CombatConfig.swapEnabled, v -> CombatConfig.swapEnabled = v);

        addNum(x, top + row++ * GAP, "秒切目标槽",
                v -> String.valueOf(v + 1),
                () -> CombatConfig.swapTargetSlot, v -> CombatConfig.swapTargetSlot = v);

        addBool(x, top + row++ * GAP, "攻击后切回",
                v -> v ? "开" : "关",
                () -> CombatConfig.swapRestore, v -> CombatConfig.swapRestore = v);

        addHold(x, top + row++ * GAP, "保持时长");

        addDrawableChild(ButtonWidget.builder(Text.literal("秒切规则表 →"), button -> {
            if (this.client != null) {
                this.client.setScreen(new RuleTableScreen(this));
            }
        }).dimensions(x, top + row++ * GAP, COL_W, ROW_H).build());

        addBool(x, top + row++ * GAP, "调试 HUD",
                v -> v ? "开" : "关",
                () -> CombatConfig.debugHud, v -> CombatConfig.debugHud = v);

        addBool(x, top + row++ * GAP, "诊断日志",
                v -> v ? "开" : "关",
                () -> CombatConfig.debugLog, v -> CombatConfig.debugLog = v);

        addDrawableChild(ButtonWidget.builder(Text.literal("完成"), button -> this.close())
                .dimensions(x, top + row * GAP, COL_W, ROW_H).build());
    }

    private void addBool(int x, int y, String name, BoolLabel label,
                         BoolGetter getter, BoolSetter setter) {
        addDrawableChild(ButtonWidget.builder(Text.literal(text(name, label.of(getter.get()))), button -> {
            setter.set(!getter.get());
            CombatConfig.save();
            button.setMessage(Text.literal(text(name, label.of(getter.get()))));
        }).dimensions(x, y, COL_W, ROW_H).build());
    }

    private void addNum(int x, int y, String name, IntLabel label,
                        IntGetter getter, IntSetter setter) {
        addDrawableChild(ButtonWidget.builder(Text.literal(text(name, label.of(getter.get()))), button -> {
            setter.set((getter.get() + 1) % 9);
            CombatConfig.save();
            button.setMessage(Text.literal(text(name, label.of(getter.get()))));
        }).dimensions(x, y, COL_W, ROW_H).build());
    }

    private void addHold(int x, int y, String name) {
        addDrawableChild(ButtonWidget.builder(Text.literal(text(name, holdLabel())), button -> {
            CombatConfig.swapHoldTicks = (int) CombatConfig.nextStep(
                    CombatConfig.HOLD_TICK_STEPS, CombatConfig.swapHoldTicks);
            CombatConfig.save();
            button.setMessage(Text.literal(text(name, holdLabel())));
        }).dimensions(x, y, COL_W, ROW_H).build());
    }

    private static String holdLabel() {
        int ticks = CombatConfig.swapHoldTicks;
        return ticks == 0 ? "同 tick 切回" : ticks + " tick (" + (ticks * 50) + "ms)";
    }

    private static String text(String name, String value) {
        return name + ": " + value;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, 20, 0xFFFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        CombatConfig.save();
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @FunctionalInterface
    public interface BoolLabel {
        String of(boolean value);
    }

    @FunctionalInterface
    public interface BoolGetter {
        boolean get();
    }

    @FunctionalInterface
    public interface BoolSetter {
        void set(boolean value);
    }

    @FunctionalInterface
    public interface IntLabel {
        String of(int value);
    }

    @FunctionalInterface
    public interface IntGetter {
        int get();
    }

    @FunctionalInterface
    public interface IntSetter {
        void set(int value);
    }
}
