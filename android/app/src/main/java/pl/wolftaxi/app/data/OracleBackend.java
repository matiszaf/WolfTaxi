package pl.wolftaxi.app.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import pl.wolftaxi.app.domain.DriverStatus;
import pl.wolftaxi.app.domain.OrderStatus;
import pl.wolftaxi.app.domain.model.DriverSnapshot;

public final class OracleBackend implements Backend {
    private static final long POLL_MS = 2500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final SessionStore session;
    private BackendListener listener;
    private DriverSnapshot lastSnapshot;
    private boolean running;
    private long lastErrorAt;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running || !isSignedIn()) return;
            refresh();
            main.postDelayed(this, POLL_MS);
        }
    };

    public OracleBackend(Context context) {
        session = new SessionStore(context);
    }

    @Override public String modeLabel() { return "ORACLE"; }
    @Override public boolean requiresLogin() { return true; }
    @Override public boolean isSignedIn() { return session.hasSession(); }
    @Override public String currentUserId() { return session.userId(); }
    @Override public String currentDisplayName() { return session.displayName(); }
    @Override public String[] currentRoles() { return session.roles(); }
    @Override public boolean hasRole(String role) { return session.hasRole(role); }
    @Override public void setListener(BackendListener listener) { this.listener = listener; }

    @Override public void start() {
        running = true;
        main.removeCallbacks(poll);
        if (isSignedIn() && hasRole("driver")) main.post(poll);
    }

    @Override public void stop() {
        running = false;
        main.removeCallbacks(poll);
    }

    @Override public void signIn(String email, String password, ActionCallback callback) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("email", email.trim()).put("password", password);
                JSONObject json = OracleApi.post("/api/v1/auth/login", "", body);
                String token = json.optString("token", "");
                JSONObject user = json.optJSONObject("user");
                String userId = user == null ? "" : user.optString("id", "");
                String displayName = user == null ? "" : user.optString("name", "");
                JSONArray rolesJson = user == null ? null : user.optJSONArray("roles");
                String[] roles = new String[rolesJson == null ? 0 : rolesJson.length()];
                for (int i = 0; i < roles.length; i++) roles[i] = rolesJson.optString(i, "");
                if (token.isEmpty()) throw new IllegalStateException("Serwer nie zwrócił tokenu sesji.");
                session.save(token, userId, displayName, roles);
                callback.complete(true, json.optString("message", "Zalogowano"));
                if (session.hasRole("driver")) start();
            } catch (Exception error) {
                callback.complete(false, readable(error));
            }
        });
    }

    @Override public void signOut(ActionCallback callback) {
        String token = session.token();
        session.clear();
        stop();
        io.execute(() -> {
            try { if (!token.isEmpty()) OracleApi.post("/api/v1/auth/logout", token, new JSONObject()); }
            catch (Exception ignored) {}
        });
        callback.complete(true, "Wylogowano");
    }

    @Override public void startShift(ActionCallback callback) { action("/api/v1/driver/shift/start", new JSONObject(), callback); }
    @Override public void endShift(ActionCallback callback) { action("/api/v1/driver/shift/end", new JSONObject(), callback); }

    @Override public void setStatus(DriverStatus status, ActionCallback callback) {
        action("/api/v1/driver/status", body("status", status.wire), callback);
    }

    @Override public void joinQueue(String regionId, ActionCallback callback) {
        action("/api/v1/driver/queue/join", body("regionId", regionId), callback);
    }

    @Override public void leaveQueue(ActionCallback callback) { action("/api/v1/driver/queue/leave", new JSONObject(), callback); }

    @Override public void setTariff(String tariffId, ActionCallback callback) {
        action("/api/v1/driver/tariff", body("tariffId", tariffId), callback);
    }

    @Override public void acceptOrder(String orderId, ActionCallback callback) {
        action("/api/v1/orders/" + orderId + "/accept", new JSONObject(), callback);
    }

    @Override public void rejectOrder(String orderId, ActionCallback callback) {
        action("/api/v1/orders/" + orderId + "/reject", new JSONObject(), callback);
    }

    @Override public void expireOrder(String orderId, ActionCallback callback) {
        action("/api/v1/orders/" + orderId + "/expire", new JSONObject(), callback);
    }

    @Override public void advanceOrder(String orderId, OrderStatus nextStatus, ActionCallback callback) {
        action("/api/v1/orders/" + orderId + "/advance", body("nextStatus", nextStatus.wire), callback);
    }

    @Override public void simulateOffer(ActionCallback callback) {
        action("/api/v1/dev/simulate-offer", new JSONObject(), callback);
    }

    private JSONObject body(String key, Object value) {
        JSONObject out = new JSONObject();
        try { out.put(key, value); } catch (Exception ignored) {}
        return out;
    }

    private void action(String path, JSONObject body, ActionCallback callback) {
        if (!isSignedIn()) {
            callback.complete(false, "Sesja wygasła. Zaloguj się ponownie.");
            return;
        }
        io.execute(() -> {
            try {
                JSONObject json = OracleApi.post(path, session.token(), body);
                callback.complete(true, json.optString("message", "Zapisano"));
                refreshBlocking();
            } catch (OracleApi.UnauthorizedException error) {
                session.clear();
                callback.complete(false, "Sesja wygasła. Zaloguj się ponownie.");
            } catch (Exception error) {
                callback.complete(false, readable(error));
            }
        });
    }

    private void refresh() {
        if (!inFlight.compareAndSet(false, true)) return;
        io.execute(() -> {
            try { refreshBlocking(); }
            finally { inFlight.set(false); }
        });
    }

    private void refreshBlocking() {
        if (!isSignedIn()) return;
        try {
            JSONObject json = OracleApi.get("/api/v1/driver/snapshot", session.token());
            lastSnapshot = SnapshotMapper.fromJson(json);
            publish(lastSnapshot);
        } catch (OracleApi.UnauthorizedException error) {
            session.clear();
            emitError("Sesja wygasła. Zaloguj się ponownie.");
        } catch (Exception error) {
            if (lastSnapshot != null) {
                lastSnapshot.connected = false;
                publish(lastSnapshot);
            }
            long now = System.currentTimeMillis();
            if (now - lastErrorAt > 15000) {
                lastErrorAt = now;
                emitError(readable(error));
            }
        }
    }

    private void publish(DriverSnapshot snapshot) {
        if (listener != null) main.post(() -> listener.onSnapshot(snapshot));
    }

    private void emitError(String message) {
        if (listener != null) main.post(() -> listener.onError(message));
    }

    private String readable(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
