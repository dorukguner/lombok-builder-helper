plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.9.0"
}

group = "com.dguner.lombokbuilderhelper"
version = "1.5.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
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
