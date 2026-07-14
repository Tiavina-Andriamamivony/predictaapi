package com.predicta.mg.models;

import java.util.List;

/** Source de la liste de tuiles à fetcher pour couvrir la zone. */
public interface TileGridSource {
  List<TileCoordinate> tiles();
}
