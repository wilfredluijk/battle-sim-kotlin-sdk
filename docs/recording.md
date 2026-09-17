# Recording and diagnostics

```kotlin
import java.nio.file.Path
import io.github.wilfredluijk.navalsdk.*

BotRecorder(Path.of("observations.jsonl")).use { recorder ->
    run(MyBot(), RunOptions(name = "recorded-bot", recorder = recorder))
}

val decisions = replay(MyBot(), Path.of("observations.jsonl"))
for (decision in decisions) {
    println("${decision.matchId}: ${decision.tick} -> ${decision.command}")
}
```

The recorder creates a new UTF-8 JSONL file and refuses to overwrite an existing one.
It records incoming welcome/configuration/start/tick/end/lobby/error frames and never
outbound hello or commands. Nested keys containing token/password/credential/authorization
are redacted. Arbitrary text inside server-provided strings is not semantically scrubbed;
do not place credentials in custom messages or bot names.

Files use the Python SDK's `naval-sdk-bot-views` version 1 format and can be exchanged
between SDKs. Replay passes frames through the same configuration and lifecycle dispatcher
as the live runtime. It calls callbacks and records decisions without a connection.
Observations stay fixed: new decisions do not re-simulate the battle or predict a new outcome.

For large recordings, use the callback overload so decisions are consumed as they are read:

```kotlin
replay(MyBot(), Path.of("observations.jsonl")) { decision ->
    println(decision.tick)
}
```

The reader is always closed, including when a callback fails. A bot cannot be replayed
while its live runtime is active. Replay honors `onGameOver` returning false and fatal
server error frames.

`bot.diagnostics` exposes `ticks`, `overruns`, `callbackErrors`, `malformedFrames`,
`rejectedCommands`, `lastTiming`, and `lastDisconnect`. Runtime measurements and errors do
not contain the outbound authentication frame. `TickTiming` includes match/tick identity,
elapsed milliseconds, budget, and whether it was exceeded. `DisconnectInfo` contains an
optional WebSocket close code, reason, and interrupted phase.

Use `onTickTiming` and `onDisconnect` to consume diagnostics from the callback thread.
Read aggregate counters after the runtime future completes; they are runtime-owned and
are not a concurrent metrics API. CPU time includes callbacks and command validation,
excluding network latency. A slow synchronous callback cannot be forcibly interrupted
by its game deadline.
