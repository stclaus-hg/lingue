import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.21"
    // The Compose compiler plugin ships inside Kotlin and must match its version exactly.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
}

group = "lingue"
version = "0.1.0"

repositories {
    // fsrs is not published to Maven Central yet, so it is built from source and
    // installed into ~/.m2 with `publishToMavenLocal`. See docs/SETUP.md.
    mavenLocal()
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    implementation("io.github.open-spaced-repetition:fsrs:1.0.0")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "lingue.MainKt"

        // `createDistributable` bundles a trimmed JRE next to the app, so the
        // installed copy starts without Gradle and does not depend on the JDK
        // under ~/.local staying put.
        nativeDistributions {
            // The bundled runtime is assembled from a fixed minimal module set,
            // so anything outside java.base must be named or it goes missing at
            // runtime rather than at build time. This list comes from
            // `./gradlew suggestRuntimeModules` — re-run it after adding a
            // dependency that touches JDK APIs (sqlite-jdbc is why java.sql is here).
            modules("java.instrument", "java.sql", "jdk.unsupported")

            targetFormats(TargetFormat.Deb)
            packageName = "lingue"
            packageVersion = "1.0.0"
            description = "English vocabulary trainer with FSRS scheduling"
            vendor = "quantum"

            linux {
                iconFile.set(project.file("icons/lingue.png"))
                menuGroup = "Education"
                shortcut = true
            }
        }
    }
}

/** Bulk import: `./gradlew importWords -Pfile=data/words.tsv` */
tasks.register<JavaExec>("importWords") {
    group = "application"
    description = "Imports words from a TSV file (term<TAB>translation[<TAB>example])"
    mainClass.set("lingue.tools.ImportKt")
    classpath = sourceSets["main"].runtimeClasspath
    doFirst {
        val file = project.findProperty("file")?.toString()
            ?: error("Pass the source file: ./gradlew importWords -Pfile=data/words.tsv")
        args = listOf(file)
    }
}
