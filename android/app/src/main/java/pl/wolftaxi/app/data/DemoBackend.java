package pl.wolftaxi.app.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

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

public final class DemoBackend implements Backend {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BackendListener listener;
    private final DriverSnapshot snapshot = new DriverSnapshot();
    private Runnable offerExpiry;

    public DemoBackend(Context context) {
        snapshot.backendMode = "DEMO";
        snapshot.connected = true;

        Driver driver = new Driver();
        driver.uid = "demo-driver-14";
        driver.number = 14;
        driver.name = "Kierowca testowy";
        driver.vehicleId = "WT 014";
        driver.status = DriverStatus.OFFLINE;
        driver.currentTariffId = "T1";
        driver.currentFareZoneId = "zone_1";
        snapshot.driver = driver;

        snapshot.regions.add(new Region("centrum", "Centrum", "CTR"));
        snapshot.regions.add(new Region("dworzec", "Dworzec", "DWR"));
        snapshot.regions.add(new Region("port", "Port", "PRT"));
        snapshot.regions.add(new Region("zachod", "Zachód", "ZCH"));

        snapshot.tariffs.add(new Tariff("T1", "Taryfa 1", "T1", 8.0, 4.0, 60.0));
        snapshot.tariffs.add(new Tariff("T2", "Taryfa 2", "T2", 8.0, 6.0, 70.0));
        snapshot.tariffs.add(new Tariff("T3", "Poza strefą", "T3", 10.0, 8.0, 80.0));
        snapshot.tariffs.add(new Tariff("T4", "Specjalna", "T4", 10.0, 9.0, 90.0));
        snapshot.tariff = snapshot.tariffs.get(0);
        snapshot.fareZone = new FareZone("zone_1", "Strefa I", 1.0);
        snapshot.fareZones.add(snapshot.fareZone);

        long now = System.currentTimeMillis();
        snapshot.messages.add(new DispatchMessage("m1", "warning", "Centrala", "Duży ruch w rejonie DWORZEC.", now - 120000, false));
        snapshot.messages.add(new DispatchMessage("m2", "info", "System", "WolfTaxi 2.0 działa w trybie demonstracyjnym.", now - 3600000, false));
        DispatchMessage question = new DispatchMessage("q1", "question", "PYTANIE CENTRALI", "Czy możesz przyjąć kurs z R1?", now - 30000, true);
        snapshot.messages.add(0, question);
    }

    @Override public String modeLabel() { return "DEMO"; }
    @Override public boolean requiresLogin() { return false; }
    @Override public boolean isSignedIn() { return true; }
    @Override public String currentUserId() { return snapshot.driver.uid; }
    @Override public String currentDisplayName() { return "Demo"; }
    @Override public String[] currentRoles() { return new String[]{"driver"}; }
    @Override public boolean hasRole(String role) { return "driver".equalsIgnoreCase(role); }
    @Override public void setListener(BackendListener listener) { this.listener = listener; }
    @Override public void start() { publish(); }
    @Override public void stop() { handler.removeCallbacksAndMessages(null); }

    @Override public void signIn(String email, String password, ActionCallback callback) { callback.complete(true, "Tryb demo"); }
    @Override public void signOut(ActionCallback callback) { callback.complete(true, "Tryb demo nie wymaga logowania"); }

    @Override public void startShift(ActionCallback callback) {
        snapshot.driver.onShift = true;
        snapshot.driver.status = DriverStatus.AVAILABLE;
        snapshot.driver.targetRegionId = "";
        callback.complete(true, "Zmiana rozpoczęta");
        publish();
    }

    @Override public void endShift(ActionCallback callback) {
        if (snapshot.activeOrder != null || snapshot.offer != null) {
            callback.complete(false, "Najpierw zakończ lub odrzuć zlecenie.");
            return;
        }
        snapshot.driver.onShift = false;
        snapshot.driver.status = DriverStatus.OFFLINE;
        snapshot.driver.currentRegionId = "";
        snapshot.driver.targetRegionId = "";
        snapshot.region = null;
        snapshot.queuePosition = 0;
        snapshot.queueSize = 0;
        callback.complete(true, "Zmiana zakończona");
        publish();
    }

