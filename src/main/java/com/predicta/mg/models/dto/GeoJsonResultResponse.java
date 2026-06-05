package com.predicta.mg.models.dto;

import com.predicta.mg.models.GeoJsonResult;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class GeoJsonResultResponse {

    private Long id;
    private int featureCount;
    private LocalDateTime convertedAt;
    private String geoJsonContent;

    public static GeoJsonResultResponse from(GeoJsonResult result) {
        return GeoJsonResultResponse.builder()
                .id(result.getId())
                .featureCount(result.getFeatureCount())
                .convertedAt(result.getConvertedAt())
                .geoJsonContent(result.getGeoJsonContent())
                .build();
    }
}
