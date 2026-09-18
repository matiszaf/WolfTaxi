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
    public int queuePriority = 0;
    public int todayRides = 0;
    public double todayGross = 0;
    public double todayCash = 0;
    public double todayCard = 0;
    public double todayCashless = 0;
    public SafetyAlert safetyAlert;
    public boolean connected = true;
    public String backendMode = "DEMO";
    public List<Region> regions = new ArrayList<>();
    public List<Tariff> tariffs = new ArrayList<>();
    public List<FareZone> fareZones = new ArrayList<>();
    public List<DispatchMessage> messages = new ArrayList<>();
    public List<Order> history = new ArrayList<>();
    public List<Order> exchange = new ArrayList<>();
    public List<RegionStat> regionStats = new ArrayList<>();
}
