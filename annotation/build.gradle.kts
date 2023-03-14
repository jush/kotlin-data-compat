plugins {
    kotlin("jvm")
	id("maven-publish")
}

kotlin{
    jvmToolchain(11)
}

group = "com.tobrun.datacompat"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(kotlin("stdlib"))
}

project.apply {
	from("$rootDir/gradle/publish.gradle")
}