package com.fxc.exchange.grid;

import java.util.List;
import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;

/**
 * Bootstraps a single-node, in-memory GridGain/Ignite cluster for a component (docs/DESIGN.md §3.1).
 * Discovery is pinned to a single localhost address so the node never joins other nodes on the
 * network; persistence stays off (pure in-memory) by default.
 *
 * <p><b>JDK 21:</b> an embedded node needs the {@code --add-opens} flags from
 * {@code .reference/gridgain/README.md} §2; these are set on the application and test JVMs by the
 * Gradle build (root {@code build.gradle.kts}). Without them, startup fails with
 * {@code InaccessibleObjectException}.
 */
public final class GridNode implements AutoCloseable {

    private final Ignite ignite;

    private GridNode(Ignite ignite) {
        this.ignite = ignite;
    }

    /**
     * Start an isolated single-node cluster.
     *
     * @param instanceName    Ignite instance name (per-component, e.g. {@code fxc-exchange})
     * @param discoveryPort   local discovery port (isolates co-located clusters)
     * @param workDirectory   Ignite work directory (logs/marshaller); in-memory data is not stored here
     */
    public static GridNode start(String instanceName, int discoveryPort, String workDirectory) {
        // Quiet the console banner unless the operator opts in.
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
        // Explicit: in-memory only. Native persistence stays disabled (docs/DESIGN.md §3.1).
        cfg.setDataStorageConfiguration(new DataStorageConfiguration());

        Ignite ignite = Ignition.start(cfg);
        return new GridNode(ignite);
    }

    public Ignite ignite() {
        return ignite;
    }

    @Override
    public void close() {
        ignite.close();
    }
}
