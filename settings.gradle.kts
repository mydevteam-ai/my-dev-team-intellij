rootProject.name = "my-dev-team-intellij"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

include("core", "plugin")
