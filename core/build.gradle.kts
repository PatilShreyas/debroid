plugins {
    kotlin("jvm")
    jacoco
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(
        "--add-opens", "jdk.jdi/com.sun.jdi=ALL-UNNAMED",
        "--add-opens", "jdk.jdi/com.sun.jdi.connect=ALL-UNNAMED",
        "--add-opens", "jdk.jdi/com.sun.jdi.event=ALL-UNNAMED",
        "--add-opens", "jdk.jdi/com.sun.jdi.request=ALL-UNNAMED",
        "--add-opens", "jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED"
    )
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
}
