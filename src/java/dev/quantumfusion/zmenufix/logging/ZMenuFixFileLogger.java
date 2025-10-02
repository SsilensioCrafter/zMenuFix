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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ZMenuFixFileLogger {

    private static final DateTimeFormatter LOG_LINE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    private static final String ROOT_ELEMENT = "handled-errors";
    private static final String ROOT_OPEN = "<" + ROOT_ELEMENT + ">";
    private static final String ROOT_CLOSE = "</" + ROOT_ELEMENT + ">";

    private final ZMenuFixPlugin plugin;
    private final Logger consoleLogger;
    private final ZMenuFixConfiguration.LoggingSettings settings;
    private final Lock writeLock = new ReentrantLock();

    private Path logFile;

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

    public void logFixEventXml(String reason, int closedCount, List<String> affectedPlayers) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(affectedPlayers, "affectedPlayers");

        StringBuilder consoleMessage = new StringBuilder();
        consoleMessage.append("Closed ").append(closedCount).append(" inventory view(s) because ")
                .append(reason).append('.');
        StringBuilder playersList = new StringBuilder();
        for (String player : affectedPlayers) {
            if (player == null || player.isBlank()) {
                continue;
            }
            if (playersList.length() > 0) {
                playersList.append(", ");
            }
            playersList.append(player);
        }
        if (playersList.length() > 0) {
            consoleMessage.append(" Players: ").append(playersList);
        }
        log(Level.INFO, consoleMessage.toString(), null);

        if (!settings.enabled()) {
            return;
        }

        writeLock.lock();
        try {
            StringBuilder builder = new StringBuilder();
            builder.append("<fix-event timestamp=\"")
                    .append(escapeForXml(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now())))
                    .append("\" reason=\"")
                    .append(escapeForXml(reason))
                    .append("\" closed=\"")
                    .append(closedCount)
                    .append("\">");

            if (!affectedPlayers.isEmpty()) {
                builder.append("<players>");
                for (String player : affectedPlayers) {
                    if (player == null || player.isBlank()) {
                        continue;
                    }
                    builder.append("<player name=\"")
                            .append(escapeForXml(player))
                            .append("\"/>");
                }
                builder.append("</players>");
            }

            builder.append("</fix-event>");
            appendXmlEntry(builder.toString());
        } catch (IOException exception) {
            consoleLogger.log(Level.SEVERE, "Failed to write fix-event entry to handled-errors.xml.", exception);
        } finally {
            writeLock.unlock();
        }
    }

    public void shutdown() {
        debug("Shutting down file logger.");
    }

    private void initialize() {
        writeLock.lock();
        try {
            logFile = plugin.getDataFolder().toPath().resolve(settings.file());
            ensureFileReady();
        } catch (IOException exception) {
            consoleLogger.log(Level.SEVERE, "Unable to initialize handled-errors.xml log file.", exception);
        } finally {
            writeLock.unlock();
        }
    }

    private void ensureFileReady() throws IOException {
        if (logFile == null) {
            logFile = plugin.getDataFolder().toPath().resolve(settings.file());
        }

        if (Files.notExists(logFile)) {
            writeFreshDocument();
            return;
        }

        if (!Files.isRegularFile(logFile)) {
            throw new IOException("Logging target is not a regular file: " + logFile);
        }

        if (Files.size(logFile) == 0L) {
            writeFreshDocument();
        }
    }

    private void writeFreshDocument() throws IOException {
        if (logFile == null) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append(XML_HEADER).append(System.lineSeparator())
                .append(ROOT_OPEN).append(System.lineSeparator())
                .append(ROOT_CLOSE).append(System.lineSeparator());
        Files.writeString(logFile, builder.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void log(Level level, String message, Throwable throwable) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(message, "message");

        consoleLogger.log(level, message, throwable);

        if (!shouldPersist(level, throwable)) {
            return;
        }

        writeLock.lock();
        try {
            String xmlEntry = buildLogEntry(level, message, throwable);
            appendXmlEntry(xmlEntry);
        } catch (IOException exception) {
            consoleLogger.log(Level.SEVERE, "Failed to write to handled-errors.xml.", exception);
        } finally {
            writeLock.unlock();
        }
    }

    private boolean shouldPersist(Level level, Throwable throwable) {
        if (!settings.enabled()) {
            return false;
        }
        return throwable != null || level.intValue() >= Level.SEVERE.intValue();
    }

    private String buildLogEntry(Level level, String message, Throwable throwable) {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder builder = new StringBuilder();
        builder.append("<log timestamp=\"")
                .append(escapeForXml(LOG_LINE_FORMAT.format(now)))
                .append("\" level=\"")
                .append(escapeForXml(level.getName()))
                .append("\">");
        builder.append("<message>")
                .append(escapeForXml(message))
                .append("</message>");

        if (throwable != null) {
            builder.append("<error type=\"")
                    .append(escapeForXml(throwable.getClass().getName()))
                    .append("\"");
            String throwableMessage = throwable.getMessage();
            if (throwableMessage != null && !throwableMessage.isBlank()) {
                builder.append(" message=\"")
                        .append(escapeForXml(throwableMessage))
                        .append("\"");
            }
            builder.append("/>");
            if (settings.includeStacktraces()) {
                builder.append("<stacktrace><![CDATA[")
                        .append(stackTraceAsString(throwable))
                        .append("]]></stacktrace>");
            }
        }

        builder.append("</log>");
        return builder.toString();
    }

    private void appendXmlEntry(String entry) throws IOException {
        ensureFileReady();
        if (logFile == null) {
            return;
        }

        String content = Files.readString(logFile, StandardCharsets.UTF_8);
        int insertIndex = content.lastIndexOf(ROOT_CLOSE);
        if (insertIndex < 0) {
            writeFreshDocument();
            content = Files.readString(logFile, StandardCharsets.UTF_8);
            insertIndex = content.lastIndexOf(ROOT_CLOSE);
            if (insertIndex < 0) {
                return;
            }
        }

        String prefix = content.substring(0, insertIndex);
        if (!prefix.endsWith(System.lineSeparator())) {
            prefix = prefix + System.lineSeparator();
        }
        String suffix = content.substring(insertIndex);

        StringBuilder updated = new StringBuilder(prefix.length() + entry.length() + suffix.length() + 4);
        updated.append(prefix);
        updated.append("  ").append(entry).append(System.lineSeparator());
        updated.append(suffix);

        Files.writeString(logFile, updated.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
    }

    private String stackTraceAsString(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }

    private String escapeForXml(String value) {
        Objects.requireNonNull(value, "value");
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;");
    }
}
