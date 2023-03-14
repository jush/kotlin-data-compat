plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
    google()
	maven(url = "https://jitpack.io")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "11"
    }
}