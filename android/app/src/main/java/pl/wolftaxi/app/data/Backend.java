package pl.wolftaxi.app.data;

import pl.wolftaxi.app.domain.DriverStatus;
import pl.wolftaxi.app.domain.OrderStatus;

public interface Backend {
    String modeLabel();
    boolean requiresLogin();
    boolean isSignedIn();
    String currentUserId();
    String currentDisplayName();
    String[] currentRoles();
    boolean hasRole(String role);
    void setListener(BackendListener listener);
    void start();
    void stop();
    void signIn(String email, String password, ActionCallback callback);
    void signOut(ActionCallback callback);
    void startShift(ActionCallback callback);
    void endShift(ActionCallback callback);
    void setStatus(DriverStatus status, ActionCallback callback);
    void joinQueue(String regionId, ActionCallback callback);
    void leaveQueue(ActionCallback callback);
    void setTariff(String tariffId, ActionCallback callback);
    void acceptOrder(String orderId, ActionCallback callback);
    void rejectOrder(String orderId, ActionCallback callback);
    void expireOrder(String orderId, ActionCallback callback);
    void advanceOrder(String orderId, OrderStatus nextStatus, ActionCallback callback);
    void simulateOffer(ActionCallback callback);
    void claimExchange(String orderId, ActionCallback callback);
    void sendSos(String note, ActionCallback callback);
    void cancelSos(ActionCallback callback);
    void acknowledgeMessage(String messageId, ActionCallback callback);
}
