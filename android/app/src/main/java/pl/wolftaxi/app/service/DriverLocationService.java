package pl.wolftaxi.app.service;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pl.wolftaxi.app.MainActivity;
import pl.wolftaxi.app.data.OracleApi;
import pl.wolftaxi.app.data.SessionStore;

public final class DriverLocationService extends Service {
    public static final String CHANNEL_ID = "driver_location";
    public static final String ORDERS_CHANNEL_ID = "driver_orders";
    public static final int NOTIFICATION_ID = 3001;
    private static final int ORDER_NOTIFICATION_ID = 3002;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private FusedLocationProviderClient locationClient;
    private LocationCallback callback;
    private SessionStore session;
    private String lastOfferId = "";

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(NOTIFICATION_ID, notification("WolfTaxi · lokalizacja aktywna"));
        session = new SessionStore(this);
        if (!session.hasSession()) {
            stopSelf();
            return;
        }

        locationClient = LocationServices.getFusedLocationProviderClient(this);
        callback = new LocationCallback() {
            @Override public void onLocationResult(LocationResult result) {
                Location location = result.getLastLocation();
                if (location != null) publish(location);
            }
        };
        startUpdates();
    }

    @SuppressLint("MissingPermission")
    private void startUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(3000)
                .setMaxUpdateDelayMillis(10000)
                .build();
        locationClient.requestLocationUpdates(request, callback, getMainLooper());
    }

    private void publish(Location location) {
        if (session == null || !session.hasSession()) return;
        JSONObject body = new JSONObject();
        try {
            body.put("lat", location.getLatitude());
            body.put("lng", location.getLongitude());
            body.put("speed", location.hasSpeed() ? location.getSpeed() : 0f);
            body.put("heading", location.hasBearing() ? location.getBearing() : 0f);
            body.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : 0f);
        } catch (Exception ignored) {}
        String token = session.token();
        io.execute(() -> {
            try {
                JSONObject response = OracleApi.post("/api/v1/driver/location", token, body);
                String offerId = response.optString("offerId", "");
                if (!offerId.isEmpty() && !offerId.equals(lastOfferId)) {
                    lastOfferId = offerId;
                    showOrderNotification(response.optString("pickupAddress", "Nowe zlecenie"));
                }
                if (offerId.isEmpty()) lastOfferId = "";
            } catch (OracleApi.UnauthorizedException error) {
                session.clear();
                stopSelf();
            } catch (Exception ignored) {}
        });
    }

    private void showOrderNotification(String pickupAddress) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ORDERS_CHANNEL_ID)
                : new Notification.Builder(this);
        Notification value = builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("WolfTaxi · nowe zlecenie")
                .setContentText(pickupAddress)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(ORDER_NOTIFICATION_ID, value);
    }

    private Notification notification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("WolfTaxi")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel location = new NotificationChannel(CHANNEL_ID, "Lokalizacja kierowcy", NotificationManager.IMPORTANCE_LOW);
        location.setDescription("Lokalizacja używana podczas aktywnej zmiany.");
        manager.createNotificationChannel(location);
        NotificationChannel orders = new NotificationChannel(ORDERS_CHANNEL_ID, "Zlecenia", NotificationManager.IMPORTANCE_HIGH);
        orders.setDescription("Nowe zlecenia z centrali WolfTaxi.");
        manager.createNotificationChannel(orders);
    }

    @Override public void onDestroy() {
        if (locationClient != null && callback != null) locationClient.removeLocationUpdates(callback);
        if (session != null && session.hasSession()) {
            String token = session.token();
            io.execute(() -> {
                try { OracleApi.post("/api/v1/driver/presence/offline", token, new JSONObject()); }
                catch (Exception ignored) {}
            });
        }
        io.shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
