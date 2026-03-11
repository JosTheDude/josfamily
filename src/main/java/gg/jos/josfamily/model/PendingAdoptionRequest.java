package gg.jos.josfamily.model;

import java.time.Instant;
import java.util.UUID;

public record PendingAdoptionRequest(
    UUID parentId,
    String parentName,
    UUID childId,
    String childName,
    Instant createdAt,
    Instant expiresAt
) {
    public boolean involves(UUID uuid) {
        return parentId.equals(uuid) || childId.equals(uuid);
    }
}
