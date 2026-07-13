plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = "com.khalibre"
version = "1.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        webstorm("2025.3")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }

    // JSON parsing for gh CLI output and Jira REST responses
    implementation("com.google.code.gson:gson:2.10.1")

    // HTML parsing/sanitizing — converts the rich-text description editor's HTML (and pasted
    // HTML from the clipboard) to/from Jira's Atlassian Document Format
    implementation("org.jsoup:jsoup:1.17.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }
    }
}
