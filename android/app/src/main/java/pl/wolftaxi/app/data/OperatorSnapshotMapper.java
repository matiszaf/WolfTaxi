package pl.wolftaxi.app.data;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import pl.wolftaxi.app.domain.OrderStatus;
import pl.wolftaxi.app.domain.PaymentMethod;
import pl.wolftaxi.app.domain.model.DispatchMessage;
import pl.wolftaxi.app.domain.model.FareZone;
import pl.wolftaxi.app.domain.model.Order;
import pl.wolftaxi.app.domain.model.Region;
import pl.wolftaxi.app.domain.model.Tariff;
import pl.wolftaxi.app.domain.operator.OperatorDriver;
import pl.wolftaxi.app.domain.operator.OperatorSnapshot;
import pl.wolftaxi.app.domain.operator.UserAccount;
import pl.wolftaxi.app.domain.operator.SafetyAlertItem;

public final class OperatorSnapshotMapper {
    private OperatorSnapshotMapper() {}

    public static OperatorSnapshot fromJson(JSONObject json) {
        OperatorSnapshot out = new OperatorSnapshot();
        out.generatedAt = json.optLong("generatedAt", System.currentTimeMillis());
        JSONArray drivers = json.optJSONArray("drivers");
        if (drivers != null) for (int i=0;i<drivers.length();i++) {
            JSONObject j=drivers.optJSONObject(i); if(j==null) continue;
            OperatorDriver d=new OperatorDriver();
            d.id=j.optString("id",""); d.taxiId=j.optString("taxiId",""); d.number=j.optInt("number",0); d.name=j.optString("name","");
            d.vehicleId=j.optString("vehicleId",""); d.enabled=j.optBoolean("enabled",true); d.onShift=j.optBoolean("onShift",false); d.online=j.optBoolean("online",false);
            d.status=j.optString("status","offline"); d.currentRegionId=j.optString("currentRegionId",""); d.targetRegionId=j.optString("targetRegionId",""); d.detectedRegionId=j.optString("detectedRegionId","");
            d.currentTariffId=j.optString("currentTariffId",""); d.currentFareZoneId=j.optString("currentFareZoneId",""); d.activeOrderId=j.optString("activeOrderId","");
            d.queueRegionId=j.optString("queueRegionId",""); d.queuePosition=j.optInt("queuePosition",0); d.queueSize=j.optInt("queueSize",0); d.queuePriority=j.optInt("queuePriority",0); d.priorityPoints=j.optInt("priorityPoints",0); d.blockedReason=j.optString("blockedReason","");
            if(!j.isNull("lat")) d.lat=j.optDouble("lat",Double.NaN); if(!j.isNull("lng")) d.lng=j.optDouble("lng",Double.NaN); d.speed=j.optDouble("speed",0); d.lastLocationAt=j.optLong("lastLocationAt",0);
            out.drivers.add(d);
        }
        JSONArray orders=json.optJSONArray("orders"); if(orders!=null) for(int i=0;i<orders.length();i++){Order o=order(orders.optJSONObject(i));if(o!=null)out.orders.add(o);}
        JSONArray regions=json.optJSONArray("regions"); if(regions!=null) for(int i=0;i<regions.length();i++){JSONObject j=regions.optJSONObject(i);if(j==null)continue;Region r=new Region();r.id=j.optString("id","");r.name=j.optString("name","");r.shortName=j.optString("shortName",r.id);r.active=j.optBoolean("active",true);r.queueEnabled=j.optBoolean("queueEnabled",true);r.priority=j.optInt("priority",0);out.regions.add(r);}
        JSONArray tariffs=json.optJSONArray("tariffs"); if(tariffs!=null) for(int i=0;i<tariffs.length();i++){JSONObject j=tariffs.optJSONObject(i);if(j==null)continue;Tariff t=new Tariff();t.id=j.optString("id","");t.name=j.optString("name","");t.shortName=j.optString("shortName",t.id);t.active=j.optBoolean("active",true);t.startFee=j.optDouble("startFee",0);t.pricePerKm=j.optDouble("pricePerKm",0);t.waitingPricePerHour=j.optDouble("waitingPricePerHour",0);t.minimumFare=j.optDouble("minimumFare",0);out.tariffs.add(t);}
        JSONArray zones=json.optJSONArray("fareZones"); if(zones!=null) for(int i=0;i<zones.length();i++){JSONObject j=zones.optJSONObject(i);if(j==null)continue;FareZone z=new FareZone();z.id=j.optString("id","");z.name=j.optString("name","");z.active=j.optBoolean("active",true);z.multiplier=j.optDouble("multiplier",1);z.defaultTariffId=j.optString("defaultTariffId","");out.fareZones.add(z);}
        JSONArray messages=json.optJSONArray("messages");if(messages!=null)for(int i=0;i<messages.length();i++){JSONObject j=messages.optJSONObject(i);if(j==null)continue;DispatchMessage m=new DispatchMessage();m.id=j.optString("id","");m.type=j.optString("type","info");m.title=j.optString("title","");m.body=j.optString("body","");m.requiresAck=j.optBoolean("requiresAck",false);m.voiceRead=j.optBoolean("voiceRead",true);m.targetType=j.optString("targetType","all");m.targetId=j.optString("targetId","");m.createdAt=j.optLong("createdAt",0);m.yesCount=j.optInt("yesCount",0);m.noCount=j.optInt("noCount",0);out.messages.add(m);}
        JSONArray users=json.optJSONArray("users");if(users!=null)for(int i=0;i<users.length();i++){JSONObject j=users.optJSONObject(i);if(j==null)continue;UserAccount u=new UserAccount();u.id=j.optString("id","");u.email=j.optString("email","");u.name=j.optString("name","");u.enabled=j.optBoolean("enabled",true);JSONArray rs=j.optJSONArray("roles");if(rs!=null)for(int x=0;x<rs.length();x++){String role=rs.optString(x,"");if(!role.isEmpty())u.roles.add(role);}out.users.add(u);}
        JSONArray alerts=json.optJSONArray("alerts");if(alerts!=null)for(int i=0;i<alerts.length();i++){JSONObject j=alerts.optJSONObject(i);if(j==null)continue;SafetyAlertItem a=new SafetyAlertItem();a.id=j.optString("id","");a.type=j.optString("type","sos");a.status=j.optString("status","active");a.note=j.optString("note","");a.driverId=j.optString("driverId","");a.taxiId=j.optString("taxiId","");a.number=j.optInt("number",0);a.driverName=j.optString("driverName","");if(!j.isNull("lat"))a.lat=j.optDouble("lat",Double.NaN);if(!j.isNull("lng"))a.lng=j.optDouble("lng",Double.NaN);a.createdAt=j.optLong("createdAt",0);a.acknowledgedAt=j.optLong("acknowledgedAt",0);out.alerts.add(a);}
        return out;
    }

