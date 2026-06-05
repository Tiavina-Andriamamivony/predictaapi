package com.predicta.mg.models.dto;

import com.predicta.mg.models.MergedMvtTile;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MergedMvtTileResponse {

    private Long id;
    private LocalDateTime mergedAt;
    private int sourceTileCount;

    public static MergedMvtTileResponse from(MergedMvtTile merged) {
        return MergedMvtTileResponse.builder()
                .id(merged.getId())
                .mergedAt(merged.getMergedAt())
                .sourceTileCount(merged.getSourceTiles().size())
                .build();
    }
}