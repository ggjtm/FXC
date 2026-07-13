package com.fxc.pub.grid;

import java.util.List;
import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;

/**
 * Bootstraps a single-node, in-memory GridGain/Ignite cluster for FxcPub (docs/DESIGN.md §3.1/§4.3).
 * Holds FxcPub's hot application state (timeline/follow/account projections). Needs the JDK-21
 * {@code --add-opens} flags set by the Gradle build.
 *
 * <p><b>ToDo (consolidation):</b> duplicates {@code com.fxc.exchange.grid.GridNode} /
 * {@code com.fxc.broker.grid.GridNode} — extract a shared {@code fxc-grid} module (see
 * FxcBroker/docs/PROBLEMS.md B7).
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

        return new GridNode(Ignition.start(cfg));
    }

    public Ignite ignite() {
        return ignite;
    }

    @Override
    public void close() {
        ignite.close();
    }
}
