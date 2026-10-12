# gradle-versions-plugin: conversion from Gradle Kotlin DSL to Gradle XDCL

Date: 2026-10-12

## Status

The conversion is committed and pushed to a fork, but the full spec suite did not get a clean run:
the specs that pin stock Gradle 9.8.1 could not download that distribution on this machine (about
32 KB/s), so the full build was cancelled.

| | |
|---|---|
| Fork | `abstratt/gradle-versions-plugin` |
| Branch | `xdcl-conversion` |
| Commit | `da2b096`, one commit on top of upstream `master` (`4dad3a8`) |
| Compare / open a PR | https://github.com/abstratt/gradle-versions-plugin/pull/new/xdcl-conversion |
| Local remotes | `fork` (the fork), `origin` (upstream `ben-manes/gradle-versions-plugin`, untouched) |

Only the fork was pushed to. No pull request was opened.

## Decisions taken

| Question | Choice |
|---|---|
| Which project | `gradle-versions-plugin` |
| Scope | Both the project's own build and the plugin's consumer-facing configuration |
| Where the XDCL surface lives | In place, in the existing module, compiled against the XDCL distribution |
| How far to convert the build | Declarative where expressible |
| Options that take lambdas | Scalar options plus data-shaped replacements for the common predicates |

Why the module keeps a Kotlin build script: the built-in declarative project type
(`xdclGradlePluginKotlin`) only exposes `api` / `implementation` / `compileOnly` / `runtimeOnly`
dependencies. This build also has Spock suites on several JDKs, ktlint, dokka and plugin-publish,
so the module uses the documented imperative fallback (`id("xdcl-gradle-plugin")` in
`build.gradle.kts`), the same setup `xdcl-sample-build/build-logic` uses.

## What changed

### The project's own build

- `settings.gradle.kts` is replaced by `settings.gradle.xdcl`, with a comment-only
  `settings.gradle` marker stub beside it.
- The root `build.gradle.kts` is deleted; its `allprojects {}` / `subprojects {}` configuration is
  folded into `gradle-versions-plugin/build.gradle.kts`.
- The wrapper is re-pinned from stock Gradle 9.8.1 to the XDCL distribution
  `9.8.0-branch-xdclRelease-20261012070500+0000`, checksum-pinned.
- `gradle-versions-plugin/build.gradle.kts` now applies `xdcl-gradle-plugin` and compiles against
  the running distribution's API.
- The `io.github.ben-manes.versions` plugin is registered from its XDCL descriptor instead of the
  `gradlePlugin {}` block. The other three plugin ids stay in `gradlePlugin {}`.

### The plugin's XDCL surface

New files under `gradle-versions-plugin/src/main/`:

| File | Purpose |
|---|---|
| `xdcl/versions.xdsl` | Schema: `extension DependencyUpdates for Settings`, plus `Revision`, `ReleaseChannel`, `OutputFormat` and `RejectRule` |
| `xdcl/io.github.ben-manes.versions.xdcl` | Plugin descriptor: metadata, `implementationClass`, `reactions` |
| `kotlin/.../versions/xdcl/DependencyUpdatesReaction.kt` | `Reaction<DependencyUpdates, Settings>` that configures the root project's `dependencyUpdates` task |

`VersionsPlugin.kt` applies the generated carrier
(`xdcl.generated.plugins.IoGithubBenManesVersionsPlugin`) when applied to settings, and only where
the XDCL API exists, so the plugin still loads on a stock Gradle.

Consumer usage:

```
settings {
  plugins [
    { id "io.github.ben-manes.versions", version "$version" }
  ]

  dependencyUpdates {
    revision :release
    outputFormatter [:plain, :json]
    rejectPreReleases true
    preReleasePatterns ["-nf-"]
    rejectVersions [
      { group "com.google.guava", versionPattern "-android$", reason "The build uses the jre variant" }
    ]
    excludeConfigurations ["jacocoAnt"]
  }
}
```

Property mapping:

| Property of the block | Task property | Value |
|---|---|---|
| `revision` | `revision` | `:release`, `:milestone` or `:integration` |
| `gradleReleaseChannel` | `gradleReleaseChannel` | `:current`, `:release-candidate` or `:nightly` |
| `outputFormatter` | `outputFormatter` | a list of `:plain`, `:json`, `:xml`, `:html`, `:problems` |
| `outputDir` | `outputDir` | a path |
| `reportFileName` | `reportfileName` | a file name without its extension |
| `checkForGradleUpdate` | `checkForGradleUpdate` | `true` or `false` |
| `gradleVersionsApiBaseUrl` | `gradleVersionsApiBaseUrl` | a URL |
| `checkConstraints` | `checkConstraints` | `true` or `false` |
| `checkBuildEnvironmentConstraints` | `checkBuildEnvironmentConstraints` | `true` or `false` |
| `rejectOutOfBounds` | `rejectOutOfBounds` | `true` or `false` |
| `rejectPreReleases` | `rejectPreReleases` | `true` or `false` |
| `checkEmbeddedKotlin` | `checkEmbeddedKotlin` | `true` or `false` |
| `excludeConfigurations` | `filterDeclaredConfigurations` | names of the configurations to leave out |
| `preReleasePatterns` | `preReleaseVersionIf` | regular expressions searched for in a version |
| `rejectVersions` | `resolutionStrategy` component selection (as `rejectVersionIf` does) | rules of optional `group` and `module`, a `versionPattern`, optional `reason` |