    private static Order order(JSONObject json) {
        if (json == null) return null;
        Order out = new Order();
        out.id=json.optString("id",""); out.pickupAddress=json.optString("pickupAddress",""); out.destinationAddress=json.optString("destinationAddress","");
        out.pickupRegionId=json.optString("pickupRegionId",""); out.pickupFareZoneId=json.optString("pickupFareZoneId",""); out.destinationFareZoneId=json.optString("destinationFareZoneId","");
        out.tariffId=json.optString("tariffId",""); out.assignedDriverId=json.optString("assignedDriverId",""); out.offeredDriverId=json.optString("offeredDriverId","");
        out.passengerName=json.optString("passengerName",""); out.passengerPhone=json.optString("passengerPhone",""); out.notes=json.optString("notes","");
        out.passengerCount=json.optInt("passengerCount",1); out.cardRequired=json.optBoolean("cardRequired",false); out.luggage=json.optBoolean("luggage",false); out.pet=json.optBoolean("pet",false); out.englishRequired=json.optBoolean("englishRequired",false); out.mineWarning=json.optBoolean("mineWarning",false); out.forced=json.optBoolean("forced",false); out.dispatchMode=json.optString("dispatchMode","queue"); out.source=json.optString("source","dispatch"); out.scheduledFor=json.optLong("scheduledFor",0); out.estimatedPrice=json.optDouble("estimatedPrice",0); out.finalPrice=json.optDouble("finalPrice",0);
        out.createdAt=json.optLong("createdAt",0); out.offerExpiresAt=json.optLong("offerExpiresAt",0); out.status=OrderStatus.fromWire(json.optString("status","created")); out.paymentMethod=PaymentMethod.fromWire(json.optString("paymentMethod","cash"));
        return out;
    }
}
