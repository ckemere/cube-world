package com.ckemere.cubeworld.seam.nms;

import com.ckemere.cubeworld.geometry.CubeBearing;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.waypoints.WaypointTransmitter;

/**
 * A drop-in {@link ServerWaypointManager} that renders locator-bar dots along the cube
 * geodesic. Same bookkeeping as vanilla, but every connection is a {@link CubeConnection}
 * (source folded into the receiver's face frame) instead of vanilla's raw world-XZ ones.
 * Installed per cube level by {@link WaypointManagerHook}; the End keeps vanilla.
 *
 * <p>Vanilla's state (connections/waypoints/players) and its {@code createConnection} are
 * private, so we override every public entry point and keep our own state. Each method is
 * guarded: a bug degrades to "no dot" rather than breaking a player join.
 */
public final class CubeWaypointManager extends ServerWaypointManager {

    private final CubeBearing bearing;
    private final Logger log;
    private final Set<WaypointTransmitter> waypoints = new HashSet<>();
    private final Set<ServerPlayer> players = new HashSet<>();
    private final Map<ServerPlayer, Map<WaypointTransmitter, CubeConnection>> conns = new HashMap<>();

    public CubeWaypointManager(ServerLevel level, CubeBearing bearing, Logger log) {
        super(level);
        this.bearing = bearing;
        this.log = log;
    }

    @Override
    public void trackWaypoint(WaypointTransmitter w) {
        guard(() -> {
            waypoints.add(w);
            if (locatorBarEnabled) {
                for (ServerPlayer p : players) {
                    createConnection(p, w);
                }
            }
        });
    }

    @Override
    public void updateWaypoint(WaypointTransmitter w) {
        guard(() -> {
            if (!locatorBarEnabled || !waypoints.contains(w)) {
                return;
            }
            for (ServerPlayer p : players) {
                CubeConnection c = row(p).get(w);
                if (c != null) {
                    updateConnection(p, w, c);
                } else {
                    createConnection(p, w);
                }
            }
        });
    }

    @Override
    public void untrackWaypoint(WaypointTransmitter w) {
        guard(() -> {
            for (ServerPlayer p : players) {
                CubeConnection c = row(p).remove(w);
                if (c != null) {
                    c.disconnect();
                }
            }
            waypoints.remove(w);
        });
    }

    @Override
    public void addPlayer(ServerPlayer p) {
        guard(() -> {
            players.add(p);
            if (locatorBarEnabled) {
                for (WaypointTransmitter w : waypoints) {
                    createConnection(p, w);
                }
            }
            if (p.isTransmittingWaypoint()) {
                trackWaypoint(p);
            }
        });
    }

    @Override
    public void updatePlayer(ServerPlayer p) {
        guard(() -> {
            if (!locatorBarEnabled) {
                return;
            }
            for (WaypointTransmitter w : waypoints) {
                CubeConnection c = row(p).get(w);
                if (c != null) {
                    updateConnection(p, w, c);
                } else {
                    createConnection(p, w);
                }
            }
        });
    }

    @Override
    public void removePlayer(ServerPlayer p) {
        guard(() -> {
            Map<WaypointTransmitter, CubeConnection> r = conns.remove(p);
            if (r != null) {
                r.values().forEach(CubeConnection::disconnect);
            }
            untrackWaypoint(p);
            players.remove(p);
        });
    }

    @Override
    public void breakAllConnections() {
        guard(() -> {
            conns.values().forEach(m -> m.values().forEach(CubeConnection::disconnect));
            conns.clear();
        });
    }

    @Override
    public void remakeConnections(WaypointTransmitter w) {
        guard(() -> {
            if (locatorBarEnabled) {
                for (ServerPlayer p : players) {
                    createConnection(p, w);
                }
            }
        });
    }

    @Override
    public Set<WaypointTransmitter> transmitters() {
        return waypoints;
    }

    private Map<WaypointTransmitter, CubeConnection> row(ServerPlayer p) {
        return conns.computeIfAbsent(p, k -> new HashMap<>());
    }

    private void createConnection(ServerPlayer p, WaypointTransmitter w) {
        if (p == w || !locatorBarEnabled) {
            return;
        }
        make(w, p).ifPresentOrElse(c -> {
            row(p).put(w, c);
            c.connect();
        }, () -> {
            CubeConnection old = row(p).remove(w);
            if (old != null) {
                old.disconnect();
            }
        });
    }

    private void updateConnection(ServerPlayer p, WaypointTransmitter w, CubeConnection c) {
        if (p == w || !locatorBarEnabled) {
            return;
        }
        if (!c.isBroken()) {
            c.update();
        } else {
            make(w, p).ifPresentOrElse(nc -> {
                nc.connect();
                row(p).put(w, nc);
            }, () -> {
                c.disconnect();
                row(p).remove(w);
            });
        }
    }

    private Optional<CubeConnection> make(WaypointTransmitter w, ServerPlayer p) {
        if (!(w instanceof LivingEntity src)
                || WaypointTransmitter.doesSourceIgnoreReceiver(src, p)) {
            return Optional.empty();
        }
        return Optional.of(new CubeConnection(src, p, w.waypointIcon(), bearing));
    }

    private void guard(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            log.warning("CubeWaypointManager: " + t);
        }
    }
}
