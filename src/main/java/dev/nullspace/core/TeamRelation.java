package dev.nullspace.core;

public enum TeamRelation {
    NONE, ALLY, MEMBER, OFFICER, OWNER;
    public boolean memberOrBetter() { return this == MEMBER || this == OFFICER || this == OWNER; }
    public boolean officerOrBetter() { return this == OFFICER || this == OWNER; }
}
