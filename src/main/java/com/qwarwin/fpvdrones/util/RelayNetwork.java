package com.qwarwin.fpvdrones.util;

import com.qwarwin.fpvdrones.Config;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.storage.WorldSavedData;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Relay masts for Shaheds and Gerans, kept with the world so they count even where nobody is around to load
 * them. A strike drone can be sent past its own range when the launch site reaches a relay and the target is
 * within reach of one; relays reach each other in a chain.
 */
public class RelayNetwork extends WorldSavedData {
    private static final String NAME = "fpvdrones_relays";

    private final Map<UUID, Relay> relays = new HashMap<>();

    private static final class Relay {
        final Vector3d pos;
        @Nullable
        final UUID owner;

        Relay(Vector3d pos, @Nullable UUID owner) {
            this.pos = pos;
            this.owner = owner;
        }
    }

    public RelayNetwork() {
        super(NAME);
    }

    public static RelayNetwork get(ServerWorld world) {
        return world.getDataStorage().computeIfAbsent(RelayNetwork::new, NAME);
    }

    public void put(UUID id, Vector3d pos, @Nullable UUID owner) {
        Relay r = relays.get(id);
        if (r != null && r.pos.distanceToSqr(pos) < 1.0 && java.util.Objects.equals(r.owner, owner)) {
            return;
        }
        relays.put(id, new Relay(pos, owner));
        setDirty();
    }

    public void remove(UUID id) {
        if (relays.remove(id) != null) {
            setDirty();
        }
    }

    private List<Relay> usable(World level, @Nullable UUID owner) {
        List<Relay> out = new ArrayList<>();
        for (Relay r : relays.values()) {
            // nobody to ask (a launcher fired by redstone): any relay; otherwise only friendly ones
            if (owner == null || r.owner == null || Targeting.isFriendly(level, owner, r.owner)) {
                out.add(r);
            }
        }
        return out;
    }

    /** Whether a strike drone launched at {@code from} with {@code ownRange} can be sent to {@code to}. */
    public boolean reaches(World level, Vector3d from, Vector3d to, double ownRange, @Nullable UUID owner) {
        if (flat(from, to) <= ownRange) {
            return true;
        }
        double reach = Config.RELAY_RANGE.get();
        List<Relay> all = usable(level, owner);
        boolean[] seen = new boolean[all.size()];
        Deque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < all.size(); i++) {
            if (flat(from, all.get(i).pos) <= ownRange) {
                seen[i] = true;
                queue.add(i);
            }
        }
        while (!queue.isEmpty()) {
            Relay r = all.get(queue.poll());
            if (flat(r.pos, to) <= reach) {
                return true;
            }
            for (int j = 0; j < all.size(); j++) {
                if (!seen[j] && flat(r.pos, all.get(j).pos) <= reach) {
                    seen[j] = true;
                    queue.add(j);
                }
            }
        }
        return false;
    }

    /** A friendly relay in reach: the drone checks its position against it, so spoofing works far worse. */
    public boolean covers(World level, Vector3d pos, @Nullable UUID owner) {
        double reach = Config.RELAY_RANGE.get();
        for (Relay r : usable(level, owner)) {
            if (flat(r.pos, pos) <= reach) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return relays.isEmpty();
    }

    private static double flat(Vector3d a, Vector3d b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public void load(CompoundNBT tag) {
        relays.clear();
        ListNBT list = tag.getList("Relays", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundNBT t = list.getCompound(i);
            relays.put(t.getUUID("Id"), new Relay(new Vector3d(t.getDouble("X"), t.getDouble("Y"), t.getDouble("Z")),
                    t.hasUUID("Owner") ? t.getUUID("Owner") : null));
        }
    }

    @Override
    public CompoundNBT save(CompoundNBT tag) {
        ListNBT list = new ListNBT();
        for (Map.Entry<UUID, Relay> e : relays.entrySet()) {
            CompoundNBT t = new CompoundNBT();
            t.putUUID("Id", e.getKey());
            t.putDouble("X", e.getValue().pos.x);
            t.putDouble("Y", e.getValue().pos.y);
            t.putDouble("Z", e.getValue().pos.z);
            if (e.getValue().owner != null) {
                t.putUUID("Owner", e.getValue().owner);
            }
            list.add(t);
        }
        tag.put("Relays", list);
        return tag;
    }
}
