# Contributing

Use Java 17 or newer. Build, test, and package with `./mvnw verify` (`mvnw.cmd` on Windows).
Maven and all build/dependency versions are pinned. The library targets JVM 17 and Kotlin
2.4.20 metadata. CI covers Java 17, 21, and 25. Keep examples and docs aligned with API changes.

The normal suite includes strict protocol parsing, lifecycle/configuration negotiation,
powerups, error fallback, multi-round resets, tactical regression cases, CLI/recording,
actual loopback WebSockets, and 240 Python golden vectors. The optional Rust test is skipped
unless `BATTLE_SIM_SERVER` points to a built `naval-server` binary:

```sh
BATTLE_SIM_SERVER=/absolute/path/to/naval-server ./mvnw verify
```

That test launches an isolated local process with synthetic credentials, runs two Kotlin
bots for two rounds, changes configuration, checks telemetry/loadouts, records observations,
and replays them. It terminates its server on completion.

To regenerate cross-language vectors, use the source revision in `NOTICE` and install its
Python dependencies, then run `python tools/generate_python_parity.py`. Review fixture changes
against upstream behavior; do not regenerate expected values from the Kotlin implementation.

Kotlin sources follow ktfmt's Kotlin style. Do not commit tokens, participant environment
files, generated recordings, or build output. The build includes LICENSE and NOTICE in JARs.

The `examples` directory is compiled with tests. GitHub Releases distribute the library JAR,
source JAR, bundled executable JAR, POM, and SHA-256 checksums. This repository does not deploy
artifacts to Maven Central. Before releasing, run the suite and the real-server test, update
the version/docs/changelog, create a version tag, and publish the verified artifacts.
