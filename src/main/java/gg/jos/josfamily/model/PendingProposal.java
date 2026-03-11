package gg.jos.josfamily.model;

import java.time.Instant;
import java.util.UUID;

public record PendingProposal(
    UUID proposerId,
    String proposerName,
    UUID targetId,
    String targetName,
    Instant createdAt,
    Instant expiresAt
) {
    public boolean involves(UUID uuid) {
        return proposerId.equals(uuid) || targetId.equals(uuid);
    }
}
