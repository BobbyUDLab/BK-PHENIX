plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
}

/** `./gradlew :tools:replay --args="<журнал GnssLogger> [--nav <RINEX nav>]"` */
tasks.register<JavaExec>("replay") {
    group = "verification"
    description = "Прогоняет журнал GnssLogger через ядро и печатает метрики целостности"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ru.bkphenix.tools.ReplayKt")
    workingDir = rootDir
}
