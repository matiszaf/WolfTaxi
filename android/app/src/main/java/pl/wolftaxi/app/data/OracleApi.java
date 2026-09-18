package pl.wolftaxi.app.data;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import pl.wolftaxi.app.BuildConfig;

public final class OracleApi {
    private OracleApi() {}

    public static JSONObject get(String path, String token) throws Exception {
        return request("GET", path, token, null);
    }

    public static JSONObject post(String path, String token, JSONObject body) throws Exception {
        return request("POST", path, token, body == null ? new JSONObject() : body);
    }

    private static JSONObject request(String method, String path, String token, JSONObject body) throws Exception {
        String base = BuildConfig.API_BASE_URL == null ? "" : BuildConfig.API_BASE_URL.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.isEmpty()) throw new IllegalStateException("Brak wolftaxi.apiUrl w android/local.properties");
        URL url = new URL(base + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);

        if (body != null) {
            connection.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
        }

        int code = connection.getResponseCode();
        InputStream input = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = read(input);
        JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
        if (code < 200 || code >= 300) {
            String message = json.optString("message", "HTTP " + code);
            if (code == 401) throw new UnauthorizedException(message);
            throw new ApiException(code, message);
        }
        return json;
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    public static final class ApiException extends Exception {
        public final int statusCode;
        public ApiException(int statusCode, String message) { super(message); this.statusCode = statusCode; }
    }

    public static final class UnauthorizedException extends Exception {
        public UnauthorizedException(String message) { super(message); }
    }
}
