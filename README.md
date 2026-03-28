# JosFamily

Simple marriage and family-style gameplay for Paper and Folia servers.

Players can propose, accept or deny proposals, buy marriage rings, check relationship status, and divorce. The plugin supports SQLite by default, optional MySQL, and optional Vault economy integration for paid marriages and rings.

This README is powered by Documatic for automated updating.

## Requirements

- Java 21
- Paper or Folia 1.21+
- Vault and an economy plugin only if economy-backed features are enabled

## Installation

### Download and install

1. Build or download the plugin jar.
2. Place it in your server `plugins/` folder.
3. Start the server once to generate the config files.
4. Adjust `config.yml`, `messages.yml`, and `ui.yml` if needed.
5. Restart the server or run `/family admin reload`.

### Build from source

```bash
./gradlew build
```

Builds the plugin and runs the shadow jar task.

```bash
./gradlew shadowJar
```

Creates the distributable plugin jar.

```bash
./gradlew runServer
```

Starts a local Paper 1.21 test server.

## Commands

All commands use `/marry`, with `/marriage` and `/family` as aliases.

| Command | Description |
| --- | --- |
| `/family` | Show your current relationship status. Requires `josfamily.command.status`. |
| `/family <player>` | Shortcut for proposing to a player. Requires `josfamily.command.propose`. |
| `/family propose <player>` | Send a marriage proposal. Requires `josfamily.command.propose`. |
| `/family ring <player>` | Buy a marriage ring for a player. Requires `josfamily.command.ring`. |
| `/family accept [player]` | Accept the current or named proposal. Requires `josfamily.command.accept`. |
| `/family deny [player]` | Deny the current or named proposal. Requires `josfamily.command.deny`. |
| `/family divorce` | End your current marriage. Requires `josfamily.command.divorce`. |
| `/family status` | View your own status. Requires `josfamily.command.status`. |
| `/family status <player>` | View another player's status. Requires `josfamily.command.status` and `josfamily.admin.inspect`. |
| `/family admin` | Show the admin command list. Requires `josfamily.admin`. |
| `/family admin reload` | Reload config and message files. Requires `josfamily.admin` and `josfamily.admin.reload`. |

## Permissions

| Permission | Description | Default |
| --- | --- | --- |
| `josfamily.command.propose` | Allows `/family propose <player>` and `/family <player>`. | `true` |
| `josfamily.command.ring` | Allows `/family ring <player>`. | `true` |
| `josfamily.command.status` | Allows `/family` and `/family status` for your own status. | `true` |
| `josfamily.command.accept` | Allows `/family accept [player]`. | `true` |
| `josfamily.command.deny` | Allows `/family deny [player]`. | `true` |
| `josfamily.command.divorce` | Allows `/family divorce`. | `true` |
| `josfamily.admin` | Allows `/family admin` and acts as the parent admin permission. | `op` |
| `josfamily.admin.reload` | Allows `/family admin reload`. Included by `josfamily.admin`. | `op` |
| `josfamily.admin.inspect` | Allows `/family status <player>`. Included by `josfamily.admin`. | `op` |

## Configuration Notes

- `database.type`: `SQLITE` by default, with optional MySQL settings included.
- `modules.proposal-distance`: Requires players to be within a configurable range to propose.
- `modules.marriage-cost`: Charges players through Vault if enabled.
- `modules.marriage-ring`: Lets players buy and use marriage rings.
- `marriage.proposal-expiry-seconds`: Controls how long proposals stay active.
- `marriage.divorce-requires-confirmation`: Opens a confirmation UI before divorce.

## Storage

- SQLite stores data locally in `plugins/JosFamily/data/marriages.db`.
- MySQL can be enabled by changing `database.type` and filling in the connection settings.

## Notes

- Folia is supported.
- Vault is optional, but required for paid marriage costs or ring purchases.
