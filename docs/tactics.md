# Tactical toolkit

Subclass `TacticalBot` and override `decide(ctx): Intent`. The framework constructs a
`Tracker`, `Gunner`, `Helm`, and `Evader` from the welcome configuration. Customize them in
`onTacticalWelcome`; the properties can be replaced with subclasses. Call the superclass
implementation when overriding `onWelcome` or `onGameStart` to preserve setup/reset behavior.

The order is: update tracker and gunner; apply evasion if hit; obtain player intent; steer
with wall avoidance; choose sensors; attempt a shot. Evasion preempts intent. `Intent.Custom`
bypasses steering, sensors, and firing overlays after evasion. `Intent.Hold` does not fire.

| Intent | Behavior |
| --- | --- |
| `Intent.engage(track)` | Steer toward and attempt to shoot that track. |
| `Intent.patrol(PatrolRect(x1,y1,x2,y2))` | Visit rectangle corners and shoot the nearest threat. |
| `Intent.retreatTo(Vec2(x,y))` | Steer to the point, optionally firing at the nearest threat. |
| `Intent.hold()` | Stop movement and hold fire. |
| `Intent.custom(command)` | Return an explicit command. |

`TacticalContext` provides `view`, `me`, `specs`, `tracker`, `threats`, `mapWidth`, and
`mapHeight`. `ThreatList` is iterable with `size`, `isEmpty`, `nearest`, `farthest`, and `byId`.

## Tracking

`Tracker(specs, tickHz, simulationDt, activeGate, passiveBearingGateDeg, velocityAlpha,
velocityWindowTicks, stalenessTicks)` creates stable track IDs from unstable contact IDs.
`update(view)` returns tracks sorted by ID; `tracks`, `get(id)`, and `reset()` expose state.

Active contacts associate by predicted distance. Passive contacts associate by bearing and
never create a new track. `Track.pos` predicts to the current tick; `observedPos` retains the
last active position; `vel` is units per simulation second. Tracks also expose `kind`,
`lastSeenTick`, `firstSeenTick`, `lastActiveTick`, `confidence`, and `source` (`active`,
`passive`, or `dead_reckoned`). Shells and ships stay separate, and stale tracks are pruned
before association. A changed match ID or backward tick resets the tracker.

## Fire control

`Gunner(specs, selfSplashMargin, maxActiveAgeTicks, requireRecentActive, powerups, simulationDt)`
provides pure `solve(me, track, view, activatePowerup)` returning `FireSolution?`.
It checks ammo/cooldown, freshness, intercept feasibility, range, current self-splash distance,
and estimated own-ship position at impact. The predictor assumes constant velocity;
steering changes after the shot are not simulated.

Call `noteFired(tick, cooldownTicks, ammo)` only after committing a solution, or use
`attempt(command, me, track, view)` to solve, attach, and record it. `update(view)` reconciles
cooldown telemetry or unchanged ammo after a rejected shot. `effectiveWeapons` incorporates
rapid fire, heavy shells, long range, and EMP. Same-command activation is included when the
powerup is ready. Positive half-tick cooldowns round upward like the Rust server.

## Steering, sensing, evasion

`Helm.steerToBearing` and `steerToPoint` return destructurable `Steering(throttle, rudder)`.
Both accept `respectWalls` and `desiredThrottle`. Sharp turns reduce throttle; lookahead
accounts for braking, turning, and overdrive before redirecting inward at a wall. This is
a conservative helper, not a collision guarantee.

Implement `SensorPolicy.choose(view, tracker)` and optional `reset()`, or use `AlwaysActive`,
`AlwaysPassive`, `DutyCycle(activeTicks, passiveTicks)`, or `PingWhenStale(staleThresholdTicks)`.
The last policy pings when any tracked ship lacks a recent active fix. Passive observations
do not refresh range estimates.

`Evader(evasionTicks, cooldownTicks, throttle, initialRudderSign)` reacts to `HitEvent` with
full rudder, enters cooldown, and reverses rudder if hit during cooldown. It exposes `state`,
`update(view): Command?`, and `reset()`. Match starts reset tracking, gun cooldown, evasion,
sensor policy state, and the patrol cursor.
