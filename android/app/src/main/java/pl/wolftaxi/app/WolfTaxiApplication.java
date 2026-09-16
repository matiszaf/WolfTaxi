package pl.wolftaxi.app;

import android.app.Application;
import pl.wolftaxi.app.data.BackendProvider;

public final class WolfTaxiApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        BackendProvider.initialize(this);
    }
}
