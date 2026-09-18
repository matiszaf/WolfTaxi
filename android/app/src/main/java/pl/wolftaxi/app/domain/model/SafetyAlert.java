package pl.wolftaxi.app.domain.model;

public class SafetyAlert {
    public String id = "";
    public String type = "sos";
    public String status = "active";
    public String note = "";
    public double lat = Double.NaN;
    public double lng = Double.NaN;
    public long createdAt = 0;
}
