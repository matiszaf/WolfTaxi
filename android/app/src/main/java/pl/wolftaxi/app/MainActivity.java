package pl.wolftaxi.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import pl.wolftaxi.app.data.ActionCallback;
import pl.wolftaxi.app.data.Backend;
import pl.wolftaxi.app.data.BackendListener;
import pl.wolftaxi.app.data.BackendProvider;
import pl.wolftaxi.app.domain.DriverStatus;
import pl.wolftaxi.app.domain.OrderStatus;
import pl.wolftaxi.app.domain.model.DispatchMessage;
import pl.wolftaxi.app.domain.model.DriverSnapshot;
import pl.wolftaxi.app.domain.model.Order;
import pl.wolftaxi.app.domain.model.Region;
import pl.wolftaxi.app.domain.model.Tariff;
import pl.wolftaxi.app.service.DriverLocationService;
import pl.wolftaxi.app.ui.Ui;

public final class MainActivity extends Activity implements BackendListener {
    private static final int REQUEST_LOCATION = 140;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NumberFormat money = NumberFormat.getCurrencyInstance(new Locale("pl", "PL"));
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private Backend backend;
    private DriverSnapshot snapshot;
    private LinearLayout root;
    private TextView countdown;
    private TextView toastLine;
    private Runnable countdownTick;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        backend = BackendProvider.get(this);
        backend.setListener(this);
        if (backend.requiresLogin() && !backend.isSignedIn()) loginScreen();
        else {
            dashboardShell();
            backend.start();
        }
    }

    @Override public void onResume() {
        super.onResume();
        if (!backend.requiresLogin() || backend.isSignedIn()) backend.start();
    }

    @Override public void onPause() {
        super.onPause();
        stopCountdown();
    }

    @Override public void onDestroy() {
        backend.stop();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onSnapshot(DriverSnapshot value) {
        runOnUiThread(() -> {
            snapshot = value;
            if (root == null || (backend.requiresLogin() && !backend.isSignedIn())) return;
            renderDashboard();
            if (snapshot.driver.onShift) ensureLocationService(false);
        });
    }

    @Override public void onError(String message) {
        runOnUiThread(() -> showMessage("Błąd: " + message, false));
    }

    private void loginScreen() {
        stopCountdown();
        ScrollView scroll = baseScroll();
        LinearLayout content = Ui.column(this);
        content.setPadding(Ui.dp(this, 24), Ui.dp(this, 42), Ui.dp(this, 24), Ui.dp(this, 30));
        scroll.addView(content);
        setContentView(scroll);
        root = null;

        Ui.text(this, content, "WOLFTAXI", 15, Ui.GREEN, true);
        Ui.text(this, content, "Terminal kierowcy", 31, Ui.TEXT, true);
        Ui.text(this, content, "Logowanie do centrali WolfTaxi", 14, Ui.MUTED, false);

        LinearLayout card = Ui.card(this, content);
        EditText email = field("E-mail kierowcy", false);
        EditText password = field("Hasło", true);
        card.addView(email);
        card.addView(password);
        toastLine = Ui.text(this, card, "", 13, Ui.MUTED, false);
        Button signIn = Ui.button(this, card, "ZALOGUJ", Ui.GREEN, v -> {
            v.setEnabled(false);
            backend.signIn(email.getText().toString(), password.getText().toString(), (ok, message) -> runOnUiThread(() -> {
                v.setEnabled(true);
                if (!ok) {
                    showMessage(message, false);
                    return;
                }
                dashboardShell();
                backend.start();
            }));
        });
        signIn.setEnabled(true);

        Ui.text(this, content, "Konto kierowcy tworzy centrala. Po zalogowaniu aplikacja pobiera status, regiony, kolejkę, taryfy i zlecenia z serwera Oracle.", 13, Ui.MUTED, false);
    }

    private EditText field(String hint, boolean password) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setHintTextColor(Color.rgb(104, 132, 132));
        input.setTextColor(Ui.TEXT);
        input.setSingleLine(true);
        input.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        input.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12));
        return input;
    }

    private void dashboardShell() {
        ScrollView scroll = baseScroll();
        root = Ui.column(this);
        root.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 28));
        scroll.addView(root);
        setContentView(scroll);
        renderDashboard();
    }

    private ScrollView baseScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        return scroll;
    }

    private void renderDashboard() {
        if (root == null) return;
        stopCountdown();
        root.removeAllViews();
        toastLine = null;

        if (snapshot == null) {
            Ui.text(this, root, "WOLFTAXI", 16, Ui.GREEN, true);
            Ui.text(this, root, "Łączenie z systemem…", 24, Ui.TEXT, true);
            return;
        }

        renderHeader();
        if (!snapshot.connected) renderConnectionWarning();
        renderDriverState();
        renderRegionAndTariff();
        renderOrderArea();
        renderMessages();
        renderHistory();
        renderActions();
        toastLine = Ui.text(this, root, "", 13, Ui.MUTED, false);
    }

    private void renderHeader() {
        LinearLayout header = Ui.row(this);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout left = Ui.column(this);
        header.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Ui.text(this, left, "WOLFTAXI", 15, Ui.GREEN, true);
        Ui.text(this, left, "Terminal kierowcy", 25, Ui.TEXT, true);
        String taxi = snapshot.driver.number > 0 ? "TAXI " + snapshot.driver.number : "KIEROWCA";
        TextView badge = Ui.text(this, header, taxi + " · " + snapshot.backendMode, 12, snapshot.connected ? Ui.GREEN : Ui.RED, true);
        badge.setGravity(Gravity.END);
    }

    private void renderConnectionWarning() {
        LinearLayout card = Ui.card(this, root);
        Ui.text(this, card, "BRAK POŁĄCZENIA", 15, Ui.RED, true);
        Ui.text(this, card, "Bieżące informacje mogą być nieaktualne. Nie wykonuj operacji wymagających potwierdzenia centrali.", 13, Ui.TEXT, false);
    }

    private void renderDriverState() {
        LinearLayout card = Ui.card(this, root);
        Ui.text(this, card, snapshot.driver.status.label.toUpperCase(Locale.ROOT), 28, statusColor(snapshot.driver.status), true);
        String shift = snapshot.driver.onShift ? "Zmiana aktywna" : "Poza zmianą";
        Ui.text(this, card, shift + " · " + safe(snapshot.driver.name), 14, Ui.MUTED, false);
        if (!safe(snapshot.driver.vehicleId).isEmpty()) Ui.text(this, card, "Samochód: " + snapshot.driver.vehicleId, 13, Ui.MUTED, false);
    }

    private void renderRegionAndTariff() {
        LinearLayout row = Ui.row(this);
        root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout regionCard = compactCard(row);
        Ui.text(this, regionCard, "REGION", 11, Ui.MUTED, true);
        Ui.text(this, regionCard, snapshot.region == null ? "—" : snapshot.region.shortName.isEmpty() ? snapshot.region.name : snapshot.region.shortName, 22, Ui.TEXT, true);
        String queue = snapshot.queuePosition > 0 ? snapshot.queuePosition + " / " + snapshot.queueSize : "poza kolejką";
        Ui.text(this, regionCard, queue, 13, snapshot.queuePosition > 0 ? Ui.BLUE : Ui.MUTED, false);

        LinearLayout tariffCard = compactCard(row);
        Ui.text(this, tariffCard, "TARYFA", 11, Ui.MUTED, true);
        Ui.text(this, tariffCard, snapshot.tariff == null ? "—" : snapshot.tariff.shortName, 22, Ui.TEXT, true);
        String rate = snapshot.tariff == null ? "brak danych" : money.format(snapshot.tariff.pricePerKm) + "/km";
        Ui.text(this, tariffCard, rate, 13, Ui.MUTED, false);

        LinearLayout zoneCard = compactCard(row);
        Ui.text(this, zoneCard, "STREFA", 11, Ui.MUTED, true);
        Ui.text(this, zoneCard, snapshot.fareZone == null ? "—" : snapshot.fareZone.name, 17, Ui.TEXT, true);
        Ui.text(this, zoneCard, "taryfowa", 12, Ui.MUTED, false);
    }

    private LinearLayout compactCard(LinearLayout row) {
        LinearLayout card = Ui.column(this);
        card.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Ui.CARD);
        background.setCornerRadius(Ui.dp(this, 12));
        background.setStroke(Ui.dp(this, 1), Color.rgb(33, 61, 69));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), Ui.dp(this, 10));
        row.addView(card, params);
        return card;
    }

    private void renderOrderArea() {
        if (snapshot.offer != null) {
            renderOffer(snapshot.offer);
            return;
        }
        if (snapshot.activeOrder != null) {
            renderActiveOrder(snapshot.activeOrder);
            return;
        }
        LinearLayout card = Ui.card(this, root);
        Ui.text(this, card, "BRAK AKTYWNEGO ZLECENIA", 17, Ui.TEXT, true);
        String hint = snapshot.driver.status == DriverStatus.IN_QUEUE ? "Oczekujesz w kolejce regionu." : "Ustaw status WOLNY lub wejdź do kolejki regionu.";
        Ui.text(this, card, hint, 13, Ui.MUTED, false);
        if (("DEMO".equals(snapshot.backendMode) || (BuildConfig.DEBUG && "ORACLE".equals(snapshot.backendMode))) && snapshot.driver.onShift) {
            Ui.button(this, card, "TEST · WYGENERUJ ZLECENIE", Ui.ORANGE, v -> action(cb -> backend.simulateOffer(cb)));
        }
    }

    private void renderOffer(Order order) {
        LinearLayout card = Ui.card(this, root);
        Ui.text(this, card, "NOWE ZLECENIE", 14, Ui.ORANGE, true);
        countdown = Ui.text(this, card, "", 34, Ui.ORANGE, true);
        Ui.text(this, card, safe(order.pickupAddress), 24, Ui.TEXT, true);
        if (!safe(order.destinationAddress).isEmpty()) Ui.text(this, card, "→ " + order.destinationAddress, 18, Ui.TEXT, false);
        String meta = order.passengerCount + " os. · " + order.paymentMethod.label;
        if (order.cardRequired) meta += " · karta wymagana";
        Ui.text(this, card, meta, 13, Ui.MUTED, false);
        if (!safe(order.notes).isEmpty()) Ui.text(this, card, order.notes, 13, Ui.MUTED, false);
        if (order.estimatedPrice > 0) Ui.text(this, card, "Szacunkowo: " + money.format(order.estimatedPrice), 14, Ui.TEXT, true);

        LinearLayout row = Ui.row(this);
        card.addView(row);
        Ui.rowButton(this, row, "ODRZUĆ", Ui.RED, v -> confirm("Odrzucić zlecenie?", () -> action(cb -> backend.rejectOrder(order.id, cb))));
        Ui.rowButton(this, row, "PRZYJMIJ", Ui.GREEN, v -> action(cb -> backend.acceptOrder(order.id, cb)));
        startCountdown(order.id, order.offerExpiresAt);
    }

    private void renderActiveOrder(Order order) {
        LinearLayout card = Ui.card(this, root);
        Ui.text(this, card, "ZLECENIE " + safe(order.id), 13, Ui.BLUE, true);
        Ui.text(this, card, order.status.label.toUpperCase(Locale.ROOT), 24, Ui.TEXT, true);
        Ui.text(this, card, "PODSTAWIENIE", 11, Ui.MUTED, true);
        Ui.text(this, card, safe(order.pickupAddress), 21, Ui.TEXT, true);
        if (!safe(order.destinationAddress).isEmpty()) {
            Ui.text(this, card, "CEL", 11, Ui.MUTED, true);
            Ui.text(this, card, order.destinationAddress, 19, Ui.TEXT, false);
        }
        if (!safe(order.passengerPhone).isEmpty()) Ui.text(this, card, "Kontakt: " + order.passengerPhone, 13, Ui.MUTED, false);
        if (!safe(order.notes).isEmpty()) Ui.text(this, card, "Uwagi: " + order.notes, 13, Ui.MUTED, false);

        LinearLayout utilities = Ui.row(this);
        card.addView(utilities);
        String navAddress = order.status == OrderStatus.IN_PROGRESS && !safe(order.destinationAddress).isEmpty() ? order.destinationAddress : order.pickupAddress;
        Ui.rowButton(this, utilities, "NAWIGACJA", Ui.BLUE, v -> openNavigation(navAddress));
        if (!safe(order.passengerPhone).isEmpty()) Ui.rowButton(this, utilities, "ZADZWOŃ", Ui.BLUE, v -> openDialer(order.passengerPhone));

        OrderStatus next = nextStatus(order.status);
        if (next != null) Ui.button(this, card, nextAction(next), next == OrderStatus.COMPLETED ? Ui.RED : Ui.GREEN,
                v -> nextOrderAction(order, next));
    }

    private void nextOrderAction(Order order, OrderStatus next) {
        if (next == OrderStatus.COMPLETED) {
            confirm("Zakończyć kurs?", () -> action(cb -> backend.advanceOrder(order.id, next, cb)));
        } else action(cb -> backend.advanceOrder(order.id, next, cb));
    }

    private void renderMessages() {
        LinearLayout card = Ui.card(this, root);
        Ui.text(this, card, "CENTRALA", 13, Ui.MUTED, true);
        if (snapshot.messages.isEmpty()) {
            Ui.text(this, card, "Brak wiadomości.", 13, Ui.MUTED, false);
            return;
        }
        int shown = Math.min(snapshot.messages.size(), 3);
        for (int i = 0; i < shown; i++) {
            DispatchMessage message = snapshot.messages.get(i);
            int color = "warning".equals(message.type) || "urgent".equals(message.type) ? Ui.ORANGE : Ui.TEXT;
            String time = message.createdAt > 0 ? clock.format(new Date(message.createdAt)) + " · " : "";
            Ui.text(this, card, time + safe(message.title), 12, Ui.MUTED, true);
            Ui.text(this, card, safe(message.body), 14, color, false);
        }
    }


    private void renderHistory() {
        if (snapshot.history.isEmpty()) return;
        LinearLayout card = Ui.card(this, root);
        Ui.text(this, card, "OSTATNIE KURSY", 13, Ui.MUTED, true);
        int shown = Math.min(snapshot.history.size(), 3);
        for (int i = 0; i < shown; i++) {
            Order order = snapshot.history.get(i);
            String route = safe(order.pickupAddress) + (safe(order.destinationAddress).isEmpty() ? "" : " → " + order.destinationAddress);
            Ui.text(this, card, route, 14, Ui.TEXT, i == 0);
            String meta = order.createdAt > 0 ? clock.format(new Date(order.createdAt)) : "";
            if (order.finalPrice > 0) meta += (meta.isEmpty() ? "" : " · ") + money.format(order.finalPrice);
            if (!meta.isEmpty()) Ui.text(this, card, meta, 12, Ui.MUTED, false);
        }
    }

    private void renderActions() {
        LinearLayout primary = Ui.row(this);
        root.addView(primary);
        if (!snapshot.driver.onShift) {
            Ui.rowButton(this, primary, "ROZPOCZNIJ ZMIANĘ", Ui.GREEN, v -> action(cb -> backend.startShift((ok, message) -> {
                cb.complete(ok, message);
                if (ok) runOnUiThread(() -> ensureLocationService(true));
            })));
        } else {
            Ui.rowButton(this, primary, "STATUS", Ui.BLUE, v -> chooseStatus());
            Ui.rowButton(this, primary, "REGION", Ui.BLUE, v -> chooseRegion());
            Ui.rowButton(this, primary, "TARYFA", Ui.BLUE, v -> chooseTariff());
        }

        if (snapshot.driver.onShift && snapshot.queuePosition > 0) {
            Ui.button(this, root, "OPUŚĆ KOLEJKĘ", Ui.ORANGE, v -> action(backend::leaveQueue));
        }

        if (snapshot.driver.onShift) {
            Ui.button(this, root, "ZAKOŃCZ ZMIANĘ", Color.rgb(93, 112, 118), v -> confirm("Zakończyć zmianę?", () -> action(cb -> backend.endShift((ok, message) -> {
                cb.complete(ok, message);
                if (ok) runOnUiThread(this::stopLocationService);
            }))));
        }

        if (backend.requiresLogin()) {
            Ui.button(this, root, "WYLOGUJ", Color.rgb(93, 112, 118), v -> confirm("Wylogować kierowcę?", () -> backend.signOut((ok, message) -> runOnUiThread(() -> {
                stopLocationService();
                loginScreen();
            }))));
        }
    }

    private void chooseStatus() {
        DriverStatus[] statuses = {DriverStatus.AVAILABLE, DriverStatus.BREAK, DriverStatus.OUT_OF_SERVICE};
        String[] labels = {"Wolny", "Przerwa", "Niedostępny"};
        new AlertDialog.Builder(this).setTitle("Status kierowcy").setItems(labels, (dialog, which) -> action(cb -> backend.setStatus(statuses[which], cb))).show();
    }

    private void chooseRegion() {
        if (snapshot.regions.isEmpty()) {
            showMessage("Brak aktywnych regionów.", false);
            return;
        }
        String[] labels = new String[snapshot.regions.size()];
        for (int i = 0; i < labels.length; i++) {
            Region region = snapshot.regions.get(i);
            labels[i] = region.name + (region.queueEnabled ? "" : " · kolejka wyłączona");
        }
        new AlertDialog.Builder(this).setTitle("Wybierz region").setItems(labels, (dialog, which) -> {
            Region region = snapshot.regions.get(which);
            action(cb -> backend.joinQueue(region.id, cb));
        }).show();
    }

    private void chooseTariff() {
        if (snapshot.tariffs.isEmpty()) {
            showMessage("Brak aktywnych taryf.", false);
            return;
        }
        String[] labels = new String[snapshot.tariffs.size()];
        for (int i = 0; i < labels.length; i++) {
            Tariff tariff = snapshot.tariffs.get(i);
            labels[i] = tariff.shortName + " · " + tariff.name + " · " + money.format(tariff.pricePerKm) + "/km";
        }
        new AlertDialog.Builder(this).setTitle("Taryfa").setItems(labels, (dialog, which) -> action(cb -> backend.setTariff(snapshot.tariffs.get(which).id, cb))).show();
    }

    private OrderStatus nextStatus(OrderStatus current) {
        if (current == OrderStatus.ACCEPTED) return OrderStatus.EN_ROUTE;
        if (current == OrderStatus.EN_ROUTE) return OrderStatus.ARRIVED;
        if (current == OrderStatus.ARRIVED) return OrderStatus.IN_PROGRESS;
        if (current == OrderStatus.IN_PROGRESS) return OrderStatus.COMPLETED;
        return null;
    }

    private String nextAction(OrderStatus next) {
        if (next == OrderStatus.EN_ROUTE) return "RUSZAM DO KLIENTA";
        if (next == OrderStatus.ARRIVED) return "JESTEM NA MIEJSCU";
        if (next == OrderStatus.IN_PROGRESS) return "KLIENT WSIADŁ · ROZPOCZNIJ KURS";
        return "ZAKOŃCZ KURS";
    }

    private int statusColor(DriverStatus status) {
        if (status == DriverStatus.AVAILABLE) return Ui.GREEN;
        if (status == DriverStatus.IN_QUEUE) return Ui.BLUE;
        if (status == DriverStatus.OFFER_RECEIVED) return Ui.ORANGE;
        if (status == DriverStatus.DRIVING_TO_PICKUP || status == DriverStatus.AT_PICKUP || status == DriverStatus.IN_RIDE) return Ui.RED;
        return Ui.MUTED;
    }

    private interface BackendAction { void run(ActionCallback callback); }

    private void action(BackendAction action) {
        if (snapshot != null && !snapshot.connected && !"DEMO".equals(snapshot.backendMode)) {
            showMessage("Brak połączenia z centralą.", false);
            return;
        }
        action.run((ok, message) -> runOnUiThread(() -> showMessage(message, ok)));
    }

    private void showMessage(String message, boolean ok) {
        if (message == null || message.isEmpty()) return;
        if (toastLine != null) {
            toastLine.setText(message);
            toastLine.setTextColor(ok ? Ui.GREEN : Ui.ORANGE);
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void confirm(String message, Runnable yes) {
        new AlertDialog.Builder(this).setMessage(message).setNegativeButton("Wróć", null).setPositiveButton("Potwierdź", (dialog, which) -> yes.run()).show();
    }

    private void startCountdown(String orderId, long expiresAt) {
        stopCountdown();
        countdownTick = new Runnable() {
            @Override public void run() {
                if (countdown == null) return;
                long ms = Math.max(0, expiresAt - System.currentTimeMillis());
                countdown.setText(ms > 0 ? String.format(Locale.getDefault(), "%02d s", (ms + 999) / 1000) : "WYGASŁO");
                if (ms > 0) {
                    handler.postDelayed(this, 250);
                } else {
                    countdownTick = null;
                    backend.expireOrder(orderId, (ok, message) -> { });
                }
            }
        };
        handler.post(countdownTick);
    }

    private void stopCountdown() {
        if (countdownTick != null) handler.removeCallbacks(countdownTick);
        countdownTick = null;
        countdown = null;
    }

    private void ensureLocationService(boolean ask) {
        if ("DEMO".equals(snapshot == null ? "" : snapshot.backendMode)) return;
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            if (ask) {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}, REQUEST_LOCATION);
                else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            }
            return;
        }
        Intent service = new Intent(this, DriverLocationService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
    }

    private void stopLocationService() {
        stopService(new Intent(this, DriverLocationService.class));
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION) ensureLocationService(false);
    }


    private void openNavigation(String address) {
        if (safe(address).isEmpty()) {
            showMessage("Brak adresu do nawigacji.", false);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address)));
        try { startActivity(intent); }
        catch (Exception error) { showMessage("Nie znaleziono aplikacji nawigacyjnej.", false); }
    }

    private void openDialer(String phone) {
        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone)));
        try { startActivity(intent); }
        catch (Exception error) { showMessage("Nie można otworzyć telefonu.", false); }
    }

    private String safe(String value) { return value == null ? "" : value; }
}

