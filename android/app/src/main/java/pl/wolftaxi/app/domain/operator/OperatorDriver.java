package pl.wolftaxi.app.domain.operator;

public final class OperatorDriver {
    public String id = "";
    public String taxiId = "";
    public int number = 0;
    public String name = "";
    public String vehicleId = "";
    public boolean enabled = true;
    public boolean onShift = false;
    public boolean online = false;
    public String status = "offline";
    public String currentRegionId = "";
    public String detectedRegionId = "";
    public String currentTariffId = "";
    public String currentFareZoneId = "";
    public String activeOrderId = "";
    public String queueRegionId = "";
    public int queuePosition = 0;
    public int queueSize = 0;
    public double lat = Double.NaN;
    public double lng = Double.NaN;
    public double speed = 0;
    public long lastLocationAt = 0;
}
