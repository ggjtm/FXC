plugins {
    application
}

@Suppress("UNCHECKED_CAST")
val igniteJvmArgs = rootProject.extra["igniteJvmArgs"] as List<String>

dependencies {
    implementation(project(":fxc-common"))

    // Hot state: embedded GridGain 8 node + SQL tables/services.
    implementation(libs.bundles.gridgain)
    // FIX acceptor: NewOrderSingle / OrderCancelRequest in, ExecutionReport / market data out.
    implementation(libs.bundles.quickfixj)
    // Cold archive to MariaDB.
    implementation(libs.mariadb.client)
    implementation(libs.hikaricp)

    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.fxc.exchange.Main")
    // Required to embed a GridGain/Ignite node on JDK 21 (see .reference/gridgain/README.md §2).
    applicationDefaultJvmArgs = igniteJvmArgs
}
