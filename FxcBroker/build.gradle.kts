plugins {
    application
}

@Suppress("UNCHECKED_CAST")
val igniteJvmArgs = rootProject.extra["igniteJvmArgs"] as List<String>

dependencies {
    implementation(project(":fxc-common"))

    // Hot state: embedded GridGain 8 node (ACCOUNT, POSITION, CLIENT_ORDER, EXECUTION).
    implementation(libs.bundles.gridgain)
    // FIX initiator to FxcExchange + drop-copy initiator to FxcPub.
    implementation(libs.bundles.quickfixj)
    // OFX 2.x server for FxcInvestor clients.
    implementation(libs.ofx4j)
    // XMPP bot account: posts fills to FxcPub.
    implementation(libs.bundles.smack)
    // Cold archive to MariaDB.
    implementation(libs.mariadb.client)
    implementation(libs.hikaricp)

    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Exit-criteria integration test drives a *live* FxcExchange (PLAN Phase 2). Runtime modules
    // stay independent; only the broker's integration test orchestrates both.
    testImplementation(project(":FxcExchange"))
}

application {
    mainClass.set("com.fxc.broker.Main")
    applicationDefaultJvmArgs = igniteJvmArgs
}
