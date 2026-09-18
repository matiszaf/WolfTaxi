package pl.wolftaxi.app.domain.operator;

public class SafetyAlertItem {
    public String id = "";
    public String type = "sos";
    public String status = "active";
    public String note = "";
    public String driverId = "";
    public String taxiId = "";
    public int number = 0;
    public String driverName = "";
    public double lat = Double.NaN;
    public double lng = Double.NaN;
    public long createdAt = 0;
    public long acknowledgedAt = 0;
}
