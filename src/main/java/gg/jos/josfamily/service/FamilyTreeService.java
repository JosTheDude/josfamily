package gg.jos.josfamily.service;

import gg.jos.josfamily.model.FamilyMapView;
import gg.jos.josfamily.model.MarriageRecord;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

public final class FamilyTreeService {
    private final MarriageService marriageService;

    public FamilyTreeService(MarriageService marriageService) {
        this.marriageService = marriageService;
    }

    public FamilyMapView buildMap(UUID focusId) {
        MarriageRecord marriage = marriageService.getMarriage(focusId);
        FamilyMapView.FamilyMemberView spouse = marriage == null
            ? null
            : member(marriage.partnerOf(focusId));

        return new FamilyMapView(
            member(focusId),
            spouse,
            List.of(),
            List.of(),
            List.of()
        );
    }

    private FamilyMapView.FamilyMemberView member(UUID playerId) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        String playerName = player.getName() == null ? playerId.toString() : player.getName();
        return new FamilyMapView.FamilyMemberView(playerId, playerName);
    }
}
