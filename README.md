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

Then open the world to LAN. Remote players join without Mojang authentication. The host keeps their real profile.

> **Warning:** in offline mode anyone with the domain can join using any username. Whitelists, bans and operator status are tied to names, so they can be bypassed by impersonating another player. Only share the domain with people you trust.

Offline mode only affects worlds hosted from the client (integrated server). It never changes dedicated servers.

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
| `offlineMode` | `false` | Run LAN worlds in offline mode (no Mojang authentication) |
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
