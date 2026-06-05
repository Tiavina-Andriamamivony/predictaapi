package com.predicta.mg.repository;

import com.predicta.mg.models.MergedMvtTile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MergedMvtTileRepository extends JpaRepository<MergedMvtTile, Long> {

    Optional<MergedMvtTile> findTopByOrderByMergedAtDesc();
}