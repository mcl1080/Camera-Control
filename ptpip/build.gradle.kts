import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin/JVM library: the camera protocol, with no Android dependencies,
// so it can be unit tested on any machine against a fake camera.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
