package dev.quantumfusion.zmenufix.config;

import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public final class ZMenuFixConfiguration {

    private final boolean enabled;
    private final boolean debug;
    private final LoggingSettings logging;
    private final FixSettings fix;

    public ZMenuFixConfiguration(FileConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        this.enabled = configuration.getBoolean("enabled", true);
        this.debug = configuration.getBoolean("debug", false);
        this.logging = new LoggingSettings(configuration.getConfigurationSection("log"));
        this.fix = new FixSettings(configuration.getConfigurationSection("fix"));
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean debug() {
        return debug;
    }

    public LoggingSettings logging() {
        return logging;
    }

    public FixSettings fix() {
        return fix;
    }

    public static final class LoggingSettings {

        private final boolean enabled;
        private final String folder;
        private final boolean rotateDaily;
        private final boolean includeStacktraces;

        public LoggingSettings(ConfigurationSection section) {
            if (section == null) {
                this.enabled = true;
                this.folder = "logs";
                this.rotateDaily = true;
                this.includeStacktraces = false;
                return;
            }

            this.enabled = section.getBoolean("enabled", true);
            this.folder = section.getString("folder", "logs");
            this.rotateDaily = section.getBoolean("rotate_daily", true);
            this.includeStacktraces = section.getBoolean("include_stacktraces", false);
        }

        public boolean enabled() {
            return enabled;
        }

        public String folder() {
            return folder;
        }

        public boolean rotateDaily() {
            return rotateDaily;
        }

        public boolean includeStacktraces() {
            return includeStacktraces;
        }
    }

    public static final class FixSettings {

        private final boolean closeOnZMenuDisable;
        private final boolean closeAllInventories;
        private final boolean asyncGuard;
        private final boolean notifyPlayers;
        private final String notifyMessage;

        public FixSettings(ConfigurationSection section) {
            if (section == null) {
                this.closeOnZMenuDisable = true;
                this.closeAllInventories = true;
                this.asyncGuard = true;
                this.notifyPlayers = false;
                this.notifyMessage = "&eYour menu was closed due to zMenu restart.";
                return;
            }

            this.closeOnZMenuDisable = section.getBoolean("close_on_zmenu_disable", true);
            this.closeAllInventories = section.getBoolean("close_all_inventories", true);
            this.asyncGuard = section.getBoolean("async_guard", true);
            this.notifyPlayers = section.getBoolean("notify_players", false);
            this.notifyMessage = section.getString("notify_message", "&eYour menu was closed due to zMenu restart.");
        }

        public boolean closeOnZMenuDisable() {
            return closeOnZMenuDisable;
        }

        public boolean closeAllInventories() {
            return closeAllInventories;
        }

        public boolean asyncGuard() {
            return asyncGuard;
        }

        public boolean notifyPlayers() {
            return notifyPlayers;
        }

        public String notifyMessage() {
            return notifyMessage;
        }
    }
}
