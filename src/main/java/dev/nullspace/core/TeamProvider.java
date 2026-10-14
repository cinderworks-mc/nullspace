package dev.nullspace.core;

import java.util.UUID;

// source of teams (ftb teams today). pure, server thread only. a team it cannot read, one it does not accept (like a
// solo team) or data not loaded yet all count as no team (teamKey null, relation NONE)
public interface TeamProvider {
    default boolean ready() { return true; }
    TeamRelation relation(UUID owner, UUID other);
    String teamKey(UUID player);
    String teamName(UUID player);
    int teamSize(UUID player);
}
