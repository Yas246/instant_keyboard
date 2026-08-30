plugins {
    kotlin("jvm") version "2.2.21"
    `java-library`
}

group = "helium314.keyboard"
version = "1.0"

kotlin { jvmToolchain(17) }

dependencies {
    api("org.jsoup:jsoup:1.18.3")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
