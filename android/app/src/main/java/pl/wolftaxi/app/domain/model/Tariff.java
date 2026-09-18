package pl.wolftaxi.app.domain.model;

public class Tariff {
    public String id = "";
    public String name = "";
    public String shortName = "";
    public boolean active = true;
    public double startFee = 0;
    public double pricePerKm = 0;
    public double waitingPricePerHour = 0;
    public double minimumFare = 0;

    public Tariff() {}

    public Tariff(String id, String name, String shortName, double startFee, double pricePerKm, double waitingPricePerHour) {
        this.id = id;
        this.name = name;
        this.shortName = shortName;
        this.startFee = startFee;
        this.pricePerKm = pricePerKm;
        this.waitingPricePerHour = waitingPricePerHour;
    }
}
