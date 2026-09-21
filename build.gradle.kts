plugins {
    id("com.android.application") version "9.2.1" apply false
    // Not applied anywhere: AGP 9 brings its own Kotlin support, and declaring
    // the plugin here is what tells it which Kotlin to use. mapscore 4.0.0 is
    // built with 2.4, which AGP's default (2.2) refuses to read.
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
}
