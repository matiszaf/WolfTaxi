package pl.wolftaxi.app.domain.model;

import java.util.ArrayList;
import java.util.List;

public class Region {
    public String id = "";
    public String name = "";
    public String shortName = "";
    public boolean active = true;
    public boolean queueEnabled = true;
    public int priority = 0;
    public List<GeoPointValue> polygon = new ArrayList<>();

    public Region() {}

    public Region(String id, String name, String shortName) {
        this.id = id;
        this.name = name;
        this.shortName = shortName;
    }

    public static class GeoPointValue {
        public double lat;
        public double lng;
        public GeoPointValue() {}
        public GeoPointValue(double lat, double lng) { this.lat = lat; this.lng = lng; }
    }
}
