package com.predicta.mg.models;

/** Coordonnée de tuile XYZ (slippy map). */
public record TileCoordinate(int zoom, int tileX, int tileY) {}