    @Override public void setStatus(DriverStatus status, ActionCallback callback) {
        setStatusForRegion(status, "", callback);
    }

    @Override public void setStatusForRegion(DriverStatus status, String regionId, ActionCallback callback) {
        if (!snapshot.driver.onShift) {
            callback.complete(false, "Najpierw rozpocznij zmianę.");
            return;
        }
        if (snapshot.activeOrder != null || snapshot.offer != null) {
            callback.complete(false, "Status jest sterowany przez aktywne zlecenie.");
            return;
        }
        String target = regionId == null ? "" : regionId.trim();
        if (!target.isEmpty() && findRegion(target) == null) {
            callback.complete(false, "Nieznany rejon: " + target);
            return;
        }
        snapshot.driver.status = status;
        snapshot.driver.targetRegionId = (status == DriverStatus.COURSE || status == DriverStatus.DRIVING_TO_PICKUP) ? target : "";
        if (status != DriverStatus.IN_QUEUE) {
            snapshot.queuePosition = 0;
            snapshot.queueSize = 0;
        }
        callback.complete(true, target.isEmpty() ? ("Status: " + status.label) : (status.label + " → " + target));
        publish();
    }

    @Override public void setCurrentRegion(String regionId, ActionCallback callback) {
        if (!snapshot.driver.onShift) {
            callback.complete(false, "Najpierw rozpocznij zmianę.");
            return;
        }
        Region region = findRegion(regionId);
        if (region == null || !region.active) {
            callback.complete(false, "Nieznany rejon: " + regionId);
            return;
        }
        snapshot.region = region;
        snapshot.driver.currentRegionId = region.id;
        if (region.id.equals(snapshot.driver.targetRegionId)) snapshot.driver.targetRegionId = "";
        if (snapshot.activeOrder == null && snapshot.offer == null &&
                (snapshot.driver.status == DriverStatus.AVAILABLE || snapshot.driver.status == DriverStatus.IN_QUEUE) && region.queueEnabled) {
            snapshot.driver.status = DriverStatus.IN_QUEUE;
            snapshot.queuePosition = 1;
            snapshot.queueSize = Math.max(1, snapshot.queueSize);
        } else {
            snapshot.queuePosition = 0;
            snapshot.queueSize = 0;
        }
        callback.complete(true, "Bieżący rejon: " + region.name);
        publish();
    }

    @Override public void joinQueue(String regionId, ActionCallback callback) {
        if (!snapshot.driver.onShift) {
            callback.complete(false, "Najpierw rozpocznij zmianę.");
            return;
        }
        if (snapshot.activeOrder != null || snapshot.offer != null) {
            callback.complete(false, "Nie możesz wejść do kolejki podczas zlecenia.");
            return;
        }
        Region region = findRegion(regionId);
        if (region == null || !region.active || !region.queueEnabled) {
            callback.complete(false, "Ten region nie przyjmuje kierowców.");
            return;
        }
        snapshot.region = region;
        snapshot.driver.currentRegionId = region.id;
        snapshot.driver.targetRegionId = "";
        snapshot.driver.status = DriverStatus.IN_QUEUE;
        snapshot.queuePosition = 3;
        snapshot.queueSize = 8;
        callback.complete(true, "Dołączono do kolejki " + region.name);
        publish();
    }

    @Override public void leaveQueue(ActionCallback callback) {
        snapshot.queuePosition = 0;
        snapshot.queueSize = 0;
        snapshot.driver.targetRegionId = "";
        if (snapshot.driver.onShift) snapshot.driver.status = DriverStatus.AVAILABLE;
        callback.complete(true, "Opuszczono kolejkę");
        publish();
    }

