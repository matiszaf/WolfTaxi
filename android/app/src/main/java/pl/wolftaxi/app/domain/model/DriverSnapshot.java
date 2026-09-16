package pl.wolftaxi.app.domain.model;

import java.util.ArrayList;
import java.util.List;

public class DriverSnapshot {
    public Driver driver = new Driver();
    public Region region;
    public FareZone fareZone;
    public Tariff tariff;
    public Order activeOrder;
    public Order offer;
    public int queuePosition = 0;
    public int queueSize = 0;
    public boolean connected = true;
    public String backendMode = "DEMO";
    public List<Region> regions = new ArrayList<>();
    public List<Tariff> tariffs = new ArrayList<>();
    public List<FareZone> fareZones = new ArrayList<>();
    public List<DispatchMessage> messages = new ArrayList<>();
    public List<Order> history = new ArrayList<>();
}
