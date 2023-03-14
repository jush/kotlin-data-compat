plugins {
    id("com.google.devtools.ksp")
    kotlin("jvm")
}


kotlin{
    jvmToolchain(11)
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(kotlin("stdlib"))
	implementation(project(":annotation"))
    ksp(project(":processor"))
}

sourceSets.main {
    java.srcDirs("src/main/kotlin", "build/generated/ksp/main/kotlin")
}

ksp {
    arg("enabled", "true")
}