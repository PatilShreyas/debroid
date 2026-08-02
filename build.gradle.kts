plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}

val detektFormatting = libs.detekt.formatting

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "21"
            freeCompilerArgs = listOf("-Xjvm-default=all")
        }
    }
    
    tasks.withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    dependencies {
        // Shared test dependencies could go here or in individual modules
        "detektPlugins"(detektFormatting)
    }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        autoCorrect = true
        ignoreFailures = true
    }
}
