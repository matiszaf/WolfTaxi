package pl.wolftaxi.app.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.telephony.SmsManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import pl.wolftaxi.app.MainActivity;
import pl.wolftaxi.app.data.OracleApi;
import pl.wolftaxi.app.data.SessionStore;

public final class SmsGatewayService extends Service {
    private static final String CHANNEL_ID = "wolftaxi_sms_gateway";
    private static final int NOTIFICATION_ID = 8020;
    private static final String PREFS = "wolftaxi_sms_gateway";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LAST_STATUS = "last_status";
    private static final String KEY_LAST_RECIPIENT = "last_recipient";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_LAST_AT = "last_at";

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private SessionStore session;
    private SharedPreferences prefs;

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static String lastStatus(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_STATUS, "Bramka nie była jeszcze używana.");
    }

    public static String lastRecipient(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_RECIPIENT, "");
    }

    public static String lastError(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_ERROR, "");
    }

    public static long lastAt(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_AT, 0L);
    }

    @Override public void onCreate() {
        super.onCreate();
        session = new SessionStore(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        ensureChannel();
        startForeground(NOTIFICATION_ID, notification("Bramka SMS uruchomiona"));
        scheduler.scheduleWithFixedDelay(this::pollOnce, 0, 6, TimeUnit.SECONDS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        prefs.edit().putBoolean(KEY_ENABLED, true).apply();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        // Nie wyłączamy trwałej flagi tutaj. Android może zniszczyć usługę przy braku pamięci
        // lub podczas aktualizacji procesu. START_STICKY / BootReceiver uruchomi ją ponownie.
        scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void pollOnce() {
        if (!inFlight.compareAndSet(false, true)) return;
        try {
            if (!session.hasSession() || !session.hasRole("sms_gateway")) {
                remember("Brak aktywnej sesji bramki SMS.", "", "");
                setEnabled(this, false);
                stopSelf();
                return;
            }
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                remember("Brak uprawnienia SEND_SMS.", "", "Nadaj uprawnienie SMS w aplikacji.");
                return;
            }

            JSONObject response = OracleApi.post("/api/v1/sms-gateway/next", session.token(), new JSONObject());
            JSONObject item = response.optJSONObject("item");
            if (item == null) {
                updateNotification("Bramka SMS aktywna · brak kolejki");
                return;
            }

            String id = item.optString("id", "");
            String recipient = item.optString("recipient", "");
            String body = item.optString("body", "");
            if (id.isEmpty() || recipient.isEmpty() || body.isEmpty()) {
                if (!id.isEmpty()) report(id, false, "Niekompletne zadanie SMS");
                return;
            }

            // Lokalny znacznik chroni przed ponownym wysłaniem, gdy SMS został już przekazany
            // systemowi Android, ale raport do Oracle nie zdążył wrócić przed zerwaniem sieci.
            if (prefs.getBoolean("sent_" + id, false)) {
                report(id, true, "");
                return;
            }

            try {
                sendSms(recipient, body);
                prefs.edit().putBoolean("sent_" + id, true).apply();
                remember("SMS wysłany", recipient, "");
                updateNotification("Wysłano SMS do " + recipient);
                report(id, true, "");
            } catch (Exception sendError) {
                String reason = readable(sendError);
                remember("Błąd wysyłki SMS", recipient, reason);
                updateNotification("Błąd SMS do " + recipient);
                report(id, false, reason);
            }
        } catch (Exception error) {
            remember("Błąd bramki SMS", "", readable(error));
            updateNotification("Błąd bramki SMS");
        } finally {
            inFlight.set(false);
        }
    }

    private void sendSms(String recipient, String body) {
        SmsManager manager = SmsManager.getDefault();
        ArrayList<String> parts = manager.divideMessage(body);
        if (parts.size() <= 1) manager.sendTextMessage(recipient, null, body, null, null);
        else manager.sendMultipartTextMessage(recipient, null, parts, null, null);
    }

    private void report(String id, boolean success, String error) {
        try {
            JSONObject body = new JSONObject();
            body.put("success", success);
            body.put("error", error == null ? "" : error);
            OracleApi.post("/api/v1/sms-gateway/" + id + "/report", session.token(), body);
        } catch (Exception reportError) {
            remember(success ? "SMS wysłany, raport oczekuje" : "Błąd raportu SMS", lastRecipient(this), readable(reportError));
        }
    }

    private void remember(String status, String recipient, String error) {
        prefs.edit()
                .putString(KEY_LAST_STATUS, status == null ? "" : status)
                .putString(KEY_LAST_RECIPIENT, recipient == null ? "" : recipient)
                .putString(KEY_LAST_ERROR, error == null ? "" : error)
                .putLong(KEY_LAST_AT, System.currentTimeMillis())
                .apply();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "WolfTaxi SMS Gateway", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Prywatna bramka SMS WolfTaxi");
        channel.enableLights(false);
        channel.setLightColor(Color.GREEN);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setContentTitle("WolfTaxi · Bramka SMS")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    private static String readable(Exception error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }
}
