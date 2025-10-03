# ZMenuFix

<img width="1024" height="1024" alt="image" src="https://github.com/user-attachments/assets/4b1449f7-689f-49f6-b7ca-310cffd80a0c" />


Production-ready mitigation plugin that ensures zMenu GUI sessions are closed safely when the zMenu plugin reloads or shuts down. Built for Paper/Spigot 1.20.1+ with configuration-driven behaviour and structured XML logging.

## Features
- Gracefully detects zMenu enable/disable lifecycle without a hard dependency.
- Closes lingering inventory views on zMenu disable to prevent `IllegalPluginAccessException`.
- Optional player notifications, debug instrumentation, and async guards for thread safety.
- Structured XML log stream written to `plugins/ZMenuFix/handled-errors.xml` with optional stack traces.

## Configuration
Configuration is stored at `plugins/ZMenuFix/config.yml`:

```yaml
enabled: true
# Enables verbose debug console + file output
debug: false
log:
  enabled: true
  file: handled-errors.xml
  include_stacktraces: false
fix:
  close_on_zmenu_disable: true
  close_all_inventories: true
  async_guard: true
  notify_players: false
  notify_message: "&eYour menu was closed due to zMenu restart."
```

## Project layout
- `zMenuFix/` – Maven module containing the plugin implementation.
  - `src/java` – Java sources for the plugin.
  - `src/resources` – bundled configuration defaults and metadata.

## Building
1. Install Java 17 or newer.
2. Run `mvn clean package`.
3. Drop the generated `target/ZMenuFix.jar` into your server's `plugins/` folder.

## Support
Issues and pull requests are welcome.
