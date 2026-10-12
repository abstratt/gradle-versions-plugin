import org.gradle.api.JavaVersion.VERSION_17
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
import org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED
import org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
import org.gradle.plugin.compatibility.compatibility
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.dokka)
  alias(libs.plugins.ktlint)
  alias(libs.plugins.plugin.publish)
  id("maven-publish") // For publishing the plugin to mavenLocal()
  `java-gradle-plugin`
  `java-library`
  groovy
  // Built into the XDCL-enabled distribution: generates the facades of `src/main/xdcl` and registers
  // the plugin that the descriptor there declares.
  id("xdcl-gradle-plugin")
}

group = property("GROUP").toString()
version = property("VERSION_NAME").toString()

configurations.configureEach {
  resolutionStrategy {
    preferProjectModules()

    enableDependencyVerification()
  }
}

tasks.withType<Jar>().configureEach {
  manifest {
    attributes(
      "Implementation-Title" to project.property("POM_NAME").toString(),
      "Implementation-Version" to project.property("VERSION_NAME").toString(),
      "Built-By" to System.getProperty("user.name"),
      "Built-JDK" to System.getProperty("java.version"),
      "Built-Gradle" to gradle.gradleVersion,
    )
  }
}

tasks.withType<KotlinJvmCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(JVM_17)
    // The build runs on a JDK newer than the target, so bound the API as well as the bytecode.
    freeCompilerArgs.add("-Xjdk-release=17")
    // Kotlin code compiled against the plugin, such as a precompiled script plugin in buildSrc,
    // is compiled by the Kotlin embedded in the running Gradle, which reads metadata at most one
    // release newer than its own.
    languageVersion.set(KOTLIN_2_0)
  }
}

tasks.withType<JavaCompile>().configureEach {
  options.apply {
    release.set(VERSION_17.majorVersion.toInt())
    compilerArgs = compilerArgs +
      listOf(
        "-Xlint:all",
        "-Xlint:-processing",
      )
    encoding = "utf-8"
    isFork = true
  }
}

tasks.withType<GroovyCompile>().configureEach {
  sourceCompatibility = VERSION_17.toString()
  targetCompatibility = VERSION_17.toString()

  options.apply {
    compilerArgs = compilerArgs +
      listOf(
        "-Xlint:all",
        "-Xlint:-processing",
      )
    encoding = "utf-8"
    isFork = true
  }
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform() // Ensure JUnit Platform is used if you are using JUnit 5 or Spock 2.x

  testLogging {
    exceptionFormat = FULL
    showCauses = true
    showExceptions = true
    showStackTraces = true
    showStandardStreams = true
    events = setOf(PASSED, FAILED, SKIPPED)
  }

  // A path relative to the project directory, which is the worker's working directory, so that
  // the test task's inputs are the same in every checkout.
  systemProperty("testKitPool", ".gradle/testkit/$name")
  maxParallelForks = (gradle.startParameter.maxWorkerCount / 2).coerceIn(1, 4)
}

// The Problems API is newer than the oldest supported release, so the class that reports to it is
// compiled against the API of the Gradle running this build, and the plugin creates it only on a
// release that has the API.
val problems = sourceSets.create("problems")

// Declared a friend of the main source set rather than associated with it, as an association would
// add the newer API to the main source set's compile classpath as well.
tasks.compileKotlin {
  friendPaths.from(problems.output.classesDirs)
}

tasks.jar {
  from(problems.output)
}

// Matched by name, as the publishing plugin registers the sources jar after this script runs.
tasks.withType<Jar>().matching { it.name == "sourcesJar" }.configureEach {
  from(problems.allSource)
}

tasks.pluginUnderTestMetadata {
  pluginClasspath.from(problems.output)
}

// The wrapper runs on the newest JDK, so the older ones are reached through toolchains: the test
// worker forks on the named JDK and the TestKit daemons follow it. `test` stays on the build JVM as
// the fast loop.
val testOnAllJdks =
  tasks.register("testOnAllJdks") {
    description = "Runs the test suite on every JDK the plugin supports."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.test)
  }

// `test` already runs on the build JVM, so the task named for that JDK runs `test` instead of a
// second copy of the suite.
val buildJdk = JavaVersion.current().majorVersion
tasks.register("testOn$buildJdk") {
  description = "Runs the test suite on JDK $buildJdk, the JDK the build runs on."
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  dependsOn(tasks.test)
}

