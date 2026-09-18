package pl.wolftaxi.app.domain.operator;

public final class OperatorSettlement {
    public String id="", orderId="", driverId="", companyId="", clientId="", paymentMethod="", status="";
    public String taxiId="", driverName="", companyName="", clientName="";
    public double grossAmount=0, driverAmount=0, companyAmount=0, voucherAmount=0;
    public long createdAt=0, settledAt=0;
}
