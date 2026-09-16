package pl.wolftaxi.app.domain;

public enum PaymentMethod {
    CASH("cash", "Gotówka"),
    CARD("card", "Karta"),
    COMPANY("company", "Firma"),
    OTHER("other", "Inna");

    public final String wire;
    public final String label;

    PaymentMethod(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    public static PaymentMethod fromWire(String value) {
        if (value != null) for (PaymentMethod method : values()) if (method.wire.equals(value)) return method;
        return CASH;
    }
}
