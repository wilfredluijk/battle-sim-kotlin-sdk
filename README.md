# Battle Sim Kotlin SDK

[![Kotlin CI](https://github.com/wilfredluijk/battle-sim-kotlin-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/wilfredluijk/battle-sim-kotlin-sdk/actions/workflows/ci.yml)

Kotlin/JVM SDK for bots in the battle-sim naval simulator, ported from
[battle-sim-python-sdk](https://github.com/wilfredluijk/battle-sim-python-sdk/tree/816fa9baba75c7294561a6f111aa2735f4bbdd01).
Requires **Java 17+**, **Kotlin 2.4.20+** for Kotlin consumers, and server **protocol 3.x**.

Includes typed protocol models, configuration agreement, multi-round WebSocket sessions,
all twelve powerups, diagnostics, Python-compatible recording/replay, and a tactical toolkit
for tracking, aiming, steering, sensors, and evasion. All production code is Kotlin;
the port has no Python or Java SDK runtime dependency.

## Build and run

```sh
git clone https://github.com/wilfredluijk/battle-sim-kotlin-sdk.git
cd battle-sim-kotlin-sdk
./mvnw verify
java -jar target/naval-sdk-kotlin-0.1.0-all.jar --help
java -jar target/naval-sdk-kotlin-0.1.0-all.jar --env-file /path/to/participant.env
```

Windows: use `mvnw.cmd`. The wrapper downloads Maven on first use. The executable JAR
runs the included patrol bot. Alternatively, download it from
[Releases](https://github.com/wilfredluijk/battle-sim-kotlin-sdk/releases).

Set `BATTLE_SERVER_URL` and `BATTLE_BOT_TOKEN` to the endpoint and participant credential
provided by your operator. The hosted workshop endpoint is `wss://93.190.187.250/bot`
and requires your assigned participant token. Without an endpoint override, the SDK uses
`ws://localhost:7878/bot`. The CLI supports `--url`, `--host`, `--port`, and `--env-file`;
credentials are read from the environment or participant file.

## Write a bot

```kotlin
import io.github.wilfredluijk.navalsdk.*

class MyBot : Bot() {
    override fun onTick(view: WorldView) = Command(throttle = 0.6, rudder = 0.2)
}

fun main() {
    run(MyBot(), RunOptions(name = "my-kotlin-bot"))
}
```

This bot moves in a circle. For tracking and firing, subclass `TacticalBot`:

```kotlin
import io.github.wilfredluijk.navalsdk.tactical.*

class Hunter : TacticalBot() {
    override fun decide(ctx: TacticalContext): Intent =
        ctx.threats.nearest()?.let(Intent::engage)
            ?: Intent.patrol(PatrolRect(100.0, 100.0, 600.0, 600.0))
}
```

See the complete [MyBot](examples/MyBot.kt) and [HunterBot](examples/HunterBot.kt) examples.
Both compile as part of the test build. Run the compiled examples after `verify`:

```sh
java -cp 'target/test-classes:target/naval-sdk-kotlin-0.1.0-all.jar' examples.HunterBotKt --env-file /path/to/participant.env
```

Use `;` as the classpath separator on Windows.

## Use from another project

Install the library in your local Maven repository:

```sh
./mvnw install
```

Gradle Kotlin DSL:

```kotlin
plugins { kotlin("jvm") version "2.4.20" }
repositories { mavenLocal(); mavenCentral() }
dependencies { implementation("io.github.wilfredluijk:naval-sdk-kotlin:0.1.0") }
kotlin { jvmToolchain(17) }
```

Maven:

```xml
<dependency>
  <groupId>io.github.wilfredluijk</groupId>
  <artifactId>naval-sdk-kotlin</artifactId>
  <version>0.1.0</version>
</dependency>
```

Artifacts are published as GitHub Release downloads. These coordinates are for local
installation; this project is **not published to Maven Central**. The regular JAR uses
Kotlin stdlib and Jackson dependencies declared in `pom.xml`; the `-all.jar` bundles them.

## Documentation

- [API and lifecycle](docs/api.md)
- [Protocol and configuration](docs/protocol.md)
- [Tactical toolkit](docs/tactics.md)
- [Powerups](docs/powerups.md)
- [Recording and diagnostics](docs/recording.md)
- [Migrating from Python](docs/migration.md)
- [Contributing and validation](CONTRIBUTING.md)

Validation includes 240 golden cases executed by the Python SDK, real WebSocket tests,
and an opt-in two-round integration test against the Rust server:

```sh
BATTLE_SIM_SERVER=/absolute/path/to/naval-server ./mvnw verify
```

MIT licensed. Original attribution and source revision are in [NOTICE](NOTICE).
