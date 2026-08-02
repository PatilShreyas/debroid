plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass = "dev.shreyaspatil.debroid.MainKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
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
    archiveVersion.set("0.0.1")
}
