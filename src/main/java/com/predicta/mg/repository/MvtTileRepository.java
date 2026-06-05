package com.predicta.mg.repository;

import com.predicta.mg.models.MvtTile;
import com.predicta.mg.models.TileStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MvtTileRepository extends JpaRepository<MvtTile, Long> {

    Optional<MvtTile> findByZoomAndTileXAndTileY(int zoom, int tileX, int tileY);

    List<MvtTile> findByStatus(TileStatus status);
}