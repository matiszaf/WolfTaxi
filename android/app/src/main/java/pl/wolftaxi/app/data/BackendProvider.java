package pl.wolftaxi.app.data;

import android.content.Context;

import pl.wolftaxi.app.BuildConfig;

public final class BackendProvider {
    private static Backend backend;

    private BackendProvider() {}

    public static synchronized void initialize(Context context) {
        if (backend != null) return;
        String url = BuildConfig.API_BASE_URL == null ? "" : BuildConfig.API_BASE_URL.trim();
        backend = url.isEmpty()
                ? new DemoBackend(context.getApplicationContext())
                : new OracleBackend(context.getApplicationContext());
    }

    public static synchronized Backend get(Context context) {
        if (backend == null) initialize(context);
        return backend;
    }
}
