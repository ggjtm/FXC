plugins {
    `java-library`
}

dependencies {
    api(libs.slf4j.api)
    // Shared OFX contract: the custom order-entry aggregates + OFX codec live here so FxcBroker
    // (server) and FxcInvestor (client) round-trip the same message set. `api` because the
    // aggregate classes' public surface exposes ofx4j types. (See FxcBroker/docs/PROBLEMS.md B2.)
    api(libs.ofx4j)
    // Shared cold-store (ColdStore): HikariCP+JDBC to MariaDB, used by the components' archival
    // (root PLAN Phase 5). `implementation` — the ColdStore public API is java.sql only.
    implementation(libs.mariadb.client)
    implementation(libs.hikaricp)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
