package com.predicta.mg.models;

/** Récupère les octets MVT (décompressés) d'une tuile. Lève une exception si échec. */
public interface TileFetcher {
  byte[] fetch(TileCoordinate coord);
}
