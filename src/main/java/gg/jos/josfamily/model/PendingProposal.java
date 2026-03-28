package gg.jos.josfamily.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PendingProposal(
    UUID proposerId,
    String proposerName,
    UUID targetId,
    String targetName,
    Instant createdAt,
    Instant expiresAt,
    List<ChargeReceipt> sendChargeReceipts
) {
    public PendingProposal {
        sendChargeReceipts = List.copyOf(sendChargeReceipts);
    }

    public boolean involves(UUID uuid) {
        return proposerId.equals(uuid) || targetId.equals(uuid);
    }
}
