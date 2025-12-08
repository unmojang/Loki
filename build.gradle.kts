import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.gradleup.shadow") version "8.3.1"
}

group = "org.unmojang"
version = "1.9.1"

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
    implementation("com.grack:nanojson:1.10")
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
        include(dependency("com.grack:nanojson"))
        include(dependency("org.ow2.asm:asm"))
        include(dependency("org.ow2.asm:asm-tree"))
    }
    minimize()
    exclude("META-INF/maven/**")

    relocate("com.grack.nanojson", "org.unmojang.loki.internal.nanojson")
    relocate("org.objectweb.asm", "org.unmojang.loki.internal.asm")

    manifest {
        attributes(
            "Premain-Class" to "org.unmojang.loki.Loki",
            "Implementation-Version" to project.version,
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
        )
    }
}

tasks.named<Jar>("jar") {
    isEnabled = false
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}
