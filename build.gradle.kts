import java.util.Properties

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

// --- GridGain license: single source of truth ---
// Read the license file reference from gridgain.properties (repo root) and resolve it to a URL that
// every GridGain component's Gradle-launched JVM (run + test) receives as -Dgridgain.license.url.
// This is the one place to change the license location for Gradle launches; GridNode.licenseUrl()
// keeps an in-code default only as a fallback for non-Gradle launches (e.g. the packaged dist).
val gridgainLicenseUrl: String = run {
    val propsFile = rootProject.file("gridgain.properties")
    val props = Properties()
    if (propsFile.exists()) {
        propsFile.inputStream().use { props.load(it) }
    }
    val ref = props.getProperty("gridgain.license.file")?.trim()
        ?.takeUnless { it.isEmpty() } ?: "gridgain-license.xml"
    // A value already carrying a URL scheme (file:, http:, ...) is used verbatim; otherwise it is a
    // path resolved relative to the repo root and converted to a canonical file:/// URL (Path.toUri,
    // not File.toURI, so it carries an authority component and passes GridNode's scheme guard).
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*").matches(ref)) {
        ref
    } else {
        rootProject.file(ref).toPath().toUri().toString()
    }
}
extra["gridgainLicenseUrl"] = gridgainLicenseUrl

allprojects {
    group = "com.fxc"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        // GridGain 8 (Ultimate Edition) is not published to Maven Central (see .reference/README.md risk 2).
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

    // Every Gradle-launched GridGain component JVM gets the license location resolved once from
    // gridgain.properties (see the root build). Forked JVMs run with the subproject dir as CWD, not
    // the repo root where the license lives, so an absolute file:/// URL is required — GridNode's
    // bare-filename default would not resolve here.
    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs(igniteJvmArgs)
        systemProperty("gridgain.license.url", gridgainLicenseUrl)
    }
    // The `run` targets of the GridGain application modules (FxcExchange/FxcBroker/FxcPub). The
    // application plugin registers `run` in each subproject's own build script (evaluated after
    // this block), so configureEach picks it up lazily. Harmless for non-GridGain run tasks.
    tasks.withType<JavaExec>().configureEach {
        // Forward FXC config overrides from the Gradle invocation into the forked application JVM.
        // Gradle's own `-D` flags land on the *daemon* JVM and JavaExec does not inherit them, so
        // `./gradlew :FxcInvestor:run -Daccount=... -Dagent.seed=...` silently did nothing —
        // FxcConfig reads System.getProperty() in the app, which never saw them. That broke both the
        // documented `-Dkey=value` override (README) and scripts/demo.sh, whose two agents are meant
        // to differ by account and seed so their orders cross.
        //
        // Only the FXC config namespaces are forwarded, deliberately: copying the whole
        // system-property set would push the daemon's java.home/user.dir/etc. into the child.
        val fxcConfigKeys = listOf(
            "account", "mode",                                  // exact keys
            "agent.", "ofx.", "fix.", "xmpp.",                   // prefixes
            "feed.", "web.", "gridgain.", "db.", "archive."
        )
        for (key in System.getProperties().stringPropertyNames()) {
            val matches = fxcConfigKeys.any { if (it.endsWith(".")) key.startsWith(it) else key == it }
            if (matches) {
                systemProperty(key, System.getProperty(key))
            }
        }
        // Set last so the build's resolved, absolute license URL wins: a forked JVM's CWD is the
        // subproject directory, where a relative path would not resolve.
        systemProperty("gridgain.license.url", gridgainLicenseUrl)
    }
}
