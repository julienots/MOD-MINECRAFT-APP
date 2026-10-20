import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Module Kotlin pur : aucune dépendance Android, testable sur la JVM.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
}

tasks.test {
    // Les noms de fichiers accentués des tests exigent un système de fichiers en UTF-8, comme sur Android.
    environment("LC_ALL", "C.UTF-8")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
}
