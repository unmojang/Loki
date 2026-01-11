import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.gradleup.shadow") version "8.3.1"
}

group = "org.unmojang"
version = "2.1.1"

val authlibInjectorAPIServer: String by project
val authHost: String by project
val accountHost: String by project
val sessionHost: String by project
val servicesHost: String by project

base {
    archivesName.set("Loki")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.9")
    implementation("org.ow2.asm:asm-tree:9.9")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.isDebug = false
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-deprecation", "-Xlint:-options"))
}

tasks.named<ShadowJar>("shadowJar") {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    archiveClassifier.set("") // remove '-all' suffix
    dependencies {
        include(dependency("org.ow2.asm:asm"))
        include(dependency("org.ow2.asm:asm-tree"))
    }
    minimize()
    exclude("META-INF/maven/**")

    relocate("org.objectweb.asm", "org.unmojang.loki.internal.asm")

    manifest {
        attributes(
            "Premain-Class" to "org.unmojang.loki.Loki",
            "Implementation-Version" to project.version,
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",

            "AuthlibInjectorAPIServer" to authlibInjectorAPIServer,
            "AuthHost" to authHost,
            "AccountHost" to accountHost,
            "SessionHost" to sessionHost,
            "ServicesHost" to servicesHost
        )
    }
}

tasks.named<Jar>("jar") {
    isEnabled = false
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}