// A build resolves the plugin's dependencies from its published metadata, which TestKit's plugin
// classpath leaves out, so a spec that needs them resolves the plugin from this repository instead.
val pluginId = property("PLUGIN_NAME").toString()
val pluginModule = "${project.group.toString().replace('.', '/')}/${project.name}"
val pluginVersion = version.toString()
val specsRepositoryDir = layout.buildDirectory.dir("specs-repository")
val specsRepository =
  tasks.register<Sync>("specsRepository") {
    into(specsRepositoryDir)
    into("$pluginModule/$pluginVersion") {
      from(tasks.jar)
      from(tasks.named("generatePomFileForPluginMavenPublication")) {
        rename("pom-default\\.xml", "${project.name}-$pluginVersion.pom")
      }
      from(tasks.named("generateMetadataFileForPluginMavenPublication")) {
        rename("module\\.json", "${project.name}-$pluginVersion.module")
      }
    }
    // The descriptor's plugin is declared under a name that the XDCL plugin chooses, which the
    // marker publication's tasks are named after.
    val declaration = gradlePlugin.plugins.single { it.id == pluginId }.name.replaceFirstChar { it.uppercase() }
    into("${pluginId.replace('.', '/')}/$pluginId.gradle.plugin/$pluginVersion") {
      from(tasks.named("generatePomFileFor${declaration}PluginMarkerMavenPublication")) {
        rename("pom-default\\.xml", "$pluginId.gradle.plugin-$pluginVersion.pom")
      }
    }
  }

// Every test task gets the repository: a settings.gradle.xdcl reads a plugin's schema from the plugin
// that it resolves, which TestKit's plugin classpath is not.
tasks.withType<Test>().configureEach {
  inputs.files(specsRepository).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("specsRepository")
  systemProperty("specsRepository", specsRepositoryDir.get().asFile.relativeTo(projectDir).path)
  systemProperty("pluginId", pluginId)
  systemProperty("pluginVersion", pluginVersion)
}

// The build JDK already has its task above, which matters when another build includes this one and
// runs it on a JDK of its own.
listOf(17, 21).filter { it.toString() != buildJdk }.forEach { jdk ->
  val testOnJdk =
    tasks.register<Test>("testOn$jdk") {
      description = "Runs the test suite on JDK $jdk."
      group = LifecycleBasePlugin.VERIFICATION_GROUP
      javaLauncher.set(
        javaToolchains.launcherFor {
          languageVersion.set(JavaLanguageVersion.of(jdk))
        },
      )
      val specs = sourceSets.test.get()
      testClassesDirs = specs.output.classesDirs
      classpath = specs.runtimeClasspath
    }
  testOnAllJdks.configure { dependsOn(testOnJdk) }
}

dependencies {
  compileOnly(libs.kotlin.stdlib)
  implementation(libs.moshi)

  compileOnly(problems.output)
  testRuntimeOnly(problems.output)
  "problemsCompileOnly"(gradleApi())
  "problemsCompileOnly"(libs.kotlin.stdlib)

  testImplementation(localGroovy())
  testImplementation(gradleTestKit())
  testImplementation(libs.kotlin.reflect)
  testImplementation(libs.spock) { exclude(module = "groovy-all") }
  testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
  website.set(property("POM_URL").toString())
  vcsUrl.set(property("POM_SCM_URL").toString())
  plugins {
    // The plugin of this id is declared by its descriptor, `src/main/xdcl/$pluginId.xdcl`, which has
    // no field for the features that the plugin is compatible with.
    matching { it.id == pluginId }.configureEach {
      compatibility {
        features {
          configurationCache = true
          isolatedProjects = true
        }
      }
    }
    create("legacyVersionsPlugin") {
      id = property("PLUGIN_LEGACY_NAME").toString()
      implementationClass = property("PLUGIN_LEGACY_NAME_CLASS").toString()
      displayName = property("POM_LEGACY_NAME").toString()
      description = property("POM_LEGACY_DESCRIPTION").toString()
      tags.set(listOf("dependencies", "versions", "updates"))
      compatibility {
        features {
          configurationCache = true
          isolatedProjects = true
        }
      }
    }
    create("versionsContributorPlugin") {
      id = property("PLUGIN_CONTRIBUTOR_NAME").toString()
      implementationClass = property("PLUGIN_CONTRIBUTOR_NAME_CLASS").toString()
      displayName = property("POM_CONTRIBUTOR_NAME").toString()
      description = property("POM_CONTRIBUTOR_DESCRIPTION").toString()
      tags.set(listOf("dependencies", "versions", "updates"))
      compatibility {
        features {
          configurationCache = true
          isolatedProjects = true
        }
      }
    }
    create("versionsSettingsPlugin") {
      id = property("PLUGIN_SETTINGS_NAME").toString()
      implementationClass = property("PLUGIN_SETTINGS_NAME_CLASS").toString()
      displayName = property("POM_SETTINGS_NAME").toString()
      description = property("POM_SETTINGS_DESCRIPTION").toString()
      tags.set(listOf("dependencies", "versions", "updates"))
      compatibility {
        features {
          configurationCache = true
          isolatedProjects = true
        }
      }
    }
  }
}
