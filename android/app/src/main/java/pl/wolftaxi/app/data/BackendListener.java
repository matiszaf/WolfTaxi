package pl.wolftaxi.app.data;

import pl.wolftaxi.app.domain.model.DriverSnapshot;

public interface BackendListener {
    void onSnapshot(DriverSnapshot snapshot);
    void onError(String message);
}
