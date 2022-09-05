plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.8.1"
}

group = "com.dguner.lombokbuilderhelper"
version = "1.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.projectlombok:lombok:1.18.24")
}

intellij {
    version.set("2022.2")
    plugins.set(listOf("com.intellij.java"))
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "11"
        targetCompatibility = "11"
    }

    patchPluginXml {
        version.set("${project.version}")
        sinceBuild.set("203")
        untilBuild.set("")
    }
}
