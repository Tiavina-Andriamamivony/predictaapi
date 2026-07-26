package com.predicta.mg.models;

public record QuartierView(String name, double lon, double lat) {
  public static QuartierView of(Quartier q) {
    return new QuartierView(q.getName(), q.getCentroidLon(), q.getCentroidLat());
  }
}
