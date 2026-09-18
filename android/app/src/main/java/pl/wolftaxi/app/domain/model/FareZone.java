package pl.wolftaxi.app.domain.model;

import java.util.ArrayList;
import java.util.List;

public class FareZone {
    public String id = "";
    public String name = "";
    public boolean active = true;
    public double multiplier = 1.0;
    public String defaultTariffId = "";
    public List<Region.GeoPointValue> polygon = new ArrayList<>();

    public FareZone() {}
    public FareZone(String id, String name, double multiplier) {
        this.id = id;
        this.name = name;
        this.multiplier = multiplier;
    }
}
