package gg.jos.josfamily.model;

import java.time.Instant;
import java.util.UUID;

public record MarriageRecord(UUID partnerOne, UUID partnerTwo, Instant marriedAt) {
    public static MarriageRecord create(UUID first, UUID second, Instant marriedAt) {
        return normalize(first, second, marriedAt);
    }

    public static MarriageRecord normalize(UUID first, UUID second, Instant marriedAt) {
        if (first.toString().compareTo(second.toString()) <= 0) {
            return new MarriageRecord(first, second, marriedAt);
        }
        return new MarriageRecord(second, first, marriedAt);
    }

    public boolean involves(UUID uuid) {
        return partnerOne.equals(uuid) || partnerTwo.equals(uuid);
    }

    public UUID partnerOf(UUID uuid) {
        if (partnerOne.equals(uuid)) {
            return partnerTwo;
        }
        if (partnerTwo.equals(uuid)) {
            return partnerOne;
        }
        throw new IllegalArgumentException("UUID " + uuid + " is not part of this marriage");
    }
}
