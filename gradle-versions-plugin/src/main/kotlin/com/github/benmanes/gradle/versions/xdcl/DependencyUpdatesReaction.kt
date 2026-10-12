package com.github.benmanes.gradle.versions.xdcl

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionRulesWithCurrent
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionWithCurrent
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.IsolatedAction
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.specs.Spec
import org.gradle.api.xdcl.Reaction
import org.gradle.api.xdcl.ReactionScope
import java.io.Serializable
import java.util.regex.Pattern

/**
 * Applies the `dependencyUpdates` block of a `settings.gradle.xdcl` to the root project's
 * `dependencyUpdates` task, whose shared settings every other project inherits. Only the properties
 * that the block declares are set, so the rest keep the task's defaults.
 */
class DependencyUpdatesReaction : Reaction<DependencyUpdates, Settings> {
  override fun on(
    data: DependencyUpdates,
    context: Settings,
    scope: ReactionScope,
  ) {
    val configuration =
      DependencyUpdatesConfiguration(
        revision = data.revision().orNull?.toString(),
        gradleReleaseChannel = data.gradleReleaseChannel().orNull?.toString(),
        outputFormatter = data.outputFormatter().orNull?.joinToString(","),
        outputDir = data.outputDir().orNull,
        reportFileName = data.reportFileName().orNull,
        checkForGradleUpdate = data.checkForGradleUpdate().orNull,
        gradleVersionsApiBaseUrl = data.gradleVersionsApiBaseUrl().orNull,
        checkConstraints = data.checkConstraints().orNull,
        checkBuildEnvironmentConstraints = data.checkBuildEnvironmentConstraints().orNull,
        rejectOutOfBounds = data.rejectOutOfBounds().orNull,
        rejectPreReleases = data.rejectPreReleases().orNull,
        checkEmbeddedKotlin = data.checkEmbeddedKotlin().orNull,
        excludeConfigurations = data.excludeConfigurations().getOrElse(emptyList()).toSet(),
        preReleasePatterns = data.preReleasePatterns().getOrElse(emptyList()).map(Pattern::compile),
        rejectVersions =
          data.rejectVersions().getOrElse(emptyList()).map { rule ->
            RejectedVersions(
              group = rule.group().orNull,
              module = rule.module().orNull,
              versionPattern = Pattern.compile(rule.versionPattern().get()),
              reason = rule.reason().orNull,
            )
          },
      )
    context.gradle.lifecycle.beforeProject(ConfigureRootProject(configuration))
  }
}

/** The values of a `dependencyUpdates` block, where null is a property that the block omits. */
internal data class DependencyUpdatesConfiguration(
  val revision: String?,
  val gradleReleaseChannel: String?,
  val outputFormatter: String?,
  val outputDir: String?,
  val reportFileName: String?,
  val checkForGradleUpdate: Boolean?,
  val gradleVersionsApiBaseUrl: String?,
  val checkConstraints: Boolean?,
  val checkBuildEnvironmentConstraints: Boolean?,
  val rejectOutOfBounds: Boolean?,
  val rejectPreReleases: Boolean?,
  val checkEmbeddedKotlin: Boolean?,
  val excludeConfigurations: Set<String>,
  val preReleasePatterns: List<Pattern>,
  val rejectVersions: List<RejectedVersions>,
) : Serializable

/** The candidate versions that a `rejectVersions` rule matches. */
internal data class RejectedVersions(
  val group: String?,
  val module: String?,
  val versionPattern: Pattern,
  val reason: String?,
) : Serializable

// The actions and predicates below are named classes that hold only the block's values, so that the
// state captured for an isolated project and stored in the configuration cache is plain data.

private class ConfigureRootProject(private val configuration: DependencyUpdatesConfiguration) : IsolatedAction<Project> {
  override fun execute(project: Project) {
    if (project.path == ":") {
      project.tasks.withType(DependencyUpdatesTask::class.java).configureEach(ConfigureTask(configuration))
    }
  }
}

private class ConfigureTask(private val configuration: DependencyUpdatesConfiguration) :
  Action<DependencyUpdatesTask>,
  Serializable {
  override fun execute(task: DependencyUpdatesTask) {
    configuration.revision?.let { task.revision = it }
    configuration.gradleReleaseChannel?.let { task.gradleReleaseChannel = it }
    configuration.outputFormatter?.let { task.outputFormatter = it }
    configuration.outputDir?.let { task.outputDir = it }
    configuration.reportFileName?.let { task.reportfileName = it }
    configuration.checkForGradleUpdate?.let { task.checkForGradleUpdate = it }
    configuration.gradleVersionsApiBaseUrl?.let { task.gradleVersionsApiBaseUrl = it }
    configuration.checkConstraints?.let { task.checkConstraints = it }
    configuration.checkBuildEnvironmentConstraints?.let { task.checkBuildEnvironmentConstraints = it }
    configuration.rejectOutOfBounds?.let { task.rejectOutOfBounds = it }
    configuration.rejectPreReleases?.let { task.rejectPreReleases = it }
    configuration.checkEmbeddedKotlin?.let { task.checkEmbeddedKotlin = it }
    if (configuration.excludeConfigurations.isNotEmpty()) {
      task.filterDeclaredConfigurations = NotNamed(configuration.excludeConfigurations)
    }
    if (configuration.preReleasePatterns.isNotEmpty()) {
      task.preReleaseVersionIf(MatchesAny(configuration.preReleasePatterns))
    }
    if (configuration.rejectVersions.isNotEmpty()) {
      task.resolutionStrategy(RejectVersions(configuration.rejectVersions))
    }
  }
}

private class NotNamed(private val names: Set<String>) :
  Spec<String>,
  Serializable {
  override fun isSatisfiedBy(name: String): Boolean = name !in names
}

private class MatchesAny(private val patterns: List<Pattern>) :
  Spec<String>,
  Serializable {
  override fun isSatisfiedBy(version: String): Boolean = patterns.any { it.matcher(version).find() }
}

private class RejectVersions(private val rules: List<RejectedVersions>) :
  Action<ResolutionStrategyWithCurrent>,
  Serializable {
  override fun execute(strategy: ResolutionStrategyWithCurrent) {
    strategy.componentSelection(RejectSelections(rules))
  }
}

private class RejectSelections(private val rules: List<RejectedVersions>) :
  Action<ComponentSelectionRulesWithCurrent>,
  Serializable {
  override fun execute(selection: ComponentSelectionRulesWithCurrent) {
    selection.all(RejectSelection(rules))
  }
}

private class RejectSelection(private val rules: List<RejectedVersions>) :
  Action<ComponentSelectionWithCurrent>,
  Serializable {
  override fun execute(selection: ComponentSelectionWithCurrent) {
    val candidate = selection.candidate
    val rule =
      rules.firstOrNull {
        (it.group == null || it.group == candidate.group) &&
          (it.module == null || it.module == candidate.module) &&
          it.versionPattern.matcher(candidate.version).find()
      }
    if (rule != null) {
      selection.reject(rule.reason ?: "Rejected by rejectVersions")
    }
  }
}