    @Override public void setTariff(String tariffId, ActionCallback callback) {
        Tariff tariff = findTariff(tariffId);
        if (tariff == null || !tariff.active) {
            callback.complete(false, "Taryfa jest niedostępna.");
            return;
        }
        if (!snapshot.driver.manualTariffAllowed) {
            callback.complete(false, "Centrala zablokowała ręczną zmianę taryfy.");
            return;
        }
        snapshot.driver.currentTariffId = tariff.id;
        snapshot.tariff = tariff;
        callback.complete(true, "Ustawiono " + tariff.shortName);
        publish();
    }

    @Override public void acceptOrder(String orderId, ActionCallback callback) {
        if (snapshot.offer == null || !snapshot.offer.id.equals(orderId)) {
            callback.complete(false, "Oferta nie jest już aktywna.");
            return;
        }
        cancelOfferExpiry();
        snapshot.offer.status = OrderStatus.ACCEPTED;
        snapshot.activeOrder = snapshot.offer;
        snapshot.offer = null;
        snapshot.driver.activeOrderId = orderId;
        snapshot.driver.targetRegionId = "";
        snapshot.driver.status = DriverStatus.DRIVING_TO_PICKUP;
        snapshot.queuePosition = 0;
        snapshot.queueSize = 0;
        callback.complete(true, "Zlecenie przyjęte");
        publish();
    }

    @Override public void rejectOrder(String orderId, ActionCallback callback) {
        if (snapshot.offer == null || !snapshot.offer.id.equals(orderId)) {
            callback.complete(false, "Oferta nie jest już aktywna.");
            return;
        }
        cancelOfferExpiry();
        snapshot.offer = null;
        snapshot.driver.status = snapshot.region != null ? DriverStatus.IN_QUEUE : DriverStatus.AVAILABLE;
        if (snapshot.region != null) {
            snapshot.queuePosition = 4;
            snapshot.queueSize = 8;
        }
        callback.complete(true, "Oferta odrzucona");
        publish();
    }

    @Override public void expireOrder(String orderId, ActionCallback callback) {
        if (snapshot.offer == null || !snapshot.offer.id.equals(orderId)) {
            callback.complete(true, "Oferta już wygasła");
            return;
        }
        cancelOfferExpiry();
        snapshot.offer = null;
        snapshot.driver.status = snapshot.region != null ? DriverStatus.IN_QUEUE : DriverStatus.AVAILABLE;
        callback.complete(true, "Oferta wygasła");
        publish();
    }

    @Override public void advanceOrder(String orderId, OrderStatus nextStatus, ActionCallback callback) {
        Order order = snapshot.activeOrder;
        if (order == null || !order.id.equals(orderId)) {
            callback.complete(false, "Brak aktywnego zlecenia.");
            return;
        }
        if (!validTransition(order.status, nextStatus)) {
            callback.complete(false, "Niedozwolona zmiana statusu.");
            return;
        }
        order.status = nextStatus;
        if (nextStatus == OrderStatus.EN_ROUTE) snapshot.driver.status = DriverStatus.DRIVING_TO_PICKUP;
        if (nextStatus == OrderStatus.ARRIVED) snapshot.driver.status = DriverStatus.AT_PICKUP;
        if (nextStatus == OrderStatus.IN_PROGRESS) snapshot.driver.status = DriverStatus.IN_RIDE;
        if (nextStatus == OrderStatus.COMPLETED) {
            order.finalPrice = 67.0;
            snapshot.history.add(0, order);
            snapshot.activeOrder = null;
            snapshot.driver.activeOrderId = "";
            snapshot.driver.status = DriverStatus.AVAILABLE;
        }
        callback.complete(true, nextStatus == OrderStatus.COMPLETED ? "Kurs zakończony" : "Zaktualizowano zlecenie");
        publish();
    }


