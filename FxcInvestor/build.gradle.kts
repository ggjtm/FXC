plugins {
    application
    // Opt-in performance / bulk-simulation harness (docs/stories/005). Adds the `gatling` source set
    // (src/gatling/java) and the `gatlingRun` task; NOT part of the default build/test/check.
    id("io.gatling.gradle") version "3.13.5"
}

// FxcInvestor does NOT embed GridGain — MariaDB is its primary store (docs/DESIGN.md §3.2/§4.4),
// so it needs neither the GridGain dependency nor the Ignite --add-opens JVM flags.
dependencies {
    implementation(project(":fxc-common"))

    // OFX 2.x client to FxcBroker (signon, statement sync, custom order-entry message set).
    implementation(libs.ofx4j)
    // XMPP client to FxcPub (home timeline in, agent commentary out).
    implementation(libs.bundles.smack)
    // Primary + archive store.
    implementation(libs.mariadb.client)
    implementation(libs.hikaricp)

    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Exit-criteria integration test drives a live FxcExchange + FxcBroker (PLAN Phase 4);
    // the test's contra-liquidity client speaks FIX directly to the exchange. The Phase-6
    // end-to-end orchestrator (EndToEndDemoIT) additionally boots FxcPub against live Tigase.
    testImplementation(project(":FxcExchange"))
    testImplementation(project(":FxcBroker"))
    testImplementation(project(":FxcPub"))
    testImplementation(libs.bundles.quickfixj)

    // The Gatling simulation reuses the investor's production strategy + OFX request-building and
    // the shared OFX contract (fxc-common). Gatling itself is provided by the plugin.
    gatlingImplementation(project(":fxc-common"))
    gatlingImplementation(sourceSets["main"].output)
}

application {
    mainClass.set("com.fxc.investor.Main")
}
