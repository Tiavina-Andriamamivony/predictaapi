package com.predicta.mg.models;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Column;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.JoinTable;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "merged_mvt_tile")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MergedMvtTile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToMany
    @JoinTable(
            name = "merged_tile_sources",
            joinColumns = @JoinColumn(name = "merged_id"),
            inverseJoinColumns = @JoinColumn(name = "tile_id")
    )
    private List<MvtTile> sourceTiles;

    @Column(name = "merged_data", nullable = false, columnDefinition = "BYTEA")
    private byte[] mergedData;

    @Column(name = "merged_at", nullable = false)
    private LocalDateTime mergedAt;
}