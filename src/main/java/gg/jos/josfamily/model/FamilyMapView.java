package gg.jos.josfamily.model;

import java.util.List;
import java.util.UUID;

public record FamilyMapView(
    FamilyMemberView focus,
    FamilyMemberView spouse,
    List<FamilyMemberView> parents,
    List<FamilyMemberView> siblings,
    List<FamilyMemberView> children
) {
    public boolean hasRelationships() {
        return spouse != null || !parents.isEmpty() || !siblings.isEmpty() || !children.isEmpty();
    }

    public record FamilyMemberView(UUID playerId, String name) {}
}
