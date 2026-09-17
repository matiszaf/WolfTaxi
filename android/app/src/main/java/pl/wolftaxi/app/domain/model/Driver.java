package pl.wolftaxi.app.domain.model;

import pl.wolftaxi.app.domain.DriverStatus;

public class Driver {
    public String uid = "";
    public int number = 0;
    public String name = "";
    public String vehicleId = "";
    public boolean enabled = true;
    public boolean onShift = false;
    public boolean manualTariffAllowed = true;
    public DriverStatus status = DriverStatus.OFFLINE;
    public String currentRegionId = "";
    public String currentTariffId = "";
    public String currentFareZoneId = "";
    public String activeOrderId = "";
    public int priorityPoints = 0;
    public String blockedReason = "";
    public boolean ttsEnabled = true;
    public boolean exchangeEnabled = true;

    public Driver() {}
}
