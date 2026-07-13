plugins {
    java
}

// JVM flags required to embed a GridGain/Ignite node on JDK 9+ (mandatory on JDK 21).
// The bundled ignite.sh sets these; embedded apps and test runners must set them themselves.
// Source: .reference/gridgain/README.md §2. Exposed for component builds via rootProject.extra.
val igniteJvmArgs = listOf(
    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
    "--add-opens=java.management/com.sun.jmx.mbeanserver=ALL-UNNAMED",
    "--add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED",
    "--add-opens=java.base/sun.reflect.generics.reflectiveObjects=ALL-UNNAMED",
    "--add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.math=ALL-UNNAMED",
    "--add-opens=java.sql/java.sql=ALL-UNNAMED",
)
extra["igniteJvmArgs"] = igniteJvmArgs

allprojects {
    group = "com.fxc"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        // GridGain 8 CE is not published to Maven Central (see .reference/README.md risk 2).
        maven {
            name = "GridGain"
            url = uri("https://www.gridgainsystems.com/nexus/content/repositories/external")
        }
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs(igniteJvmArgs)
    }
}
