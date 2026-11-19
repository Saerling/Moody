// The Spotify source module: songs straight from Spotify (Premium) through librespot. The native
// core lives in native/spotify and is cross-compiled into the app's jniLibs by CI; without it the
// module just says it's unavailable.
plugins {
    alias(libs.plugins.kotlin.serialization)
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":modules:api"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}
