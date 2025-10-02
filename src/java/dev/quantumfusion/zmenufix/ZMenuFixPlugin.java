package dev.quantumfusion.zmenufix;

import dev.quantumfusion.zmenufix.config.ZMenuFixConfiguration;
import dev.quantumfusion.zmenufix.logging.ZMenuFixFileLogger;
import dev.quantumfusion.zmenufix.service.ZMenuLifecycleListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class ZMenuFixPlugin extends JavaPlugin {

    private static final Pattern ANSI_PATTERN = Pattern.compile("\\u001B\\[[;\\d]*m");
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD_BRIGHT_AQUA = "\u001B[1;96m";
    private static final int BANNER_HEIGHT = 5;
    private static final BannerGlyph[] BANNER_GLYPHS = {
        new BannerGlyph(7, "███████", "    ███", "  ███", " ███", "███████"),
        BannerGlyph.space(2),
        new BannerGlyph(9, "███   ███", "████ ████", "███ █ ███", "███   ███", "███   ███"),
        BannerGlyph.space(3),
        new BannerGlyph(7, "███████", "███", "██████", "███", "███"),
        BannerGlyph.space(2),
        new BannerGlyph(7, "███████", "   █", "   █", "   █", "███████"),
        BannerGlyph.space(2),
        new BannerGlyph(9, "███   ███", " ███ ███ ", "  ████", " ███ ███ ", "███   ███")
    };
    private static final String[] BANNER_LINES = composeBanner();

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

    private static String[] composeBanner() {
        String[] lines = new String[BANNER_HEIGHT];
        for (int row = 0; row < BANNER_HEIGHT; row++) {
            StringBuilder builder = new StringBuilder(64);
            for (BannerGlyph glyph : BANNER_GLYPHS) {
                builder.append(glyph.line(row));
            }
            lines[row] = builder.toString();
        }
        return lines;
    }

    private void logStartupBanner() {
        for (String line : BANNER_LINES) {
            dispatchBannerLine(line);
        }
    }

    private void dispatchBannerLine(String plainLine) {
        String ansiLine = ANSI_BOLD_BRIGHT_AQUA + plainLine + ANSI_RESET;
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        if (console != null) {
            console.sendMessage(ansiLine);
            return;
        }

        System.out.println(ansiLine);
        String sanitized = ANSI_PATTERN.matcher(ansiLine).replaceAll("");
        if (!sanitized.isBlank()) {
            getLogger().info(sanitized);
        }
    }

    private static final class BannerGlyph {

        private final int width;
        private final String[] rows;

        private BannerGlyph(int width, String... rows) {
            if (rows.length != BANNER_HEIGHT) {
                throw new IllegalArgumentException("Each banner glyph must have exactly " + BANNER_HEIGHT + " rows.");
            }
            this.width = width;
            this.rows = rows;
        }

        private static BannerGlyph space(int width) {
            String[] rows = new String[BANNER_HEIGHT];
            String blankRow = repeat(' ', Math.max(0, width));
            for (int i = 0; i < BANNER_HEIGHT; i++) {
                rows[i] = blankRow;
            }
            return new BannerGlyph(width, rows);
        }

        private static String repeat(char character, int count) {
            if (count <= 0) {
                return "";
            }
            StringBuilder builder = new StringBuilder(count);
            for (int i = 0; i < count; i++) {
                builder.append(character);
            }
            return builder.toString();
        }

        private String line(int row) {
            String value = rows[row];
            if (value.length() >= width) {
                return value;
            }
            return String.format("%-" + width + "s", value);
        }
    }
}
