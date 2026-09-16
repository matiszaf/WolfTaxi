package pl.wolftaxi.app.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

import pl.wolftaxi.app.domain.DriverStatus;
import pl.wolftaxi.app.domain.OrderStatus;
import pl.wolftaxi.app.domain.PaymentMethod;
import pl.wolftaxi.app.domain.model.DispatchMessage;
import pl.wolftaxi.app.domain.model.Driver;
import pl.wolftaxi.app.domain.model.DriverSnapshot;
import pl.wolftaxi.app.domain.model.FareZone;
import pl.wolftaxi.app.domain.model.Order;
import pl.wolftaxi.app.domain.model.Region;
import pl.wolftaxi.app.domain.model.Tariff;

public final class SnapshotMapper {
    private SnapshotMapper() {}

    public static DriverSnapshot fromJson(JSONObject json) {
        DriverSnapshot out = new DriverSnapshot();
        out.backendMode = "ORACLE";
        out.connected = true;
        out.driver = driver(json.optJSONObject("driver"));
        out.regions = regions(json.optJSONArray("regions"));
        out.tariffs = tariffs(json.optJSONArray("tariffs"));
        out.fareZones = zones(json.optJSONArray("fareZones"));
        out.offer = order(json.optJSONObject("offer"));
        out.activeOrder = order(json.optJSONObject("activeOrder"));
        out.history = orders(json.optJSONArray("history"));
        out.messages = messages(json.optJSONArray("messages"));
        out.queuePosition = json.optInt("queuePosition", 0);
        out.queueSize = json.optInt("queueSize", 0);

        for (Region value : out.regions) if (value.id.equals(out.driver.currentRegionId)) out.region = value;
        for (Tariff value : out.tariffs) if (value.id.equals(out.driver.currentTariffId)) out.tariff = value;
        for (FareZone value : out.fareZones) if (value.id.equals(out.driver.currentFareZoneId)) out.fareZone = value;
        return out;
    }

    private static Driver driver(JSONObject json) {
        Driver out = new Driver();
        if (json == null) return out;
        out.uid = json.optString("id", "");
        out.number = json.optInt("number", 0);
        out.name = json.optString("name", "");
        out.vehicleId = json.optString("vehicleId", "");
        out.enabled = json.optBoolean("enabled", true);
        out.onShift = json.optBoolean("onShift", false);
        out.manualTariffAllowed = json.optBoolean("manualTariffAllowed", true);
        out.status = DriverStatus.fromWire(json.optString("status", "offline"));
        out.currentRegionId = json.optString("currentRegionId", "");
        out.currentTariffId = json.optString("currentTariffId", "");
        out.currentFareZoneId = json.optString("currentFareZoneId", "");
        out.activeOrderId = json.optString("activeOrderId", "");
        return out;
    }

    private static ArrayList<Region> regions(JSONArray array) {
        ArrayList<Region> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) continue;
            Region value = new Region();
            value.id = json.optString("id", "");
            value.name = json.optString("name", "");
            value.shortName = json.optString("shortName", value.id);
            value.active = json.optBoolean("active", true);
            value.queueEnabled = json.optBoolean("queueEnabled", true);
            value.priority = json.optInt("priority", 0);
            JSONArray polygon = json.optJSONArray("polygon");
            if (polygon != null) for (int p = 0; p < polygon.length(); p++) {
                JSONObject point = polygon.optJSONObject(p);
                if (point != null) value.polygon.add(new Region.GeoPointValue(point.optDouble("lat"), point.optDouble("lng")));
            }
            out.add(value);
        }
        return out;
    }

    private static ArrayList<Tariff> tariffs(JSONArray array) {
        ArrayList<Tariff> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) continue;
            Tariff value = new Tariff();
            value.id = json.optString("id", "");
            value.name = json.optString("name", "");
            value.shortName = json.optString("shortName", value.id);
            value.active = json.optBoolean("active", true);
            value.startFee = json.optDouble("startFee", 0);
            value.pricePerKm = json.optDouble("pricePerKm", 0);
            value.waitingPricePerHour = json.optDouble("waitingPricePerHour", 0);
            value.minimumFare = json.optDouble("minimumFare", 0);
            out.add(value);
        }
        return out;
    }

    private static ArrayList<FareZone> zones(JSONArray array) {
        ArrayList<FareZone> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) continue;
            FareZone value = new FareZone();
            value.id = json.optString("id", "");
            value.name = json.optString("name", "");
            value.active = json.optBoolean("active", true);
            value.multiplier = json.optDouble("multiplier", 1.0);
            value.defaultTariffId = json.optString("defaultTariffId", "");
            JSONArray polygon = json.optJSONArray("polygon");
            if (polygon != null) for (int p = 0; p < polygon.length(); p++) {
                JSONObject point = polygon.optJSONObject(p);
                if (point != null) value.polygon.add(new Region.GeoPointValue(point.optDouble("lat"), point.optDouble("lng")));
            }
            out.add(value);
        }
        return out;
    }

    private static Order order(JSONObject json) {
        if (json == null) return null;
        Order out = new Order();
        out.id = json.optString("id", "");
        out.pickupAddress = json.optString("pickupAddress", "");
        out.destinationAddress = json.optString("destinationAddress", "");
        out.pickupRegionId = json.optString("pickupRegionId", "");
        out.pickupFareZoneId = json.optString("pickupFareZoneId", "");
        out.destinationFareZoneId = json.optString("destinationFareZoneId", "");
        out.tariffId = json.optString("tariffId", "");
        out.assignedDriverId = json.optString("assignedDriverId", "");
        out.offeredDriverId = json.optString("offeredDriverId", "");
        out.passengerName = json.optString("passengerName", "");
        out.passengerPhone = json.optString("passengerPhone", "");
        out.notes = json.optString("notes", "");
        out.passengerCount = json.optInt("passengerCount", 1);
        out.cardRequired = json.optBoolean("cardRequired", false);
        out.estimatedPrice = json.optDouble("estimatedPrice", 0);
        out.finalPrice = json.optDouble("finalPrice", 0);
        out.createdAt = json.optLong("createdAt", 0);
        out.offerExpiresAt = json.optLong("offerExpiresAt", 0);
        out.status = OrderStatus.fromWire(json.optString("status", "created"));
        out.paymentMethod = PaymentMethod.fromWire(json.optString("paymentMethod", "cash"));
        return out;
    }

    private static ArrayList<Order> orders(JSONArray array) {
        ArrayList<Order> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            Order value = order(array.optJSONObject(i));
            if (value != null) out.add(value);
        }
        return out;
    }

    private static ArrayList<DispatchMessage> messages(JSONArray array) {
        ArrayList<DispatchMessage> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) continue;
            DispatchMessage value = new DispatchMessage();
            value.id = json.optString("id", "");
            value.type = json.optString("type", "info");
            value.title = json.optString("title", "");
            value.body = json.optString("body", "");
            value.createdAt = json.optLong("createdAt", 0);
            value.requiresAck = json.optBoolean("requiresAck", false);
            out.add(value);
        }
        return out;
    }
}
