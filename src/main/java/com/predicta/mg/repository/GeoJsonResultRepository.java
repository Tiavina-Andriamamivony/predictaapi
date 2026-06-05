package com.predicta.mg.repository;

import com.predicta.mg.models.GeoJsonResult;
import com.predicta.mg.models.MergedMvtTile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GeoJsonResultRepository extends JpaRepository<GeoJsonResult, Long> {

    Optional<GeoJsonResult> findByMergedTile(MergedMvtTile mergedTile);

    Optional<GeoJsonResult> findTopByOrderByConvertedAtDesc();
}