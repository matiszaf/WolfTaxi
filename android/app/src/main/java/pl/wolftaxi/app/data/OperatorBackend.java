package pl.wolftaxi.app.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import pl.wolftaxi.app.domain.operator.OperatorSnapshot;

public final class OperatorBackend {
    private static final long POLL_MS = 2500;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final SessionStore session;
    private OperatorListener listener;
    private OperatorSnapshot lastSnapshot;
    private boolean running;
    private boolean adminMode;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running || !session.hasSession()) return;
            refresh();
            main.postDelayed(this, POLL_MS);
        }
    };

    public OperatorBackend(Context context) { session = new SessionStore(context); }
    public void setListener(OperatorListener listener) { this.listener = listener; }
    public void start(boolean adminMode) { this.adminMode = adminMode; running = true; main.removeCallbacks(poll); main.post(poll); }
    public void stop() { running = false; main.removeCallbacks(poll); }

    public void createOrder(String pickup, String destination, String regionId, String tariffId, ActionCallback cb) {
        JSONObject body = new JSONObject();
        put(body,"pickupAddress",pickup); put(body,"destinationAddress",destination); put(body,"pickupRegionId",regionId); put(body,"tariffId",tariffId);
        action("/api/v1/dispatch/orders", body, cb);
    }
    public void assignOrder(String orderId, String driverId, ActionCallback cb) { action("/api/v1/dispatch/orders/"+orderId+"/assign", body("driverId",driverId), cb); }
    public void cancelOrder(String orderId, ActionCallback cb) { action("/api/v1/dispatch/orders/"+orderId+"/cancel", new JSONObject(), cb); }
    public void sendMessage(String title, String bodyText, String type, ActionCallback cb) {
        JSONObject body=new JSONObject();put(body,"title",title);put(body,"body",bodyText);put(body,"type",type);action("/api/v1/dispatch/messages",body,cb);
    }
    public void createUser(String email,String name,String password,boolean driver,boolean dispatcher,boolean admin,String taxiId,int number,ActionCallback cb){
        JSONObject body=new JSONObject();put(body,"email",email);put(body,"name",name);put(body,"password",password);put(body,"taxiId",taxiId);put(body,"number",number);
        JSONArray roles=new JSONArray();if(driver)roles.put("driver");if(dispatcher)roles.put("dispatcher");if(admin)roles.put("admin");put(body,"roles",roles);action("/api/v1/admin/users",body,cb);
    }
    public void setUserEnabled(String userId, boolean enabled, ActionCallback cb){JSONObject body=new JSONObject();put(body,"enabled",enabled);action("/api/v1/admin/users/"+userId+"/enabled",body,cb);}
    public void saveTariff(String id,String name,double startFee,double pricePerKm,ActionCallback cb){JSONObject body=new JSONObject();put(body,"name",name);put(body,"shortName",id);put(body,"startFee",startFee);put(body,"pricePerKm",pricePerKm);put(body,"active",true);action("/api/v1/admin/tariffs/"+id,body,cb);}
    public void saveRegion(String id,String name,int priority,ActionCallback cb){JSONObject body=new JSONObject();put(body,"name",name);put(body,"shortName",id);put(body,"priority",priority);put(body,"active",true);put(body,"queueEnabled",true);action("/api/v1/admin/regions/"+id,body,cb);}
    public void saveZone(String id,String name,String tariffId,ActionCallback cb){JSONObject body=new JSONObject();put(body,"name",name);put(body,"defaultTariffId",tariffId);put(body,"multiplier",1);put(body,"active",true);action("/api/v1/admin/fare-zones/"+id,body,cb);}

    private void action(String path, JSONObject body, ActionCallback cb) {
        if (!session.hasSession()) { cb.complete(false,"Sesja wygasła."); return; }
        io.execute(() -> {
            try {
                JSONObject json=OracleApi.post(path,session.token(),body);
                cb.complete(true,json.optString("message","Zapisano"));
                refreshBlocking();
            } catch (OracleApi.UnauthorizedException e) {
                session.clear(); cb.complete(false,"Sesja wygasła. Zaloguj się ponownie.");
            } catch (Exception e) { cb.complete(false,readable(e)); }
        });
    }

    private void refresh() {
        if (!inFlight.compareAndSet(false,true)) return;
        io.execute(() -> { try { refreshBlocking(); } finally { inFlight.set(false); } });
    }
    private void refreshBlocking() {
        try {
            String path = adminMode ? "/api/v1/admin/snapshot" : "/api/v1/dispatch/snapshot";
            OperatorSnapshot value=OperatorSnapshotMapper.fromJson(OracleApi.get(path,session.token()));
            lastSnapshot=value;
            if(listener!=null)main.post(()->listener.onOperatorSnapshot(value));
        } catch (OracleApi.UnauthorizedException e) {
            session.clear(); if(listener!=null)main.post(()->listener.onOperatorError("Sesja wygasła. Zaloguj się ponownie."));
        } catch (Exception e) {
            if(lastSnapshot!=null){lastSnapshot.connected=false;if(listener!=null)main.post(()->listener.onOperatorSnapshot(lastSnapshot));}
            if(listener!=null)main.post(()->listener.onOperatorError(readable(e)));
        }
    }
    private static JSONObject body(String key,Object value){JSONObject out=new JSONObject();put(out,key,value);return out;}
    private static void put(JSONObject json,String key,Object value){try{json.put(key,value);}catch(Exception ignored){}}
    private static String readable(Exception e){String m=e.getMessage();return m==null||m.trim().isEmpty()?e.getClass().getSimpleName():m;}
}
