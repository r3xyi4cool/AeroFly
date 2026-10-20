package org.rexyiscool.aeroFly.model;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerData {

    private final UUID uuid;
    private String lastName;
    private long dailyUsedSeconds;
    private long lastResetPeriod;
    private long purchasedSeconds;
    private long updatedAt;

    // Transient session tracking
    private volatile long lastChargeTimestamp;
    private final Set<Long> notifiedThresholds = ConcurrentHashMap.newKeySet();
    private volatile boolean dirty = false;

    public PlayerData(UUID uuid, String lastName, long dailyUsedSeconds, long lastResetPeriod, long purchasedSeconds, long updatedAt) {
        this.uuid = Objects.requireNonNull(uuid, "UUID cannot be null");
        this.lastName = lastName != null ? lastName : "Unknown";
        this.dailyUsedSeconds = Math.max(0L, dailyUsedSeconds);
        this.lastResetPeriod = lastResetPeriod;
        this.purchasedSeconds = Math.max(0L, purchasedSeconds);
        this.updatedAt = updatedAt > 0 ? updatedAt : System.currentTimeMillis();
        this.lastChargeTimestamp = System.currentTimeMillis();
    }

    public UUID getUuid() {
        return uuid;
    }

    public synchronized String getLastName() {
        return lastName;
    }

    public synchronized void setLastName(String lastName) {
        if (lastName != null && !lastName.equals(this.lastName)) {
            this.lastName = lastName;
            this.dirty = true;
        }
    }

    public synchronized long getDailyUsedSeconds() {
        return dailyUsedSeconds;
    }

    public synchronized void setDailyUsedSeconds(long dailyUsedSeconds) {
        this.dailyUsedSeconds = Math.max(0L, dailyUsedSeconds);
        this.dirty = true;
    }

    public synchronized long getLastResetPeriod() {
        return lastResetPeriod;
    }

    public synchronized void setLastResetPeriod(long lastResetPeriod) {
        this.lastResetPeriod = lastResetPeriod;
        this.dirty = true;
    }

    public synchronized long getPurchasedSeconds() {
        return purchasedSeconds;
    }

    public synchronized long getUpdatedAt() {
        return updatedAt;
    }

    public synchronized void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public long getLastChargeTimestamp() {
        return lastChargeTimestamp;
    }

    public void setLastChargeTimestamp(long timestamp) {
        this.lastChargeTimestamp = timestamp;
    }

    public Set<Long> getNotifiedThresholds() {
        return notifiedThresholds;
    }

    public synchronized void resetDaily(long newPeriod) {
        this.dailyUsedSeconds = 0L;
        this.lastResetPeriod = newPeriod;
        this.updatedAt = System.currentTimeMillis();
        this.dirty = true;
        this.notifiedThresholds.clear();
    }

    public synchronized long getDailyRemaining(long dailyAllowance) {
        return Math.max(0L, dailyAllowance - dailyUsedSeconds);
    }

    public synchronized long getTotalRemaining(long dailyAllowance) {
        return getDailyRemaining(dailyAllowance) + purchasedSeconds;
    }

    /**
     * Drains time in seconds: daily allowance first, then purchased balance.
     */
    public synchronized long drainTime(long secondsToDrain, long dailyAllowance) {
        if (secondsToDrain <= 0) {
            return getTotalRemaining(dailyAllowance);
        }

        long dailyRem = getDailyRemaining(dailyAllowance);
        long drainFromDaily = Math.min(secondsToDrain, dailyRem);
        this.dailyUsedSeconds += drainFromDaily;

        long remainingDrain = secondsToDrain - drainFromDaily;
        if (remainingDrain > 0) {
            this.purchasedSeconds = Math.max(0L, this.purchasedSeconds - remainingDrain);
        }

        markUpdated();
        return getTotalRemaining(dailyAllowance);
    }

    public synchronized long addPurchased(long amount, long maxCap) {
        if (amount <= 0) return this.purchasedSeconds;

        try {
            long added = Math.addExact(this.purchasedSeconds, amount);
            this.purchasedSeconds = Math.min(added, maxCap);
        } catch (ArithmeticException e) {
            this.purchasedSeconds = maxCap;
        }

        markUpdated();
        recheckThresholds(this.purchasedSeconds);
        return this.purchasedSeconds;
    }

    public synchronized long removePurchased(long amount) {
        if (amount <= 0) return this.purchasedSeconds;

        this.purchasedSeconds = Math.max(0L, this.purchasedSeconds - amount);
        markUpdated();
        return this.purchasedSeconds;
    }

    public synchronized long setPurchased(long amount) {
        this.purchasedSeconds = Math.max(0L, amount);
        markUpdated();
        recheckThresholds(this.purchasedSeconds);
        return this.purchasedSeconds;
    }

    private void markUpdated() {
        this.updatedAt = System.currentTimeMillis();
        this.dirty = true;
    }

    private void recheckThresholds(long totalBalance) {
        notifiedThresholds.removeIf(threshold -> totalBalance > threshold);
    }

    public synchronized PlayerData snapshot() {
        PlayerData copy = new PlayerData(
                this.uuid,
                this.lastName,
                this.dailyUsedSeconds,
                this.lastResetPeriod,
                this.purchasedSeconds,
                this.updatedAt
        );
        copy.setDirty(false);
        return copy;
    }
}
