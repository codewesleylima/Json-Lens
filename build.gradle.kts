import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "dev.jsonlens"
version = "0.2.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.yaml:snakeyaml:2.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
    intellijPlatform {
        intellijIdea("2025.3.1")
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.modules.jcef")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
        pluginVerifier()
    }
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
tasks.withType<JavaCompile>().configureEach { options.release = 21; options.encoding = "UTF-8" }
tasks.test { useJUnitPlatform() }

intellijPlatform {
    pluginVerification {
        ides { current() }
    }

    pluginConfiguration {
        id = "dev.jsonlens.livecontracts"
        name = "WContract - API Contract Intelligence"
        version = project.version.toString()
        description = "Offline Java API contracts, cURL, integration discovery, local risk assessment and evaluation reports."
        vendor { name = "JSON Lens" }
        ideaVersion {
            sinceBuild = "253.29346.138"
            untilBuild = provider { null }
        }
    }
}
