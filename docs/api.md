# API and lifecycle

Import `io.github.wilfredluijk.navalsdk.*` for the core API and
`io.github.wilfredluijk.navalsdk.tactical.*` for tactical helpers.

## Bot callbacks

Subclass `Bot`; callbacks run serially on the thread executing `run`, or on the dedicated
daemon thread created by `runAsync`. They must finish within the tick's `deadlineMs`.
The SDK measures overruns but cannot forcibly stop a callback.

| Callback | Purpose |
| --- | --- |
| `acceptConfiguration(configuration, configHash): Boolean` | Return false to stay unready. Receives a defensive JSON snapshot. |
| `onWelcome(welcome)` | Cache settings; called again for updated configuration or start-time specs. |
| `choosePowerups(welcome): List<String>` | Return zero or two distinct IDs from the advertised catalog. |
| `onGameStartEvent(start)` | Typed match start; `matchId` is already set. |
| `onGameStart(tick, startingPosition, startingHeadingDeg)` | Positional compatibility hook, called by the default typed start callback. |
| `onTick(view): Command?` | Return one command. Null means hold station. |
| `onGameOver(result): Boolean` | True participates in later rounds; false disconnects. |
| `onLobby(tick)` | Reset bot-owned match state before loadout/readiness are resent. |
| `onError(code, message)` | Observe a server rejection. |
| `onTickTiming(timing)` | Observe decision/serialization duration, excluding network latency. |
| `onDisconnect(info)` | Observe the phase interrupted by a close. |

Exceptions from callbacks increment `diagnostics.callbackErrors`. Failed tick callbacks or
invalid commands produce a zero-throttle, zero-rudder, active-sensor command with the exact
match ID and tick. Interrupted callbacks propagate cancellation. Fatal authentication/name
errors stop the session. Protocol mismatch throws `ProtocolMismatch`.

`Bot` exposes `welcome`, `lastTick`, `matchId`, `phase`, and `diagnostics`. `rawSend(ObjectNode)`
is an escape hatch; callers supply valid frames themselves. The managed runtime owns the
only receive loop. Use callbacks or a recorder to inspect incoming frames.

## Connection

`run(bot, RunOptions(...))` blocks and returns the most recent `GameOver?`.
`runAsync(bot, options)` returns `CompletableFuture<GameOver?>` on a dedicated daemon thread;
retain/await it so a command-line process remains alive. `cancel(true)` interrupts that
thread and aborts the socket. Do not run or replay the same bot concurrently.

`RunOptions` has `url`, `token`, `name`, `version`, `reconnectAttempts`, `reconnectDelay`, and
`recorder`. Defaults use `BATTLE_SERVER_URL`, `BATTLE_BOT_TOKEN`, bot name `bot`, zero retries,
and one-second retry delay. Retry delays must be 0–60 seconds. Only lobby/ended connections
are retried; dropping an active match forfeits the ship and never silently resumes it.
Connection/welcome timeouts are ten seconds; incoming frames and queues are bounded.

The default endpoint is `ws://localhost:7878/bot`. URLs must use `ws` or `wss`, with no
embedded credentials, query, or fragment. The CLI additionally requires the `/bot` path.
The hosted workshop endpoint is `wss://93.190.187.250/bot` and requires the participant
token assigned to your team.
`ConnectionArguments.resolve(args, environment, name)` returns `RunOptions` and parses
`--url`, `--host`, `--port`, and `--env-file`. A selected file replaces both ambient settings;
an explicit endpoint overrides its URL. File contents are parsed as data, never executed.

## Data and commands

`WorldView` contains `tick`, `deadlineMs`, `matchId`, `selfState` (`me`), `contacts`, and `events`.
`nearestContact()` ignores contacts without range. A `Contact` holds `id`, `kind`, `pos`,
`bearingDeg`, nullable `range`, and `confidence`. Contact IDs are not stable between ticks.

`SelfState` contains position, heading, speed, HP, ammo, rudder, throttle, selected powerups,
powerup statuses, optional gun cooldown telemetry, and EMP ticks remaining. Use
`powerup(id)`, `powerupReady(id)`, and `powerupActive(id)` for loadout checks.

`TickEvent` is sealed: `HitEvent`, `ShellSplashEvent`, `PowerupActivatedEvent`, or
`UnknownEvent(raw)`. Future or malformed events remain inspectable without discarding the tick.
All wire models provide `fromJson(JsonNode)` companion factories.

`Command(throttle, rudder, sensorMode, fire, activatePowerup)` is mutable and has defaults.
`sensorMode` is `SensorMode.ACTIVE` or `PASSIVE`. `FireCommand(bearingDeg, range)` supplies a
shot. `toJson(tick, matchId)` retains protocol snake_case and validates finite f32 numbers.
The server clamps movement values to [-1, 1].

```kotlin
val command = Command(throttle = 0.6)
command.fireAt(
    targetPos = target.pos,
    shooterPos = view.me.pos,
    targetVel = target.vel,
    shellSpeed = welcome.shipSpecs.shellSpeed,
)
```

`fireAt` mutates and returns the command. It accepts optional `range` and `lead = false`.
If no lead solution exists, it aims at the current target position; use `Gunner.solve` for
feasibility and self-splash checks. Always pass the actual shooter position.

`Vec2(x, y)` supports addition, subtraction, scalar multiplication, and destructuring.
Helpers: `distance`, `bearingTo`, `leadTarget`, `wrapBearing`, `signedBearingDelta`, `clamp`.
Bearings increase clockwise: 0° north (-y), 90° east (+x). Velocities use simulation seconds.
