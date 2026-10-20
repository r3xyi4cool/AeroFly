package org.rexyiscool.aeroFly.model;

import java.util.Objects;

public final class Rank implements Comparable<Rank> {

    private final String name;
    private final String permission;
    private final long dailyTimeSeconds;
    private final int priority;

    public Rank(String name, String permission, long dailyTimeSeconds, int priority) {
        this.name = Objects.requireNonNull(name, "Rank name cannot be null");
        this.permission = Objects.requireNonNull(permission, "Permission cannot be null");
        this.dailyTimeSeconds = dailyTimeSeconds;
        this.priority = priority;
    }

    public String getName() {
        return name;
    }

    public String getPermission() {
        return permission;
    }

    public long getDailyTimeSeconds() {
        return dailyTimeSeconds;
    }

    public int getPriority() {
        return priority;
    }

    @Override
    public int compareTo(Rank other) {
        // Highest priority first
        return Integer.compare(other.priority, this.priority);
    }
}
