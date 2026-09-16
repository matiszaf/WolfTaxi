package pl.wolftaxi.app.data;

import android.content.Context;
import android.content.SharedPreferences;

public final class SessionStore {
    private static final String PREFS = "wolftaxi_session";
    private static final String TOKEN = "token";
    private static final String USER_ID = "user_id";

    private final SharedPreferences prefs;

    public SessionStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String token() { return prefs.getString(TOKEN, ""); }
    public String userId() { return prefs.getString(USER_ID, ""); }
    public boolean hasSession() { return !token().isEmpty(); }

    public void save(String token, String userId) {
        prefs.edit().putString(TOKEN, token == null ? "" : token)
                .putString(USER_ID, userId == null ? "" : userId)
                .apply();
    }

    public void clear() { prefs.edit().clear().apply(); }
}
