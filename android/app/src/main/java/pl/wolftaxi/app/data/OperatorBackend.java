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
    private static final long POLL_MS = 15000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final SessionStore session;
    private final RealtimeClient realtime = new RealtimeClient();
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
    public void start(boolean adminMode) {
        this.adminMode = adminMode; running = true; main.removeCallbacks(poll);
        refresh();
        realtime.start(session.token(), (type,data) -> {
            if ("refresh".equals(type) || "hello".equals(type) || "sos".equals(type)) refresh();
        });
        main.postDelayed(poll, POLL_MS);
    }
    public void stop() { running = false; main.removeCallbacks(poll); realtime.stop(); }

    public void createOrder(String pickup, String destination, String regionId, String tariffId, ActionCallback cb) {
        createOrderAdvanced(pickup,destination,regionId,tariffId,"queue",1,false,false,false,false,0,"","",cb);
    }
    public void createOrderAdvanced(String pickup,String destination,String regionId,String tariffId,String dispatchMode,int passengers,boolean luggage,boolean pet,boolean englishRequired,boolean mineWarning,long scheduledFor,String passengerName,String passengerPhone,ActionCallback cb) {
        JSONObject body = new JSONObject();
        put(body,"pickupAddress",pickup); put(body,"destinationAddress",destination); put(body,"pickupRegionId",regionId); put(body,"tariffId",tariffId);
        put(body,"dispatchMode",dispatchMode); put(body,"passengerName",passengerName); put(body,"passengerPhone",passengerPhone); put(body,"passengerCount",passengers); put(body,"luggage",luggage); put(body,"pet",pet); put(body,"englishRequired",englishRequired); put(body,"mineWarning",mineWarning); if(scheduledFor>0)put(body,"scheduledFor",new java.util.Date(scheduledFor).toInstant().toString());
        action("/api/v1/dispatch/orders", body, cb);
    }
    public void assignOrder(String orderId, String driverId, ActionCallback cb) { action("/api/v1/dispatch/orders/"+orderId+"/assign", body("driverId",driverId), cb); }
    public void cancelOrder(String orderId, ActionCallback cb) { action("/api/v1/dispatch/orders/"+orderId+"/cancel", new JSONObject(), cb); }
    public void forceOrder(String orderId, String driverId, ActionCallback cb) { action("/api/v1/dispatch/orders/"+orderId+"/force", body("driverId",driverId), cb); }
    public void setDriverPriority(String driverId, int priority, ActionCallback cb) { JSONObject body=new JSONObject();put(body,"priority",priority);action("/api/v1/dispatch/drivers/"+driverId+"/priority",body,cb); }
    public void acknowledgeAlert(String alertId, ActionCallback cb) { action("/api/v1/dispatch/alerts/"+alertId+"/ack",new JSONObject(),cb); }
    public void closeAlert(String alertId, ActionCallback cb) { action("/api/v1/dispatch/alerts/"+alertId+"/close",new JSONObject(),cb); }
    public void sendMessage(String title, String bodyText, String type, ActionCallback cb) {
        sendMessage(title,bodyText,type,"all","",false,true,cb);
    }
    public void sendMessage(String title,String bodyText,String type,String targetType,String targetId,boolean requiresAck,boolean voiceRead,ActionCallback cb) {
        JSONObject body=new JSONObject();put(body,"title",title);put(body,"body",bodyText);put(body,"type",type);put(body,"targetType",targetType);put(body,"targetId",targetId);put(body,"requiresAck",requiresAck);put(body,"voiceRead",voiceRead);action("/api/v1/dispatch/messages",body,cb);
    }
    public void createUser(String email,String name,String password,boolean driver,boolean dispatcher,boolean admin,boolean smsGateway,String taxiId,int number,ActionCallback cb){
        JSONObject body=new JSONObject();put(body,"email",email);put(body,"name",name);put(body,"password",password);put(body,"taxiId",taxiId);put(body,"number",number);
        JSONArray roles=new JSONArray();if(driver)roles.put("driver");if(dispatcher)roles.put("dispatcher");if(admin)roles.put("admin");if(smsGateway)roles.put("sms_gateway");put(body,"roles",roles);action("/api/v1/admin/users",body,cb);
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
