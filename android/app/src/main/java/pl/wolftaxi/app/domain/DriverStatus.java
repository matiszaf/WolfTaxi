package pl.wolftaxi.app.domain;

public enum DriverStatus {
    OFFLINE("offline", "Poza pracą"),
    AVAILABLE("available", "Wolny"),
    IN_QUEUE("in_queue", "W kolejce"),
    OFFER_RECEIVED("offer_received", "Nowe zlecenie"),
    DRIVING_TO_PICKUP("driving_to_pickup", "Dojazd do klienta"),
    AT_PICKUP("at_pickup", "Na miejscu"),
    IN_RIDE("in_ride", "Kurs"),
    COURSE("course", "Kursem"),
    BUSY("busy", "Zajęty"),
    BREAK("break", "Przerwa"),
    OUT_OF_SERVICE("out_of_service", "Niedostępny"),
    BLOCKED("blocked", "Zablokowany"),
    EMERGENCY("emergency", "Alarm");

    public final String wire;
    public final String label;

    DriverStatus(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    public static DriverStatus fromWire(String value) {
        if (value != null) for (DriverStatus status : values()) if (status.wire.equals(value)) return status;
        return OFFLINE;
    }
}
