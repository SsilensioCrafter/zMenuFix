package dev.quantumfusion.zmenufix.service;

import dev.quantumfusion.zmenufix.ZMenuFixPlugin;
import dev.quantumfusion.zmenufix.config.ZMenuFixConfiguration;
import dev.quantumfusion.zmenufix.logging.ZMenuFixFileLogger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

public final class ZMenuLifecycleListener implements Listener {

    private static final String ZMENU_NAME = "zMenu";

    private final ZMenuFixPlugin plugin;
    private final ZMenuFixConfiguration configuration;
    private final ZMenuFixFileLogger fileLogger;
    private final AtomicBoolean zMenuEnabledFlag;

    public ZMenuLifecycleListener(
            ZMenuFixPlugin plugin,
            ZMenuFixConfiguration configuration,
            ZMenuFixFileLogger fileLogger,
            AtomicBoolean zMenuEnabledFlag
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.fileLogger = Objects.requireNonNull(fileLogger, "fileLogger");
        this.zMenuEnabledFlag = Objects.requireNonNull(zMenuEnabledFlag, "zMenuEnabledFlag");
    }

    public void handleZMenuEnabled(Plugin zMenu) {
        if (!configuration.enabled()) {
            return;
        }

        if (zMenu == null) {
            return;
        }

        if (zMenuEnabledFlag.compareAndSet(false, true)) {
            String version = zMenu.getDescription() != null ? zMenu.getDescription().getVersion() : null;
            String versionInfo = version == null ? "unknown version" : "v" + version;
            plugin.getLogger().info("Detected zMenu " + versionInfo + " as enabled.");
            fileLogger.info("Detected zMenu " + versionInfo + " as enabled.");
        }

        plugin.attemptSchedulerBridge(zMenu);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (!configuration.enabled()) {
            return;
        }

        if (event.getPlugin().getName().equalsIgnoreCase(ZMENU_NAME)) {
            handleZMenuEnabled(event.getPlugin());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (!configuration.enabled() || !configuration.fix().closeOnZMenuDisable()) {
            return;
        }

        if (!event.getPlugin().getName().equalsIgnoreCase(ZMENU_NAME)) {
            return;
        }

        if (!zMenuEnabledFlag.get()) {
            plugin.getLogger().log(Level.FINE, "Received zMenu disable event but plugin was not marked enabled.");
        }

        zMenuEnabledFlag.set(false);
        plugin.clearSchedulerBridge(event.getPlugin());
        fileLogger.info("zMenu disable detected. Initiating inventory close routine.");
        plugin.executeOnPrimaryThread(() -> closeInventories("PluginDisableEvent"));
    }

    private void closeInventories(String reason) {
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        if (onlinePlayers.isEmpty()) {
            fileLogger.info("No online players to process for zMenu inventory closure (" + reason + ").");
            return;
        }

        List<String> affectedPlayers = new ArrayList<>();
        for (Player player : onlinePlayers) {
            if (player == null || !player.isOnline() || !player.isValid()) {
                continue;
            }

            InventoryView view = player.getOpenInventory();
            if (view == null) {
                continue;
            }

            Inventory top = view.getTopInventory();
            if (!hasExternalView(view, top)) {
                continue;
            }

            if (!configuration.fix().closeAllInventories() && !isLikelyZMenuView(top)) {
                continue;
            }

            try {
                player.closeInventory();
            } catch (IllegalPluginAccessException exception) {
                fileLogger.warn("Failed to close inventory for " + player.getName()
                        + " because zMenu is already disabled: " + exception.getMessage());
                continue;
            }
            affectedPlayers.add(player.getName());
            notifyPlayer(player);
        }

        int closedCount = affectedPlayers.size();
        String summary = String.format(Locale.US,
                "Closed %d inventory view(s) after zMenu disable via %s.", closedCount, reason);
        fileLogger.info(summary);
        fileLogger.logFixEventXml(reason, closedCount, List.copyOf(affectedPlayers));

        if (plugin.isDebug() && !affectedPlayers.isEmpty()) {
            fileLogger.debug("Players affected: " + String.join(", ", affectedPlayers));
        }
    }

    private boolean hasExternalView(InventoryView view, Inventory topInventory) {
        if (topInventory == null) {
            return false;
        }

        InventoryHolder holder = topInventory.getHolder();
        InventoryType type = topInventory.getType();
        if (type == InventoryType.CRAFTING && holder instanceof Player) {
            return false;
        }
        return topInventory.getSize() > 0 && (view.getType() != InventoryType.CRAFTING || holder == null || !(holder instanceof Player));
    }

    private boolean isLikelyZMenuView(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder == null) {
            return true;
        }

        String holderName = holder.getClass().getName().toLowerCase(Locale.ROOT);
        return holderName.contains("zmenu");
    }

    private void notifyPlayer(Player player) {
        if (!configuration.fix().notifyPlayers()) {
            return;
        }

        String message = configuration.fix().notifyMessage();
        if (message == null || message.isBlank()) {
            return;
        }

        String parsed = ChatColor.translateAlternateColorCodes('&', message);
        player.sendMessage(parsed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!configuration.enabled() || !plugin.isDebug()) {
            return;
        }

        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        fileLogger.debug("Inventory closed for player " + player.getName() + " due to " + event.getReason());
    }
}