    @Override public void setMeterWaiting(String orderId, boolean active, ActionCallback callback) {
        if (snapshot != null && snapshot.activeOrder != null && orderId.equals(snapshot.activeOrder.id)) snapshot.activeOrder.meterWaitingActive = active;
        callback.complete(true, active ? "Postój zgłoszony." : "Wznowiono jazdę.");
        publish();
    }

    @Override public void claimExchange(String orderId, ActionCallback callback) {
        Order found = null;
        for (Order order : snapshot.exchange) if (order.id.equals(orderId)) { found = order; break; }
        if (found == null) { callback.complete(false, "Zlecenie nie jest już na giełdzie."); return; }
        snapshot.exchange.remove(found);
        found.status = OrderStatus.ACCEPTED;
        snapshot.activeOrder = found;
        snapshot.driver.activeOrderId = found.id;
        snapshot.driver.status = DriverStatus.DRIVING_TO_PICKUP;
        snapshot.queuePosition = 0; snapshot.queueSize = 0;
        callback.complete(true, "Zlecenie pobrane z giełdy."); publish();
    }

    @Override public void sendSos(String note, ActionCallback callback) {
        pl.wolftaxi.app.domain.model.SafetyAlert a = new pl.wolftaxi.app.domain.model.SafetyAlert();
        a.id = "demo-sos"; a.note = note == null ? "" : note; a.createdAt = System.currentTimeMillis();
        snapshot.safetyAlert = a; snapshot.driver.status = DriverStatus.EMERGENCY;
        callback.complete(true, "ALARM SOS wysłany do centrali."); publish();
    }

    @Override public void cancelSos(ActionCallback callback) {
        snapshot.safetyAlert = null; snapshot.driver.status = snapshot.driver.onShift ? DriverStatus.AVAILABLE : DriverStatus.OFFLINE;
        callback.complete(true, "Alarm SOS odwołany."); publish();
    }

    @Override public void acknowledgeMessage(String messageId, ActionCallback callback) {
        for (DispatchMessage m : snapshot.messages) if (m.id.equals(messageId)) m.acknowledged = true;
        callback.complete(true, "Potwierdzono komunikat."); publish();
    }

    @Override public void answerMessage(String messageId, boolean yes, ActionCallback callback) {
        for (DispatchMessage m : snapshot.messages) if (m.id.equals(messageId)) { m.answered = true; m.answer = yes ? "yes" : "no"; m.acknowledged = true; }
        callback.complete(true, yes ? "Odpowiedź: TAK" : "Odpowiedź: NIE"); publish();
    }
    @Override public void completeOrder(String orderId, double finalPrice, PaymentMethod paymentMethod, ActionCallback callback) {
        Order order = snapshot.activeOrder;
        if (order == null || !order.id.equals(orderId) || order.status != OrderStatus.IN_PROGRESS) { callback.complete(false, "Kurs nie jest gotowy do zakończenia."); return; }
        order.finalPrice = Math.max(0, finalPrice); order.paymentMethod = paymentMethod == null ? PaymentMethod.CASH : paymentMethod;
        order.status = OrderStatus.COMPLETED; snapshot.history.add(0, order); snapshot.activeOrder = null; snapshot.driver.activeOrderId = ""; snapshot.driver.status = DriverStatus.AVAILABLE;
        snapshot.todayRides++; snapshot.todayGross += order.finalPrice;
        if (order.paymentMethod == PaymentMethod.CASH) snapshot.todayCash += order.finalPrice; else if (order.paymentMethod == PaymentMethod.CARD) snapshot.todayCard += order.finalPrice; else snapshot.todayCashless += order.finalPrice;
        callback.complete(true, "Kurs zakończony"); publish();
    }

