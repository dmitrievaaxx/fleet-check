import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Ровно эти плагины и библиотеки стоят на платформе. Другие на проверке недоступны: платформа
// собирает сервис без сети и ничего не скачивает. Версии не меняйте.
plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

// Java 21. Не подключайте toolchain (jvmToolchain или java { toolchain { ... } }): без сети
// Gradle не сможет скачать JDK, и сборка на проверке не пройдёт.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // Jackson 2 (com.fasterxml.jackson) — для кода из примеров под Spring Boot 3; Spring Boot 4
    // сам работает на Jackson 3 (tools.jackson), им и пользуйтесь в новом коде.
    implementation("org.springframework.boot:spring-boot-jackson2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.postgresql:postgresql")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// В build/libs остаётся один jar — тот, что запускается командой java -jar.
tasks.jar {
    enabled = false
}

tasks.test {
    useJUnitPlatform()
}
