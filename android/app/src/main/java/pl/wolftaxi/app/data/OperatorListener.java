package pl.wolftaxi.app.data;

import pl.wolftaxi.app.domain.operator.OperatorSnapshot;

public interface OperatorListener {
    void onOperatorSnapshot(OperatorSnapshot snapshot);
    void onOperatorError(String message);
}
