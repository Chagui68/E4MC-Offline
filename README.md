# E4MC Offline

Open a LAN server to anyone, anywhere, anytime — now with optional offline mode.

E4MC Offline is a fork of [e4mc](https://github.com/vgskye/e4mc-minecraft-architectury) by Skye. It keeps all of e4mc's features (public LAN sharing through the e4mc relay, Dialtone peer-to-peer connections, `/ban` and `/whitelist` on LAN worlds) and adds an option to run the shared world in **offline mode**, so players without a Mojang/Microsoft-authenticated account can join.

Supports Fabric, Forge and NeoForge.

## Usage

1. Install the mod on the client that hosts the world.
2. Open the world to LAN as normal. e4mc prints a public domain in chat that others can use to join.

### Offline mode

Offline mode is **disabled by default**. To enable it, edit `config/e4mc.toml`:

```toml
offlineMode = true
```

Then open the world to LAN. With offline mode on:

- **Premium names still use Mojang authentication.** When a player joins, E4MC Offline asks Mojang whether that name belongs to a paid account. If it does, the player must log in with that account, so nobody can take a premium player's name.
- **Every other player must log in with a password.** On their first join they run `/register <password> <password>`, and on later joins `/login <password>`. Until then they cannot move, chat, use commands, interact or touch inventories. Players who do not log in within 60 seconds are kicked, and a wrong password kicks the player.
- **Names cannot be stolen while in use.** A player cannot join with the name of someone already online, including the host.
- **The relay connection tolerates bad networks better.** It survives network drops of up to 30 seconds (10 seconds otherwise) and uses BBR congestion control, which keeps throughput up on long-distance, lossy links.

Passwords are stored hashed (PBKDF2) in `config/e4mc-offline-accounts.properties`, one line per player name. To reset a player's password, delete their line while the world is closed.

Passwords may only contain letters, numbers and `_ - . +`. The host never needs to log in.

> **Note:** premium and non-premium players get different UUIDs, so player data from worlds played in plain offline mode does not carry over for premium players.

Offline mode only affects worlds hosted from the client (integrated server). It never changes dedicated servers.

E4MC Offline also fixes a vanilla issue that applies with or without offline mode: a remote player using the host's name was given the host's profile before any authentication. Only the host's own local connection gets that profile now.

### Fixed connection code

With offline mode on, the host also gets a **fixed code** that never changes, shown in chat when the world opens to LAN:

```
Fixed code for players with E4MC Offline: 3f2a…c91e.7b04…5d10
```

Players who install E4MC Offline add that code as a normal server address in the multiplayer screen, once. They connect peer-to-peer through Dialtone (iroh), falling back to a relay when a direct connection is not possible. It works behind CGNAT and needs no port forwarding, domain or own relay. Players without the mod keep using the e4mc domain, which still changes every session.

The code comes from a secret key stored in `config/e4mc-dialtone-host.key`. Back it up to keep the same code on another PC, and never share it: anyone with that file can pose as your server.

This uses a [fork of iroh-java](https://github.com/Chagui68/iroh-java) that adds persistent keys and lookup by id through n0's public DNS service.

### Ping and distance

Distance adds latency that no mod can remove. The public relay is picked close to the host, so a player far away still crosses that distance to reach it. What helps:

- Players who install E4MC Offline (or e4mc) connect peer-to-peer through Dialtone when possible, skipping the relay.
- Vanilla already compresses packets above 256 bytes, and since 1.20.2 adapts chunk sending to each player's connection.

## Configuration

All options live in `config/e4mc.toml`:

| Option | Default | Description |
|---|---|---|
| `hideDomainInChat` | `false` | Hide the domain in chat and only allow copying it |
| `useBroker` | `true` | Use the broker to pick the best relay by location instead of a hard-coded relay |
| `brokerUrl` | `https://broker.e4mc.link/getBestRelay` | Broker URL |
| `relayHost` / `relayPort` | `test.e4mc.link` / `25575` | Relay used when the broker is disabled |
| `restoreDedicatedCommands` | `true` | Enable dedicated-server commands such as `/ban` and `/whitelist` |
| `useWhiteList` | `false` | Use a whitelist on LAN worlds |
| `offlineMode` | `false` | Run LAN worlds in offline mode with password login for non-premium players |
| `hostEnabled` | `true` | Share LAN worlds with e4mc |
| `dialtoneHostEnabled` | `true` | Enable Dialtone peer-to-peer connections as the host |
| `dialtonePlayerEnabled` | `true` | Enable Dialtone peer-to-peer connections as the player |
| `dialtoneRelayMap` | `https://natives.e4mc.link/relaymap.json` | URL of the Iroh relay list |
| `dialtoneSanitizeTicket` | `true` | Hide direct IP addresses from the relay |

## Building

```bash
./gradlew build -PcurseforgeApiToken=none -PmodrinthApiToken=none
```

The token properties are only used for publishing; any value works for a local build. Jars are written to `fabric/build/libs`, `forge/build/libs` and `neoforge/build/libs`.

## Authors

- **Chagui68** — E4MC Offline (offline mode)
- **Skye (skyevg)** — original author of e4mc
- Other contributors to the original e4mc: Schroddinger, beto-bit, Vazanoir, Tarteroyc, Sisby folk, NuruTheDoomer, Kena78

### Related repositories

The following repositories from the original project are also part of e4mc:

- [e4mc-quiclime](https://github.com/vgskye/e4mc-quiclime) — relay-side code
- Skye's fork of [netty-incubator-codec-quic](https://github.com/vgskye/netty-incubator-codec-quic)
- [iroh-java](https://github.com/vgskye/iroh-java) — used for Dialtone

## License

E4MC Offline is licensed under the [GNU General Public License v3.0](LICENSE).

It is based on e4mc, which is licensed under the MIT License. The original copyright notice and license are kept in [LICENSE-MIT](LICENSE-MIT), as that license requires.
