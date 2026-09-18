package pl.wolftaxi.app.domain.operator;

import java.util.ArrayList;
import pl.wolftaxi.app.domain.model.DispatchMessage;
import pl.wolftaxi.app.domain.model.FareZone;
import pl.wolftaxi.app.domain.model.Order;
import pl.wolftaxi.app.domain.model.Region;
import pl.wolftaxi.app.domain.model.Tariff;

public final class OperatorSnapshot {
    public ArrayList<OperatorDriver> drivers = new ArrayList<>();
    public ArrayList<Order> orders = new ArrayList<>();
    public ArrayList<Region> regions = new ArrayList<>();
    public ArrayList<Tariff> tariffs = new ArrayList<>();
    public ArrayList<FareZone> fareZones = new ArrayList<>();
    public ArrayList<DispatchMessage> messages = new ArrayList<>();
    public ArrayList<UserAccount> users = new ArrayList<>();
    public ArrayList<SafetyAlertItem> alerts = new ArrayList<>();
    public boolean connected = true;
    public long generatedAt = 0;
    public int todayRides = 0;
    public double todayGross = 0;
    public double todayCash = 0;
    public double todayCard = 0;
    public double todayCashless = 0;
}
