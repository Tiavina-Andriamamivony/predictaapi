package com.predicta.mg.models;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "mvt_tile")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MvtTile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private int zoom;

    @Column(name = "tile_x", nullable = false)
    private int tileX;

    @Column(name = "tile_y", nullable = false)
    private int tileY;

    @Column(name = "source_url", nullable = false)
    private String sourceUrl;

    @Column(name = "raw_data", nullable = false, columnDefinition = "BYTEA")
    private byte[] rawData;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TileStatus status;
}