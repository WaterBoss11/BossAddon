# libs/ — local dependency drop-in

Drop your **AUTISM API jar** here:

```
libs/autism-3.4.jar
```

The Gradle build resolves the AUTISM Client API from this folder via a `flatDir`
repository (see `build.gradle.kts`). The file name must match the coordinate in
`gradle/libs.versions.toml` — i.e. `autism-<version>.jar` (currently `autism-3.4.jar`).

**This jar is NOT included in version control** (`.gitignore` excludes `libs/*.jar`).
Each builder must supply their own copy of the AUTISM API jar, obtained separately
from the AUTISM Client distribution.

If you bump the `autism` version in `gradle/libs.versions.toml`, drop the matching
`autism-<new-version>.jar` here as well.
