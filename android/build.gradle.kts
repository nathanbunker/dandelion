plugins {
    id("com.android.application") version "9.0.1" apply false
    // AGP 9 has Kotlin built in (2.2.10); the Compose compiler plugin must match that version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
