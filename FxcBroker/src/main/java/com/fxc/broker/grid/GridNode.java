package com.fxc.broker.grid;

import java.util.List;
import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.gridgain.grid.configuration.GridGainConfiguration;

/**
 * Bootstraps a single-node, in-memory GridGain/Ignite cluster for FxcBroker (docs/DESIGN.md §3.1).
 * Discovery is pinned to one localhost address so the node never joins others; persistence stays
 * off. Needs the JDK-21 {@code --add-opens} flags set by the Gradle build.
 *
 * <p><b>ToDo (consolidation):</b> this duplicates {@code com.fxc.exchange.grid.GridNode}. Extract a
 * shared {@code fxc-grid} module used by Exchange/Broker/Pub — see FxcBroker/docs/PROBLEMS.md.
 */
public final class GridNode implements AutoCloseable {

    private final Ignite ignite;

    private GridNode(Ignite ignite) {
        this.ignite = ignite;
    }

    public static GridNode start(String instanceName, int discoveryPort, String workDirectory) {
        if (System.getProperty("IGNITE_QUIET") == null) {
            System.setProperty("IGNITE_QUIET", "true");
        }

        TcpDiscoveryVmIpFinder ipFinder = new TcpDiscoveryVmIpFinder();
        ipFinder.setAddresses(List.of("127.0.0.1:" + discoveryPort));

        TcpDiscoverySpi discovery = new TcpDiscoverySpi();
        discovery.setIpFinder(ipFinder);
        discovery.setLocalAddress("127.0.0.1");
        discovery.setLocalPort(discoveryPort);
        discovery.setLocalPortRange(0);

        IgniteConfiguration cfg = new IgniteConfiguration();
        cfg.setIgniteInstanceName(instanceName);
        cfg.setConsistentId(instanceName);
        cfg.setClientMode(false);
        cfg.setWorkDirectory(workDirectory);
        cfg.setDiscoverySpi(discovery);
        cfg.setPeerClassLoadingEnabled(false);
        cfg.setDataStorageConfiguration(new DataStorageConfiguration());
        // GridGain Ultimate Edition requires a license (see docs/BUILDING.md). Point the embedded
        // node at the license file; the plugin config supplies it to the GridGain runtime.
        cfg.setPluginConfigurations(new GridGainConfiguration().setLicenseUrl(licenseUrl()));

        return new GridNode(Ignition.start(cfg));
    }

    /**
     * Resolve the GridGain license location. Overridable via the {@code gridgain.license.url}
     * system property or the {@code GRIDGAIN_LICENSE_URL} environment variable; otherwise defaults
     * to {@code gridgain-license.xml}, resolved relative to the node's launch directory.
     */
    private static String licenseUrl() {
        String location = System.getProperty("gridgain.license.url");
        if (location == null || location.isBlank()) {
            location = System.getenv("GRIDGAIN_LICENSE_URL");
        }
        if (location == null || location.isBlank()) {
            location = "gridgain-license.xml";
        }
        // GridGain's setLicenseUrl expects a URL, not a bare path. Pass through anything that
        // already carries a URL scheme (file:, file://, http://, …); otherwise resolve the path to
        // an absolute file:// URL.
        if (location.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
            return location;
        }
        return java.nio.file.Path.of(location).toAbsolutePath().toUri().toString();
    }

    public Ignite ignite() {
        return ignite;
    }

    @Override
    public void close() {
        ignite.close();
    }
}
