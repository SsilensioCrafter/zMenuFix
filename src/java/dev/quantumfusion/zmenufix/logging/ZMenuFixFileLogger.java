package dev.quantumfusion.zmenufix.logging;

import dev.quantumfusion.zmenufix.ZMenuFixPlugin;
import dev.quantumfusion.zmenufix.config.ZMenuFixConfiguration;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ZMenuFixFileLogger {

    private static final DateTimeFormatter LOG_LINE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final DateTimeFormatter FILE_SUFFIX_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US);

    private final ZMenuFixPlugin plugin;
    private final Logger consoleLogger;
    private final ZMenuFixConfiguration.LoggingSettings settings;
    private final Lock writeLock = new ReentrantLock();

    private Path directory;
    private Path currentFile;
    private LocalDate currentDate;

    public ZMenuFixFileLogger(ZMenuFixPlugin plugin, ZMenuFixConfiguration.LoggingSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.consoleLogger = plugin.getLogger();
        this.settings = Objects.requireNonNull(settings, "settings");
        if (settings.enabled()) {
            initialize();
        }
    }

    public void debug(String message) {
        if (plugin.isDebug()) {
            log(Level.FINE, "[DEBUG] " + message, null);
        }
    }

    public void info(String message) {
        log(Level.INFO, message, null);
    }

    public void warn(String message) {
        log(Level.WARNING, message, null);
    }

    public void error(String message, Throwable throwable) {
        log(Level.SEVERE, message, throwable);
    }

    public void shutdown() {
        debug("Shutting down file logger.");
    }

    private void initialize() {
        writeLock.lock();
        try {
            Path dataFolder = plugin.getDataFolder().toPath();
            directory = dataFolder.resolve(settings.folder());
            Files.createDirectories(directory);
            rotateIfNeeded();
        } catch (IOException exception) {
            consoleLogger.log(Level.SEVERE, "Unable to initialize ZMenuFix log directory.", exception);
        } finally {
            writeLock.unlock();
        }
    }

    private void rotateIfNeeded() throws IOException {
        if (!settings.enabled()) {
            return;
        }

        LocalDate today = LocalDate.now();
        if (!settings.rotateDaily() && currentFile != null) {
            return;
        }

        if (currentFile != null && !settings.rotateDaily()) {
            return;
        }

        if (currentFile != null && today.equals(currentDate)) {
            return;
        }

        currentDate = today;
        String fileName = settings.rotateDaily()
                ? "zmenufix-" + FILE_SUFFIX_FORMAT.format(today) + ".log"
                : "zmenufix.log";
        currentFile = directory.resolve(fileName);
        if (Files.notExists(currentFile)) {
            Files.createFile(currentFile);
        }
    }

    private void log(Level level, String message, Throwable throwable) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(message, "message");

        consoleLogger.log(level, message, throwable);

        if (!settings.enabled()) {
            return;
        }

        writeLock.lock();
        try {
            rotateIfNeeded();
            if (currentFile == null) {
                return;
            }

            String logLine = String.format(Locale.US, "%s [%s] %s", LOG_LINE_FORMAT.format(LocalDateTime.now()),
                    level.getName(), message);
            Files.writeString(currentFile, logLine + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            if (throwable != null && settings.includeStacktraces()) {
                StringWriter stringWriter = new StringWriter();
                throwable.printStackTrace(new PrintWriter(stringWriter));
                Files.writeString(currentFile, stringWriter + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException exception) {
            consoleLogger.log(Level.SEVERE, "Failed to write to ZMenuFix log file.", exception);
        } finally {
            writeLock.unlock();
        }
    }
}
