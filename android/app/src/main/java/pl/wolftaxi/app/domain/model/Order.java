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
    public double estimatedPrice = 0;
    public double finalPrice = 0;
    public long createdAt = 0;
    public long offerExpiresAt = 0;
    public OrderStatus status = OrderStatus.CREATED;
    public PaymentMethod paymentMethod = PaymentMethod.CASH;

    public Order() {}
}
