plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "top.fcidd"
version = "2.0.0"
description = "qq-bot-spring-boot-starter"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    maven { setUrl("https://mirrors.cloud.tencent.com/maven/") }
    mavenCentral()
}

dependencies {
    // spring boot autoconfigure
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-configuration-processor")

    // websocket client
    implementation("org.springframework:spring-websocket")

    // jackson
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // http client，用来拉 gateway、发送消息
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // validation
    compileOnly("jakarta.validation:jakarta.validation-api")

    // test
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
