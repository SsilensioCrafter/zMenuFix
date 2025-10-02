# ZMenuFix

Production-ready mitigation plugin that ensures zMenu GUI sessions are closed safely when the zMenu plugin reloads or shuts down. Built for Paper/Spigot 1.20.1+ with configuration-driven behaviour and structured file logging.

## Features
- Gracefully detects zMenu enable/disable lifecycle without a hard dependency.
- Closes lingering inventory views on zMenu disable to prevent `IllegalPluginAccessException`.
- Optional player notifications, debug instrumentation, and async guards for thread safety.
- Daily-rotating file logs stored under `plugins/ZMenuFix/logs` with optional stack traces.

## Configuration
Configuration is stored at `plugins/ZMenuFix/config.yml`:

```yaml
enabled: true
# Enables verbose debug console + file output
debug: false
log:
  enabled: true
  folder: logs
  rotate_daily: true
  include_stacktraces: false
fix:
  close_on_zmenu_disable: true
  close_all_inventories: true
  async_guard: true
  notify_players: false
  notify_message: "&eYour menu was closed due to zMenu restart."
```

## Building
1. Install Java 17 or newer.
2. Run `mvn clean package`.
3. Drop the generated `target/zmenufix-1.1.0-SNAPSHOT.jar` into your server's `plugins/` folder.

## Support
Issues and pull requests are welcome.
