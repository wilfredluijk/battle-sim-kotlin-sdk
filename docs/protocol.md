# Protocol and configuration

The port targets protocol **3.x**, based on Python source revision
`816fa9baba75c7294561a6f111aa2735f4bbdd01`. The committed `protocol3.json` test fixture is copied
unchanged from that SDK.

1. Client sends `hello` with name, SDK version, and participant token.
2. Server sends `welcome` with identity, map, specs, configuration, and `config_hash`.
3. SDK checks both protocol versions and parses the authoritative configuration.
4. Bot accepts configuration, selects its loadout, and sends `ready` with the exact hash.
5. Configuration changes repeat acceptance/loadout/readiness.
6. `game_start` establishes `match_id`, simulation dt, and optional match-specific specs.
7. Each `tick` gets a `command` containing that frame's tick and match ID.
8. `game_over` ends a round. A subsequent `lobby` clears match identity and resends readiness.

`Welcome.rules` gives a typed `MatchConfiguration` when configuration is present.
Its fields are `protocolVersion`, `revision`, `simulationDt`, `tickHz`, `deadlineMs`, `map`,
`shipSpecs`, `sensors`, `powerups`, `availablePowerups`, `matchTimeoutTicks`, `wallBumpDamage`,
`emptyLoadout`, and `ownShipTelemetry`. `configuration` and `raw` preserve unknown fields
and return defensive copies. The opaque hash is acknowledged exactly as received.

`ShipSpecs` supplies speed, acceleration, turning, hull HP, ammo, gun cooldown, hit radius,
shell speed/range, splash radius, and splash damage. `SensorConfig` supplies active/passive
ranges and noise values. `PowerupConfig` exposes every Python tuning field in camelCase.
Configuration validates positive ranges/durations and nonnegative damage/noise values;
counts must be integers and confidence must be between zero and one.

Use `simulationDt` for velocity, cooldown physics, and prediction. `tickHz` controls pacing
and can change without changing simulated seconds per tick. Match-start specs may differ
from lobby specs; `onWelcome` is called before the start callback when these change.

Unknown message types are ignored. Malformed frames increment `malformedFrames` and do not
crash the receive loop. Unknown contact kinds stay strings, and unknown event payloads are
retained as `UnknownEvent`. Unsupported protocol majors fail explicitly.

Selection requires zero or two distinct advertised powerup IDs. Switching from a nonempty
loadout to empty in the same lobby requires the `empty_loadout` capability; otherwise the
SDK stays unready. The server clears loadouts between matches. Older protocol 3 servers may
omit `gun_cooldown_ticks_left`; the gunner then reconciles shots from ammo changes.
