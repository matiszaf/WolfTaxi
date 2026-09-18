package pl.wolftaxi.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;

public final class SessionStore {
    private static final String PREFS = "wolftaxi_session";
    private static final String TOKEN = "token";
    private static final String USER_ID = "user_id";
    private static final String DISPLAY_NAME = "display_name";
    private static final String ROLES = "roles";

    private final SharedPreferences prefs;

    public SessionStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String token() { return prefs.getString(TOKEN, ""); }
    public String userId() { return prefs.getString(USER_ID, ""); }
    public String displayName() { return prefs.getString(DISPLAY_NAME, ""); }
    public boolean hasSession() { return !token().isEmpty(); }

    public String[] roles() {
        String raw = prefs.getString(ROLES, "");
        if (raw == null || raw.trim().isEmpty()) return new String[0];
        String[] parts = raw.split(",");
        ArrayList<String> out = new ArrayList<>();
        for (String part : parts) {
            String role = part == null ? "" : part.trim().toLowerCase();
            if (!role.isEmpty() && !out.contains(role)) out.add(role);
        }
        return out.toArray(new String[0]);
    }

    public boolean hasRole(String role) {
        if (role == null) return false;
        for (String value : roles()) if (role.equalsIgnoreCase(value)) return true;
        return false;
    }

    public void save(String token, String userId, String displayName, String[] roles) {
        StringBuilder csv = new StringBuilder();
        if (roles != null) for (String role : roles) {
            if (role == null || role.trim().isEmpty()) continue;
            if (csv.length() > 0) csv.append(',');
            csv.append(role.trim().toLowerCase());
        }
        prefs.edit().putString(TOKEN, token == null ? "" : token)
                .putString(USER_ID, userId == null ? "" : userId)
                .putString(DISPLAY_NAME, displayName == null ? "" : displayName)
                .putString(ROLES, csv.toString())
                .apply();
    }

    public void clear() { prefs.edit().clear().apply(); }
}