    @Override public void simulateOffer(ActionCallback callback) {
        if (!snapshot.driver.onShift) {
            callback.complete(false, "Najpierw rozpocznij zmianę.");
            return;
        }
        if (snapshot.offer != null || snapshot.activeOrder != null) {
            callback.complete(false, "Masz już aktywne zlecenie.");
            return;
        }
        Order order = new Order();
        order.id = "WT-1421";
        order.pickupAddress = "Dworcowa 12";
        order.destinationAddress = "Portowa 7";
        order.pickupRegionId = snapshot.region != null ? snapshot.region.id : "centrum";
        order.pickupFareZoneId = "zone_1";
        order.destinationFareZoneId = "zone_1";
        order.tariffId = snapshot.driver.currentTariffId;
        order.passengerName = "Jan";
        order.passengerPhone = "*** *** 321";
        order.passengerCount = 2;
        order.cardRequired = true;
        order.paymentMethod = PaymentMethod.CARD;
        order.notes = "2 osoby · płatność kartą";
        order.estimatedPrice = 67.0;
        order.createdAt = System.currentTimeMillis();
        order.offerExpiresAt = order.createdAt + 20000;
        order.status = OrderStatus.OFFERED;
        snapshot.offer = order;
        snapshot.driver.status = DriverStatus.OFFER_RECEIVED;
        scheduleOfferExpiry(order.id);
        callback.complete(true, "Nowa oferta testowa");
        publish();
    }

    private void scheduleOfferExpiry(String orderId) {
        cancelOfferExpiry();
        offerExpiry = () -> {
            if (snapshot.offer != null && snapshot.offer.id.equals(orderId)) {
                snapshot.offer = null;
                snapshot.driver.status = snapshot.region != null ? DriverStatus.IN_QUEUE : DriverStatus.AVAILABLE;
                publish();
            }
        };
        handler.postDelayed(offerExpiry, 20500);
    }

    private void cancelOfferExpiry() {
        if (offerExpiry != null) handler.removeCallbacks(offerExpiry);
        offerExpiry = null;
    }

    private boolean validTransition(OrderStatus from, OrderStatus to) {
        return (from == OrderStatus.ACCEPTED && to == OrderStatus.EN_ROUTE)
                || (from == OrderStatus.EN_ROUTE && to == OrderStatus.ARRIVED)
                || (from == OrderStatus.ARRIVED && to == OrderStatus.IN_PROGRESS)
                || (from == OrderStatus.IN_PROGRESS && to == OrderStatus.COMPLETED);
    }

    private Region findRegion(String id) {
        for (Region region : snapshot.regions) if (region.id.equals(id)) return region;
        return null;
    }

    private Tariff findTariff(String id) {
        for (Tariff tariff : snapshot.tariffs) if (tariff.id.equals(id)) return tariff;
        return null;
    }

    private void publish() {
        if (listener != null) handler.post(() -> listener.onSnapshot(copySnapshot()));
    }

    private DriverSnapshot copySnapshot() {
        DriverSnapshot out = new DriverSnapshot();
        out.driver = snapshot.driver;
        out.region = snapshot.region;
        out.fareZone = snapshot.fareZone;
        out.tariff = snapshot.tariff;
        out.activeOrder = snapshot.activeOrder;
        out.offer = snapshot.offer;
        out.queuePosition = snapshot.queuePosition;
        out.queueSize = snapshot.queueSize;
        out.queuePriority = snapshot.queuePriority;
        out.safetyAlert = snapshot.safetyAlert;
        out.connected = snapshot.connected;
        out.backendMode = snapshot.backendMode;
        out.regions = new ArrayList<>(snapshot.regions);
        out.tariffs = new ArrayList<>(snapshot.tariffs);
        out.fareZones = new ArrayList<>(snapshot.fareZones);
        out.messages = new ArrayList<>(snapshot.messages);
        out.history = new ArrayList<>(snapshot.history);
        out.exchange = new ArrayList<>(snapshot.exchange);
        out.regionStats = new ArrayList<>(snapshot.regionStats);
        return out;
    }
}
