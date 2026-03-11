package gg.jos.josfamily.model;

import java.time.Instant;
import java.util.UUID;

public record AdoptionRecord(UUID parentId, UUID childId, Instant adoptedAt) {
    public boolean involves(UUID uuid) {
        return parentId.equals(uuid) || childId.equals(uuid);
    }
}
