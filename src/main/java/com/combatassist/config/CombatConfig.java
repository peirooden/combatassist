package com.combatassist.config;

import com.combatassist.Edition;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class CombatConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("CombatAssist");
    private static final String FILE_NAME = "combatassist.properties";

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

    public static boolean enabled = true;
    public static boolean debugHud = Edition.DEV;

    /** 诊断日志。默认关，需要时在 ModMenu 里打开（用户版恒关，CombatAssistClient.debug 会兜底检查 Edition.DEV）。 */
    public static boolean debugLog = false;

    /** 秒切：攻击时先把主手切到目标槽。 */
    public static boolean swapEnabled = true;

    /** 自动判断该切哪把武器（关掉则用手动指定的槽位）。 */
    public static boolean swapAuto = true;

    /**
     * 预持长矛。<b>默认关</b>：它为了射程需要在攻击前就把矛拿在手上，
     * 和「攻击完切回原槽」本质冲突，且至今未验证成功。
     * 单独开关，避免它污染已验证可用的斧/锤流程。
     */
    public static boolean swapPreHoldSpear = false;

    /** 规则表的手写覆盖；空 = 用内置默认规则表。 */
    public static String swapRules = "";

    /** 自动模式下是否使用重锤（跌落加成）。 */
    public static boolean swapUseMace = true;

    /** 重锤起效的最小下落格数。 */
    public static double swapMaceMinFall = 1.5D;

    public static final double[] MACE_FALL_STEPS = {0.0D, 1.0D, 1.5D, 2.0D, 3.0D, 5.0D};

    /** 秒切目标槽（0-8，界面里显示成 1-9）。 */
    public static int swapTargetSlot = 1;

    /** 是否在攻击后切回原来的槽。 */
    public static boolean swapRestore = true;

    /** 攻击后保持新武器多少 tick 再切回。0 = 同一 tick 内切回（看不见变化）。 */
    public static int swapHoldTicks = 2;

    public static final double[] HOLD_TICK_STEPS = {0.0D, 1.0D, 2.0D, 4.0D, 6.0D, 10.0D, 20.0D};

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static void load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            LOGGER.info("[CombatAssist] no config yet, writing defaults");
            save();
            return;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.warn("[CombatAssist] failed to read config, using defaults: {}", e.toString());
            return;
        }

        enabled = readBool(props, "enabled", enabled);
        debugHud = readBool(props, "debugHud", debugHud);
        debugLog = readBool(props, "debugLog", debugLog);
        swapEnabled = readBool(props, "swap.enabled", swapEnabled);
        swapAuto = readBool(props, "swap.auto", swapAuto);
        swapPreHoldSpear = readBool(props, "swap.preHoldSpear", swapPreHoldSpear);
        swapUseMace = readBool(props, "swap.useMace", swapUseMace);
        swapMaceMinFall = readDouble(props, "swap.maceMinFall", swapMaceMinFall);

        String rules = props.getProperty("swap.rules", "");
        if (!com.combatassist.module.rule.RuleTable.parse(rules)) {
            LOGGER.warn("[CombatAssist] 规则表解析失败，回退到默认规则表：{}", rules);
        }
        swapRestore = readBool(props, "swap.restore", swapRestore);
        swapTargetSlot = (int) readDouble(props, "swap.targetSlot", swapTargetSlot);
        swapHoldTicks = (int) readDouble(props, "swap.holdTicks", swapHoldTicks);

        LOGGER.info("[CombatAssist] config loaded: enabled={} hud={} swap={} slot={}",
                enabled, debugHud, swapEnabled, swapTargetSlot);

        save();
    }

    public static void save() {
        Properties props = new Properties();
        props.setProperty("enabled", String.valueOf(enabled));
        props.setProperty("debugHud", String.valueOf(debugHud));
        props.setProperty("debugLog", String.valueOf(debugLog));
        props.setProperty("swap.enabled", String.valueOf(swapEnabled));
        props.setProperty("swap.auto", String.valueOf(swapAuto));
        props.setProperty("swap.preHoldSpear", String.valueOf(swapPreHoldSpear));
        props.setProperty("swap.useMace", String.valueOf(swapUseMace));
        props.setProperty("swap.maceMinFall", String.valueOf(swapMaceMinFall));
        props.setProperty("swap.rules", com.combatassist.module.rule.RuleTable.serialize());
        props.setProperty("swap.restore", String.valueOf(swapRestore));
        props.setProperty("swap.targetSlot", String.valueOf(swapTargetSlot));
        props.setProperty("swap.holdTicks", String.valueOf(swapHoldTicks));
        try (OutputStream out = Files.newOutputStream(configPath())) {
            props.store(out, "CombatAssist - client-side combat assist");
        } catch (IOException e) {
            LOGGER.warn("[CombatAssist] failed to write config: {}", e.toString());
        }
    }

    private static boolean readBool(Properties props, String key, boolean fallback) {
        String raw = props.getProperty(key);
        return raw == null ? fallback : Boolean.parseBoolean(raw);
    }

    private static double readDouble(Properties props, String key, double fallback) {
        try {
            return Double.parseDouble(props.getProperty(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            LOGGER.warn("[CombatAssist] bad {} in config, keeping {}", key, fallback);
            return fallback;
        }
    }
}
