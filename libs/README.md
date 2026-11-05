# Local AUTISM Client API jars

Supply the host jars separately from the corresponding AUTISM Client distributions:

```text
libs\autism-5.1-26.2.jar        V4 / 5.1, API v4 (default target)
libs\autism-5.0-26.2-dev.jar   Public 5.0, API v3
```

The [build](../build.gradle.kts) uses a `flatDir` repository. The file name must match
`autism` in the [version catalog](../gradle/libs.versions.toml), or the explicit
`-PhostClientVersion=...` override. Build each target with
its matching jar. The override leaves the default `5.1-26.2` selector unchanged.

These dependency jars are ignored by Git and are **not bundled in BossAddon**. Do not
commit credentials or copy a live Minecraft profile here. See the [main README](../README.md)
for Java 25, artifact selection, and build instructions.
