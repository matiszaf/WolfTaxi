package pl.wolftaxi.app.domain.operator;

public final class OperatorVoucher {
    public String code="", companyId="", clientId="";
    public double amount=0, remainingAmount=0;
    public boolean active=true;
    public long validFrom=0, validUntil=0, createdAt=0, usedAt=0;
}
