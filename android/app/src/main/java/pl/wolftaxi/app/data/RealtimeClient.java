package pl.wolftaxi.app.data;

import android.os.Handler;
import android.net.Uri;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import pl.wolftaxi.app.BuildConfig;

final class RealtimeClient {
    interface Listener { void onEvent(String type, JSONObject data); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private WebSocket socket;
    private boolean running;
    private int retry;
    private String token = "";
    private Listener listener;

    void start(String token, Listener listener) {
        stop();
        this.running = true;
        this.token = token == null ? "" : token;
        this.listener = listener;
        connect();
    }

    void stop() {
        running = false;
        retry = 0;
        main.removeCallbacksAndMessages(null);
        if (socket != null) {
            try { socket.close(1000, "bye"); } catch (Exception ignored) {}
            socket = null;
        }
    }

    private void connect() {
        if (!running || token.isEmpty()) return;
        String base = BuildConfig.API_BASE_URL == null ? "" : BuildConfig.API_BASE_URL.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length()-1);
        if (base.isEmpty()) return;
        String wsBase = base.startsWith("https://") ? "wss://" + base.substring(8)
                : base.startsWith("http://") ? "ws://" + base.substring(7) : base;
        String url = wsBase + "/ws?token=" + Uri.encode(token);
        Request req = new Request.Builder().url(url).build();
        socket = client.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) { retry = 0; }
            @Override public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject root = new JSONObject(text);
                    String type = root.optString("type", "refresh");
                    JSONObject data = root.optJSONObject("data");
                    if (data == null) data = new JSONObject();
                    JSONObject finalData = data;
                    if (listener != null) main.post(() -> listener.onEvent(type, finalData));
                } catch (Exception ignored) {}
            }
            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) { scheduleReconnect(); }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) { if (running) scheduleReconnect(); }
        });
    }

    private void scheduleReconnect() {
        if (!running) return;
        socket = null;
        long delay = Math.min(15000, 1000L * (1L << Math.min(4, retry++)));
        main.removeCallbacks(reconnect);
        main.postDelayed(reconnect, delay);
    }

    private final Runnable reconnect = this::connect;
}
