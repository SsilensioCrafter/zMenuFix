package dev.quantumfusion.zmenufix;

import dev.quantumfusion.zmenufix.config.ZMenuFixConfiguration;
import dev.quantumfusion.zmenufix.logging.ZMenuFixFileLogger;
import dev.quantumfusion.zmenufix.service.ZMenuLifecycleListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class ZMenuFixPlugin extends JavaPlugin {

    private static final Pattern ANSI_PATTERN = Pattern.compile("\\u001B\\[[;\\d]*m");

    private final AtomicBoolean zMenuDetected = new AtomicBoolean(false);

    private ZMenuFixConfiguration configuration;
    private ZMenuFixFileLogger fileLogger;
    private ZMenuLifecycleListener lifecycleListener;

    @Override
    public void onEnable() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            getLogger().severe("Unable to create plugin data folder. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            ensureConfigurationFile(dataFolder);
            reloadConfiguration();
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Failed to load configuration, disabling plugin.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.fileLogger = new ZMenuFixFileLogger(this, configuration.logging());
        logStartupBanner();
        fileLogger.info("ZMenuFix boot sequence initialized.");

        if (!configuration.enabled()) {
            getLogger().warning("ZMenuFix is disabled via configuration. Functionality will remain idle.");
            fileLogger.warn("Plugin disabled through configuration. No listeners will be registered.");
            return;
        }

        PluginManager pluginManager = getServer().getPluginManager();
        this.lifecycleListener = new ZMenuLifecycleListener(this, configuration, fileLogger, zMenuDetected);
        pluginManager.registerEvents(lifecycleListener, this);

        Plugin zMenu = pluginManager.getPlugin("zMenu");
        if (zMenu != null && zMenu.isEnabled()) {
            lifecycleListener.handleZMenuEnabled(zMenu.getDescription().getVersion());
        } else {
            String reason = (zMenu == null) ? "not present" : "present but not yet enabled";
            getLogger().warning("zMenu is " + reason + ". Awaiting enable event.");
            fileLogger.warn("zMenu is " + reason + ". ZMenuFix will wait for PluginEnableEvent.");
        }

        getLogger().info("ZMenuFix is ready.");
    }

    @Override
    public void onDisable() {
        if (fileLogger != null) {
            fileLogger.info("ZMenuFix shutdown sequence started.");
            fileLogger.shutdown();
        }
        lifecycleListener = null;
    }

    public void reloadConfiguration() {
        reloadConfig();
        FileConfiguration fileConfiguration = getConfig();
        fileConfiguration.options().copyDefaults(true);
        saveConfig();
        this.configuration = new ZMenuFixConfiguration(fileConfiguration);
    }

    public ZMenuFixConfiguration configuration() {
        return Objects.requireNonNull(configuration, "configuration");
    }

    public ZMenuFixFileLogger fileLogger() {
        return Objects.requireNonNull(fileLogger, "fileLogger");
    }

    public void executeOnPrimaryThread(Runnable task) {
        Objects.requireNonNull(task, "task");
        boolean shouldGuard = configuration != null && configuration.fix().asyncGuard();
        if (shouldGuard && !Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(this, task);
        } else {
            task.run();
        }
    }

    public boolean isDebug() {
        return configuration != null && configuration.debug();
    }

    private void ensureConfigurationFile(File dataFolder) throws IOException {
        try (InputStream ignored = getResource("config.yml")) {
            if (ignored == null) {
                throw new IOException("config.yml resource is missing from the plugin jar.");
            }
        }

        saveDefaultConfig();

        File configFile = new File(dataFolder, "config.yml");
        if (!configFile.exists()) {
            throw new IOException("Unable to create config.yml in the plugin data folder.");
        }
    }

    private void logStartupBanner() {
        String accent = "\u001B[38;2;0;204;255m";
        String secondary = "\u001B[38;2;0;153;255m";
        String reset = "\u001B[0m";
        String[] z = {
                "█████████",
                "      ██ ",
                "    ██   ",
                "  ██     ",
                "██       ",
                "█████████"
        };
        String[] m = {
                "███   ███",
                "████ ████",
                "██ ███ ██",
                "██  █  ██",
                "██     ██",
                "██     ██"
        };
        String[] f = {
                "█████████",
                "██       ",
                "███████  ",
                "██       ",
                "██       ",
                "██       "
        };
        String[] i = {
                "█████████",
                "   ███   ",
                "   ███   ",
                "   ███   ",
                "   ███   ",
                "█████████"
        };
        String[] x = {
                "███   ███",
                " ███ ███ ",
                "  █████  ",
                "  █████  ",
                " ███ ███ ",
                "███   ███"
        };

        String[][] letters = {z, m, f, i, x};
        String[] glyph = new String[z.length];
        for (int row = 0; row < z.length; row++) {
            StringBuilder rowBuilder = new StringBuilder();
            for (int column = 0; column < letters.length; column++) {
                if (column > 0) {
                    rowBuilder.append("  ");
                }
                rowBuilder.append(letters[column][row]);
            }
            glyph[row] = rowBuilder.toString();
        }

        int width = Arrays.stream(glyph)
                .mapToInt(String::length)
                .max()
                .orElse(0);

        String top = accent + "╔" + "═".repeat(width + 4) + "╗" + reset;
        String bottom = accent + "╚" + "═".repeat(width + 4) + "╝" + reset;

        dispatchBannerLine(top);
        for (String line : glyph) {
            String padded = String.format("%-" + width + "s", line);
            String framed = accent + "║  " + secondary + padded + accent + "  ║" + reset;
            dispatchBannerLine(framed);
        }
        dispatchBannerLine(bottom);
    }

    private void dispatchBannerLine(String line) {
        getLogger().info(line);
        if (fileLogger != null) {
            fileLogger.info(ANSI_PATTERN.matcher(line).replaceAll(""));
        }
    }
}