Every property is optional with no schema default. The reaction sets only what the block declares,
so the task's own defaults, command-line options and system properties keep their precedence.

Still Kotlin/Groovy-only, because XDCL holds data and no code: `resolutionStrategy`,
`filterConfigurations`, `exemptFromBuiltInChecksIf`, a custom `outputFormatter`, and any
`rejectVersionIf` / `preReleaseVersionIf` rule the patterns cannot express.

### Removed

- The compile against the Gradle 8.4 API (`gradle-api-minimum`, `groovy-minimum` and related
  catalog entries).
- The Java 8 target; Kotlin, Java and Groovy now target 17.
- The `minimumGradleTest` source set and the `testOn8` / `testOn11` tasks.

### Tests, example, docs, CI

- New `XdclSettingsSpec.groovy` with 8 feature methods.
- New `examples/xdcl/` (`settings.gradle.xdcl`, `settings.gradle` stub, `build.gradle`).
- `README.md`: new section "Configuring from XDCL", Samples and Compatibility updated.
- `CONTRIBUTING.md`: build and test notes updated for the XDCL distribution and Java 17.
- `.github/workflows/build.yml`: matrix reduced to JDK 17 and 21, `-x testOn8` dropped,
  `examples/xdcl` added to the examples step.

## Verification

### Verified

| Check | Result |
|---|---|
| `xdcl-gradle-sync sync .` | Succeeded |
| `xdcl-lsp-client validate` on `settings.gradle.xdcl` and `versions.xdsl` | Clean |
| `xdclCodegen` | Descriptor accepted, facades and carrier generated |
| ktlint and `validatePlugins` | Pass |
| `XdclSettingsSpec` | 8 of 8 pass on the XDCL distribution, including configuration-cache reuse and command-line precedence |
| Existing suite | 460 tests had passed when the build was cancelled |
| Built plugin on stock Gradle 9.7.1, run by hand | Reports correctly, applied in settings and applied in a project |
| `examples/xdcl` | Runs end to end; text and JSON reports written with the rules applied |

All 28 failures in the cancelled full build were
`Could not install Gradle distribution … gradle-9.8.1-bin.zip` lock timeouts. A later targeted run
of 39 tests had one failure, with the same cause.

### Not verified

- The specs pinned to stock Gradle 9.8.1 never ran. Whether the 8.4-pinned ones ran was not
  checked.
- `testOnAllJdks` (17, 21) was not run.
- `examples/kotlin` and `examples/groovy` were not re-run, so `publishToMavenLocal` was not run
  either.

CI on the fork fetches the distributions normally and is the place to confirm the full suite.

## Things to be aware of

- **Dropped settings.** The Develocity `buildScan` block (terms-of-use agreement, publish only on
  CI) and `FAIL_ON_PROJECT_REPOS` have no equivalent in the settings schema. The Develocity plugin
  is still applied, but CI no longer publishes scans automatically.
- **TestKit and schemas.** A `settings.gradle.xdcl` does not see a plugin's schema from TestKit's
  injected classpath (`unknown property 'dependencyUpdates'`). The new spec resolves the plugin from
  the build's local `specs-repository` instead, which is now wired into every test task.
- **Marker publication task name.** The descriptor-registered plugin is declared under its id, so
  the marker POM task is `generatePomFileForIo.github.ben-manes.versionsPluginMarkerMavenPublication`.
  `specsRepository` now derives the name from the plugin declaration.
- **Example and the editor.** `examples/xdcl` includes the plugin build with
  `includedBuilds ["../.."]`. Gradle runs it, but `xdcl-lsp-client` reports
  `unknown property 'dependencyUpdates'` there because the sync drops schema roots outside the
  build tree.
- **`testOn<N>` collision.** When another build includes this one on JDK 17 or 21, the build-JDK
  task and the loop both registered `testOn21`. The loop now skips the build JDK.
- **Left alone.** `.sdkmanrc` still names Java 8 (the plan said to update it), and the CI step that
  runs the examples on Gradle 8.4 is still there.
- **Wrapper generation.** The wrapper was generated with the local XDCL install at
  `~/xdcl/gradle-9.8.0-20261012070500+0000`, because the stock 9.8.1 download was locked by
  another process.

## Reproducing the checks

```bash
# From the repository root
xdcl-gradle-sync sync .
xdcl-lsp-client validate --root . settings.gradle.xdcl gradle-versions-plugin/src/main/xdcl/versions.xdsl
./gradlew :gradle-versions-plugin:xdclCodegen
./gradlew build
./gradlew testOnAllJdks

# The new spec alone
./gradlew :gradle-versions-plugin:test --tests '*XdclSettingsSpec'

# The XDCL example
(cd examples/xdcl && ../../gradlew dependencyUpdates)
```
