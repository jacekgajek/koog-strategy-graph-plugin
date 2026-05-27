import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        bundledPlugin("org.jetbrains.kotlin")
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }

    val elk = providers.gradleProperty("elkVersion").get()
    implementation("org.eclipse.elk:org.eclipse.elk.core:$elk")
    implementation("org.eclipse.elk:org.eclipse.elk.alg.layered:$elk")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        // Default failureLevel includes INTERNAL_API_USAGES which fires on Kotlin
        // bridge methods generated for every default method of any Kotlin interface
        // we implement (e.g. ToolWindowFactory.getAnchor/getIcon/manage/...). We
        // never wrote those overrides; the compiler did. Keep only the categories
        // that actually indicate a breaking change.
        failureLevel.set(
            listOf(
                FailureLevel.COMPATIBILITY_PROBLEMS,
                FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
                FailureLevel.MISSING_DEPENDENCIES,
                FailureLevel.INVALID_PLUGIN,
                FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
            )
        )
    }
    publishing {
        // Token: https://plugins.jetbrains.com/author/me/tokens
        // Read from -Pintellijplatform.publish.token=... or env INTELLIJ_PLATFORM_PUBLISH_TOKEN.
        token = providers.environmentVariable("INTELLIJ_PLATFORM_PUBLISH_TOKEN")
            .orElse(providers.gradleProperty("intellijplatform.publish.token"))
        // Channel: "default" (stable, visible to everyone) or "eap"/"beta" (only users who
        // add the channel URL). Override per-publish with -Pchannel=eap.
        channels = providers.gradleProperty("channel").map { listOf(it) }.orElse(listOf("default"))
    }
}

tasks {
    test {
        useJUnit()
    }
}
