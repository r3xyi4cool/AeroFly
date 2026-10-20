package org.rexyiscool.aeroFly.model;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class Grant {

    public enum GrantType {
        ALL,
        PLAYER,
        GROUP;

        public static GrantType fromString(String type) {
            for (GrantType gt : values()) {
                if (gt.name().equalsIgnoreCase(type)) {
                    return gt;
                }
            }
            throw new IllegalArgumentException("Unknown grant type: " + type);
        }
    }

    private final int id;
    private final GrantType type;
    private final String target;
    private final long expiresAtMillis;
    private final String createdBy;
    private final long createdAtMillis;

    public Grant(int id, GrantType type, String target, long expiresAtMillis, String createdBy, long createdAtMillis) {
        this.id = id;
        this.type = Objects.requireNonNull(type, "GrantType cannot be null");
        this.target = target;
        this.expiresAtMillis = expiresAtMillis;
        this.createdBy = createdBy != null ? createdBy : "CONSOLE";
        this.createdAtMillis = createdAtMillis;
    }

    public int getId() {
        return id;
    }

    public GrantType getType() {
        return type;
    }

    public String getTarget() {
        return target;
    }

    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAtMillis;
    }

    public long getRemainingSeconds() {
        long diff = expiresAtMillis - System.currentTimeMillis();
        return diff > 0 ? diff / 1000L : 0L;
    }

    public boolean appliesTo(UUID playerUuid, Set<String> playerGroups) {
        if (isExpired()) {
            return false;
        }

        switch (type) {
            case ALL:
                return true;
            case PLAYER:
                return target != null && target.equalsIgnoreCase(playerUuid.toString());
            case GROUP:
                return target != null && playerGroups != null && playerGroups.contains(target.toLowerCase());
            default:
                return false;
        }
    }

    public String getTargetDisplay() {
        return (type == GrantType.ALL) ? "ALL" : (target != null ? target : "None");
    }
}
