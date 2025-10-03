package dev.quantumfusion.zmenufix;

import dev.quantumfusion.zmenufix.config.ZMenuFixConfiguration;
import dev.quantumfusion.zmenufix.logging.ZMenuFixFileLogger;
import dev.quantumfusion.zmenufix.service.ZMenuLifecycleListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AtomicReference<Plugin> bridgedSchedulerFor = new AtomicReference<>();

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
            lifecycleListener.handleZMenuEnabled(zMenu);
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
        bridgedSchedulerFor.set(null);
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
        if (!shouldGuard || Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }

        if (!isEnabled()) {
            task.run();
            return;
        }

        Bukkit.getScheduler().runTask(this, task);
    }

    public boolean isDebug() {
        return configuration != null && configuration.debug();
    }

    public void attemptSchedulerBridge(Plugin zMenuPlugin) {
        Objects.requireNonNull(zMenuPlugin, "zMenuPlugin");

        if (configuration == null || !configuration.fix().rebindFoliaScheduler()) {
            return;
        }

        Plugin current = bridgedSchedulerFor.get();
        if (current == zMenuPlugin) {
            return;
        }

        try {
            Object implementation = locateFoliaSpigotImplementation(zMenuPlugin);
            if (implementation == null) {
                fileLogger.warn("Unable to locate zMenu FoliaLib implementation for scheduler bridge.");
                return;
            }

            Field pluginField = findPluginField(implementation);
            if (pluginField == null) {
                fileLogger.warn("Unable to identify plugin field inside zMenu FoliaLib implementation.");
                return;
            }

            Plugin existing = (Plugin) pluginField.get(implementation);
            if (existing == this) {
                bridgedSchedulerFor.set(zMenuPlugin);
                fileLogger.debug("Folia scheduler bridge already active for zMenu.");
                return;
            }

            pluginField.set(implementation, this);
            bridgedSchedulerFor.set(zMenuPlugin);
            fileLogger.info("Patched zMenu Folia scheduler to execute tasks under ZMenuFix context.");
        } catch (ReflectiveOperationException exception) {
            fileLogger.error("Failed to bridge zMenu Folia scheduler to ZMenuFix.", exception);
        }
    }

    public void clearSchedulerBridge(Plugin zMenuPlugin) {
        if (zMenuPlugin == null) {
            return;
        }
        bridgedSchedulerFor.compareAndSet(zMenuPlugin, null);
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

    private Object locateFoliaSpigotImplementation(Plugin zMenuPlugin) throws ReflectiveOperationException {
        ClassLoader classLoader = zMenuPlugin.getClass().getClassLoader();
        Class<?> foliaLibClass = Class.forName("fr.maxlego08.menu.hooks.folialib.FoliaLib", false, classLoader);
        Class<?> implementationClass = Class.forName(
                "fr.maxlego08.menu.hooks.folialib.impl.SpigotImplementation",
                false,
                classLoader
        );

        for (Field field : foliaLibClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (!field.canAccess(null)) {
                field.setAccessible(true);
            }
            Object value = field.get(null);
            if (value != null && implementationClass.isInstance(value)) {
                return value;
            }
        }

        for (Method method : foliaLibClass.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) {
                continue;
            }
            if (!implementationClass.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            if (!method.canAccess(null)) {
                method.setAccessible(true);
            }
            Object value = method.invoke(null);
            if (value != null && implementationClass.isInstance(value)) {
                return value;
            }
        }

        for (Field field : zMenuPlugin.getClass().getDeclaredFields()) {
            if (!field.canAccess(zMenuPlugin)) {
                field.setAccessible(true);
            }
            Object possibleFoliaLib = field.get(zMenuPlugin);
            if (possibleFoliaLib == null || !foliaLibClass.isInstance(possibleFoliaLib)) {
                continue;
            }

            Method getter = null;
            try {
                getter = foliaLibClass.getDeclaredMethod("getImplementation");
            } catch (NoSuchMethodException ignored) {
                // ignore, will inspect fields below
            }

            if (getter != null) {
                if (!getter.canAccess(possibleFoliaLib)) {
                    getter.setAccessible(true);
                }
                Object implementation = getter.invoke(possibleFoliaLib);
                if (implementation != null && implementationClass.isInstance(implementation)) {
                    return implementation;
                }
            }

            for (Field innerField : possibleFoliaLib.getClass().getDeclaredFields()) {
                if (!implementationClass.isAssignableFrom(innerField.getType())) {
                    continue;
                }
                if (!innerField.canAccess(possibleFoliaLib)) {
                    innerField.setAccessible(true);
                }
                Object implementation = innerField.get(possibleFoliaLib);
                if (implementation != null) {
                    return implementation;
                }
            }
        }

        return null;
    }

    private Field findPluginField(Object implementation) {
        Class<?> implementationClass = implementation.getClass();
        for (Field field : implementationClass.getDeclaredFields()) {
            if (!Plugin.class.isAssignableFrom(field.getType())) {
                continue;
            }
            if (!field.canAccess(implementation)) {
                field.setAccessible(true);
            }
            return field;
        }
        return null;
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
