package com.predicta.mg.models;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Column;
import jakarta.persistence.OneToOne;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "geojson_result")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeoJsonResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "merged_tile_id", nullable = false)
    private MergedMvtTile mergedTile;

    @Column(name = "geojson_content", columnDefinition = "TEXT", nullable = false)
    private String geoJsonContent;

    @Column(name = "converted_at", nullable = false)
    private LocalDateTime convertedAt;

    @Column(name = "feature_count")
    private int featureCount;
}