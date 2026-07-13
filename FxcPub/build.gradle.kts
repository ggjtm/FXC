plugins {
    application
}

@Suppress("UNCHECKED_CAST")
val igniteJvmArgs = rootProject.extra["igniteJvmArgs"] as List<String>

// NOTE: Tigase itself is NOT a dependency here — it runs 100% unmodified as an external
// docker-compose service (docs/DESIGN.md §4.3). FxcPub's own code is an XMPP *client* (Smack).
// Javalin is intentionally absent — the Mastodon REST surface is the deferred gateway (Phase 7).
dependencies {
    implementation(project(":fxc-common"))

    // Hot application state: embedded GridGain 8 node (PUB_ACCOUNT, STATUS, FOLLOW projections).
    implementation(libs.bundles.gridgain)
    // XMPP client to stock Tigase (publish/subscribe to PubSub feeds).
    implementation(libs.bundles.smack)
    // FIX drop-copy acceptor: receives ExecutionReports from brokers, renders them as statuses.
    implementation(libs.bundles.quickfixj)
    // Cold archive to MariaDB (also hosts Tigase's own repository schema, separately).
    implementation(libs.mariadb.client)
    implementation(libs.hikaricp)

    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.fxc.pub.Main")
    applicationDefaultJvmArgs = igniteJvmArgs
}
