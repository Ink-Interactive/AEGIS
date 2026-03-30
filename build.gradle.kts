import java.io.BufferedInputStream
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.api.tasks.JavaExec

plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    jacoco
}

group = "atlanteshellsing.aegis"
version = "1.0"

repositories {
    mavenCentral()
}

val javafxVersion = "24.0.2"
val mainModuleName = "atlanteshellsing.aegis"
val fxModules = listOf("javafx.controls", "javafx.fxml")

dependencies {
    // JavaFX
    implementation("org.openjfx:javafx-controls:$javafxVersion")
    implementation("org.openjfx:javafx-fxml:$javafxVersion")

    // Tests
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

javafx {
    version = javafxVersion
    modules = fxModules
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

jacoco {
    toolVersion = "0.8.14"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))

    reports {
        xml.required.set(true)
        csv.required.set(false)
        html.outputLocation.set(layout.buildDirectory.dir("jacocoHtml"))
    }

    val classFiles = fileTree(layout.buildDirectory.dir("classes")) {
        include("**/*.class")
    }

    // Bytecode annotation descriptor (binary name)
    val excludeAnnotation = "Latlanteshellsing/aegis/annotations/ExcludeAsGenerated;"

    val filteredClasses = classFiles.matching {
        exclude {
            val file = it.file
            if (!file.isFile || !file.name.endsWith(".class")) return@exclude false

            BufferedInputStream(file.inputStream()).use { input ->
                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    val text = String(buffer, 0, bytesRead, Charsets.ISO_8859_1)
                    if (text.contains(excludeAnnotation)) return@exclude true
                }
            }
            false
        }
    }

    classDirectories.setFrom(files(filteredClasses))
}

tasks.register<JavaExec>("runAegis") {
    group = "application"
    description = "Run AEGIS (modular JavaFX) with correct module-path"

    // deps (JavaFX, etc.)
    classpath = sourceSets.main.get().runtimeClasspath

    // run as module
    mainModule.set(mainModuleName)
    mainClass.set("atlanteshellsing.aegis.AEGISMainApplication")

    modularity.inferModulePath.set(true)

    // Explicit modules list (stable)
    jvmArgs("--add-modules", fxModules.joinToString(","))

    doFirst {
        // Patch ONLY resources into the module so getResource() works
        val resourcesDir = sourceSets.main.get().output.resourcesDir
        requireNotNull(resourcesDir) { "No resourcesDir found for main sourceSet" }

        jvmArgs("--patch-module", "$mainModuleName=${resourcesDir.absolutePath}")

        // Optional: only enable native access if JavaFX graphics is present
        if (classpath.files.any { it.name.startsWith("javafx-graphics") }) {
            jvmArgs("--enable-native-access=javafx.graphics")
        }
    }
}

application {
    mainModule.set(mainModuleName)
    mainClass.set("atlanteshellsing.aegis.AEGISMainApplication")
}
