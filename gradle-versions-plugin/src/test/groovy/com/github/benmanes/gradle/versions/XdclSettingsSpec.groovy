package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

/**
 * Configures the plugin from the {@code dependencyUpdates} block of a {@code settings.gradle.xdcl},
 * on the Gradle running this build, which is the only one in these specs that reads such a script.
 * The plugin is resolved from a repository, as the script reads the block's schema from the plugin
 * that it resolves and not from the classpath that TestKit injects.
 */
final class XdclSettingsSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()

  def 'setup'() {
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java'
        }

        repositories {
          maven {
            url = '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
  }

  private void settings(String block) {
    def repository = new File(System.getProperty('specsRepository')).absoluteFile.toURI()
    testProjectDir.newFile('settings.gradle.xdcl') <<
      """
        settings {
          pluginManagement {
            repositories ["${repository}", :mavenCentral]
          }
          plugins [
            { id "${System.getProperty('pluginId')}", version "${System.getProperty('pluginVersion')}" }
          ]
          rootProject { name "xdcl" }
          ${block}
        }
      """.stripIndent()
  }

  private def runner(String... arguments) {
    return TestKitRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments([':dependencyUpdates', '--no-check-for-gradle-update'] + arguments.toList())
  }

  def 'Reports with the defaults when the block is omitted'() {
    given:
    settings('')

    when:
    def result = runner().build()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 2.2 -> 3.1]')
  }

  def 'Applies the scalar properties of the block'() {
    given:
    settings('''
      dependencyUpdates {
        revision :release
        outputFormatter [:json]
        outputDir "build/updates"
        reportFileName "versions"
        checkForGradleUpdate false
      }
    ''')

    when:
    def result = runner().build()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    def report = new File(testProjectDir.root, 'build/updates/versions.json')
    report.exists()
    report.text.contains('"name": "guice"')
  }

  def 'A command line option takes precedence over the block'() {
    given:
    settings('''
      dependencyUpdates {
        outputFormatter [:json]
      }
    ''')

    when:
    runner('--output-formatter=xml').build()

    then:
    new File(testProjectDir.root, 'build/dependencyUpdates/report.xml').exists()
    !new File(testProjectDir.root, 'build/dependencyUpdates/report.json').exists()
  }

  def 'Rejects the versions that a rule matches'() {
    given:
    settings('''
      dependencyUpdates {
        rejectVersions [
          { group "com.google.inject", module "guice", versionPattern r"^3\\.", reason "No 3.x yet" }
        ]
      }
    ''')

    when:
    def result = runner().build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 2.2]')
    !result.output.contains('3.1')
  }

  def 'Leaves a rule that names another module out of the selection'() {
    given:
    settings('''
      dependencyUpdates {
        rejectVersions [
          { module "guava", versionPattern ".*" }
        ]
      }
    ''')

    when:
    def result = runner().build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 2.2 -> 3.1]')
  }

  def 'Treats a version that matches a pattern as a pre-release'() {
    given:
    settings('''
      dependencyUpdates {
        rejectPreReleases true
        preReleasePatterns [r"^3\\."]
      }
    ''')

    when:
    def result = runner().build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 2.2]')
  }

  def 'Leaves the excluded configurations out of the report'() {
    given:
    new File(testProjectDir.root, 'build.gradle') <<
      """
        configurations {
          tool
        }

        dependencies {
          tool 'com.google.guava:guava:15.0'
        }
      """.stripIndent()
    settings('''
      dependencyUpdates {
        excludeConfigurations ["tool"]
      }
    ''')

    when:
    def result = runner().build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 2.2 -> 3.1]')
    !result.output.contains('com.google.guava:guava')
  }

  def 'Reuses the configuration cache'() {
    given:
    settings('''
      dependencyUpdates {
        rejectVersions [
          { versionPattern r"^3\\." }
        ]
        preReleasePatterns ["-nf-"]
        excludeConfigurations ["jacocoAnt"]
      }
    ''')

    when:
    runner('--configuration-cache').build()
    def result = runner('--configuration-cache').build()

    then:
    result.output.contains('Reusing configuration cache.')
    result.output.contains('com.google.inject:guice [2.0 -> 2.2]')
  }
}
