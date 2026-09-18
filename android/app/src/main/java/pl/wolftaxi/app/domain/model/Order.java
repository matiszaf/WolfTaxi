package pl.wolftaxi.app.domain.model;

import pl.wolftaxi.app.domain.OrderStatus;
import pl.wolftaxi.app.domain.PaymentMethod;

public class Order {
    public String id = "";
    public String pickupAddress = "";
    public String destinationAddress = "";
    public String pickupRegionId = "";
    public String pickupFareZoneId = "";
    public String destinationFareZoneId = "";
    public String tariffId = "";
    public String assignedDriverId = "";
    public String offeredDriverId = "";
    public String passengerName = "";
    public String passengerPhone = "";
    public String notes = "";
    public int passengerCount = 1;
    public boolean cardRequired = false;
    public boolean luggage = false;
    public boolean pet = false;
    public boolean englishRequired = false;
    public boolean mineWarning = false;
    public boolean forced = false;
    public String dispatchMode = "queue";
    public String source = "dispatch";
    public String clientId = "";
    public String companyId = "";
    public String voucherCode = "";
    public String costCenter = "";
    public String bookingRef = "";
    public String settlementStatus = "open";
    public boolean cashless = false;
    public String trackingUrl = "";
    public boolean meterActive = false;
    public long meterStartedAt = 0;
    public double meterDistanceM = 0;
    public double meterWaitingSeconds = 0;
    public double meterAmount = 0;
    public long meterUpdatedAt = 0;
    public long scheduledFor = 0;
    public double estimatedPrice = 0;
    public double finalPrice = 0;
    public long createdAt = 0;
    public long offerExpiresAt = 0;
    public OrderStatus status = OrderStatus.CREATED;
    public PaymentMethod paymentMethod = PaymentMethod.CASH;

    public Order() {}
}
