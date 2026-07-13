plugins {
    `java-library`
}

dependencies {
    api(libs.slf4j.api)
    // Shared OFX contract: the custom order-entry aggregates + OFX codec live here so FxcBroker
    // (server) and FxcInvestor (client) round-trip the same message set. `api` because the
    // aggregate classes' public surface exposes ofx4j types. (See FxcBroker/docs/PROBLEMS.md B2.)
    api(libs.ofx4j)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
