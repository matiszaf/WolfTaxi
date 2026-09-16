package pl.wolftaxi.app.domain;

public enum OrderStatus {
    CREATED("created", "Nowe"),
    SEARCHING_DRIVER("searching_driver", "Szukanie kierowcy"),
    OFFERED("offered", "Oferta"),
    ACCEPTED("accepted", "Przyjęte"),
    EN_ROUTE("en_route", "Dojazd"),
    ARRIVED("arrived", "Na miejscu"),
    IN_PROGRESS("in_progress", "Kurs"),
    COMPLETED("completed", "Zakończone"),
    CANCELLED("cancelled", "Anulowane"),
    REJECTED("rejected", "Odrzucone"),
    EXPIRED("expired", "Wygasłe"),
    NO_DRIVER("no_driver", "Brak kierowcy");

    public final String wire;
    public final String label;

    OrderStatus(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    public static OrderStatus fromWire(String value) {
        if (value != null) for (OrderStatus status : values()) if (status.wire.equals(value)) return status;
        return CREATED;
    }

    public boolean isActive() {
        return this == ACCEPTED || this == EN_ROUTE || this == ARRIVED || this == IN_PROGRESS;
    }
}
