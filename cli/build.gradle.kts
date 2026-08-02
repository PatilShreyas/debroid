plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass = "dev.shreyaspatil.debroid.MainKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

val cliVersion = file("version.txt").readText().trim()

val generateVersionInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/source/version/main/kotlin/dev/shreyaspatil/debroid/cli").get().asFile
    inputs.file(file("version.txt"))
    outputs.dir(outputDir)
    doLast {
        outputDir.mkdirs()
        File(outputDir, "Version.kt").writeText("""
            package dev.shreyaspatil.debroid.cli
            
            const val VERSION = "$cliVersion"
        """.trimIndent())
    }
}

sourceSets {
    main {
        kotlin.srcDir(generateVersionInfo)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.clikt)
    
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "dev.shreyaspatil.debroid.MainKt"
        )
    }
    
    from(sourceSets.main.get().output)
    
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("debroid")
    archiveVersion.set(cliVersion)
}
