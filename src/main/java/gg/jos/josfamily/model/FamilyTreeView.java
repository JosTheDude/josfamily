package gg.jos.josfamily.model;

import java.util.List;
import java.util.UUID;

public record FamilyTreeView(
    UUID focusId,
    String focusLabel,
    List<FamilyTreeLayer> ancestorLayers,
    List<String> siblingLabels,
    List<FamilyTreeLayer> descendantLayers,
    boolean truncated
) {
    public boolean hasRelationships() {
        return !focusLabel.isBlank()
            && (!ancestorLayers.isEmpty() || !siblingLabels.isEmpty() || !descendantLayers.isEmpty() || focusLabel.contains(" + "));
    }

    public record FamilyTreeLayer(String label, List<String> members) {}
}
