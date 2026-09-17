# Migrating from Python

The Kotlin port targets JVM 17+ and keeps the protocol and bot lifecycle. Public Kotlin
names use camelCase, while JSON remains snake_case.

| Python | Kotlin |
| --- | --- |
| `from naval_sdk import ...` | `import io.github.wilfredluijk.navalsdk.*` |
| `from naval_sdk.tactical import ...` | `import io.github.wilfredluijk.navalsdk.tactical.*` |
| `(x, y)` | `Vec2(x, y)` |
| `None` | `null` |
| `on_tick(view)` | `override fun onTick(view: WorldView): Command?` |
| `run(bot, name="x")` | `run(bot, RunOptions(name = "x"))` |
| `await run_async(...)` | `runAsync(...).get()` or integrate the returned `CompletableFuture` with your coroutine library |
| `sensor_mode="passive"` | `sensorMode = SensorMode.PASSIVE` |
| `Command(...).fire_at(...)` | `Command(...).fireAt(...)` |
| `Intent.patrol((x1,y1,x2,y2))` | `Intent.patrol(PatrolRect(x1,y1,x2,y2))` |
| `from_dict` / `to_dict` | `fromJson` / `toJson` with Jackson nodes |
| `with BotRecorder(path)` | `BotRecorder(Path.of(path)).use { ... }` |
| `replay(bot,path)` iterator | `replay(bot,path)` list, or `replay(bot,path) { decision -> ... }` streaming callback |
| `raw_recv()` | Managed callbacks and recording; the runtime owns its receive loop |

Subclass `Bot` or `TacticalBot` with `override` methods. Data classes support named
constructor arguments and `copy`. `Command` retains mutable fields and fluent `fireAt`.
Kotlin uses a sealed `Intent` hierarchy instead of a record with optional unrelated fields;
the lowercase factory methods retain the Python authoring style.

The port also tightens some edge cases: strict numeric parsing, defensive configuration
snapshots, stale-track pruning before association, separate known ship/shell kinds,
tracker reset on changed match IDs or backward ticks, and simulation-dt updates even when
match-start frames omit ship specs. These corrections are covered by regression tests.

The Python source is pinned in `NOTICE`. `PythonParityTest` compares 240 Python-generated
cases for compass/intercept math, commands, steering, tracking, fire control, and evasion.
The fixture can be regenerated with `tools/generate_python_parity.py` while the original
Python SDK is installed or on `PYTHONPATH`. Kotlin builds require no Python installation.

The Java and Kotlin ports use the same package prefix but different Maven artifacts.
Use one SDK per application; both define core model names in that package.
