package pl.wolftaxi.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

import pl.wolftaxi.app.data.ActionCallback;
import pl.wolftaxi.app.data.Backend;
import pl.wolftaxi.app.data.BackendListener;
import pl.wolftaxi.app.data.BackendProvider;
import pl.wolftaxi.app.data.OperatorBackend;
import pl.wolftaxi.app.data.OperatorListener;
import pl.wolftaxi.app.domain.DriverStatus;
import pl.wolftaxi.app.domain.OrderStatus;
import pl.wolftaxi.app.domain.model.DispatchMessage;
import pl.wolftaxi.app.domain.model.DriverSnapshot;
import pl.wolftaxi.app.domain.model.Order;
import pl.wolftaxi.app.domain.model.Region;
import pl.wolftaxi.app.domain.model.Tariff;
import pl.wolftaxi.app.domain.model.RegionStat;
import pl.wolftaxi.app.domain.operator.OperatorDriver;
import pl.wolftaxi.app.domain.operator.OperatorSnapshot;
import pl.wolftaxi.app.domain.operator.UserAccount;
import pl.wolftaxi.app.domain.operator.SafetyAlertItem;
import pl.wolftaxi.app.service.DriverLocationService;
import pl.wolftaxi.app.ui.Ui;

public final class MainActivity extends Activity implements BackendListener, OperatorListener {
    private enum AppMode { DRIVER, DISPATCHER, ADMIN }
    private static final int REQUEST_LOCATION = 140;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NumberFormat money = NumberFormat.getCurrencyInstance(new Locale("pl", "PL"));
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private Backend backend;
    private OperatorBackend operatorBackend;
    private DriverSnapshot snapshot;
    private OperatorSnapshot operatorSnapshot;
    private AppMode mode;
    private LinearLayout root;
    private TextView countdown;
    private TextView toastLine;
    private Runnable countdownTick;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String lastSpokenOfferId = "";
    private String lastSpokenForcedId = "";
    private String lastSpokenExchangeId = "";
    private final Set<String> spokenMessageIds = new HashSet<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) tts.setLanguage(new Locale("pl", "PL"));
        });
        backend = BackendProvider.get(this);
        backend.setListener(this);
        operatorBackend = new OperatorBackend(this);
        operatorBackend.setListener(this);
        if (backend.requiresLogin() && !backend.isSignedIn()) loginScreen();
        else enterAfterLogin();
    }

    @Override public void onResume() {
        super.onResume();
        if (mode == AppMode.DRIVER && backend.isSignedIn()) backend.start();
        else if (mode == AppMode.DISPATCHER) operatorBackend.start(false);
        else if (mode == AppMode.ADMIN) operatorBackend.start(true);
    }

    @Override public void onPause() { super.onPause(); stopCountdown(); }
    @Override public void onDestroy() { backend.stop(); operatorBackend.stop(); handler.removeCallbacksAndMessages(null); if(tts!=null){tts.stop();tts.shutdown();} super.onDestroy(); }

    @Override public void onSnapshot(DriverSnapshot value) {
        runOnUiThread(() -> {
            snapshot = value;
            announceDriverSnapshot(value);
            if (mode != AppMode.DRIVER || root == null || !backend.isSignedIn()) return;
            renderDriverDashboard();
            if (snapshot.driver.onShift) ensureLocationService(false);
        });
    }
    @Override public void onError(String message) { runOnUiThread(() -> showMessage("Błąd: " + message, false)); }
    @Override public void onOperatorSnapshot(OperatorSnapshot value) { runOnUiThread(() -> { operatorSnapshot=value; if(mode==AppMode.DISPATCHER||mode==AppMode.ADMIN) renderOperatorDashboard(); }); }
    @Override public void onOperatorError(String message) { runOnUiThread(() -> showMessage("Błąd: " + message, false)); }

    private void loginScreen() {
        mode = null; backend.stop(); operatorBackend.stop(); stopLocationService(); stopCountdown();
        ScrollView scroll = baseScroll();
        LinearLayout content = Ui.column(this);
        content.setPadding(Ui.dp(this, 24), Ui.dp(this, 42), Ui.dp(this, 24), Ui.dp(this, 30));
        scroll.addView(content); setContentView(scroll); root = null;
        Ui.text(this, content, "WOLFTAXI", 15, Ui.GREEN, true);
        Ui.text(this, content, "Jeden system", 31, Ui.TEXT, true);
        Ui.text(this, content, "Kierowca · Dyspozytor · Administrator", 14, Ui.MUTED, false);
        LinearLayout card = Ui.card(this, content);
        EditText email = field("E-mail", false); EditText password = field("Hasło", true);
        card.addView(email); card.addView(password); toastLine = Ui.text(this, card, "", 13, Ui.MUTED, false);
        Button signIn = Ui.button(this, card, "ZALOGUJ", Ui.GREEN, v -> {
            v.setEnabled(false);
            backend.signIn(email.getText().toString(), password.getText().toString(), (ok, message) -> runOnUiThread(() -> {
                v.setEnabled(true); if(!ok){showMessage(message,false);return;} enterAfterLogin();
            }));
        }); signIn.setEnabled(true);
        Ui.text(this, content, "Po zalogowaniu WolfTaxi uruchomi właściwy tryb na podstawie ról konta. Konto z kilkoma rolami może przełączać tryb bez ponownego logowania.", 13, Ui.MUTED, false);
    }

    private void enterAfterLogin() {
        if (!backend.isSignedIn()) { loginScreen(); return; }
        int count = roleCount();
        if (count > 1) showModeChooser();
        else if (backend.hasRole("driver")) switchMode(AppMode.DRIVER);
        else if (backend.hasRole("dispatcher")) switchMode(AppMode.DISPATCHER);
        else if (backend.hasRole("admin")) switchMode(AppMode.ADMIN);
        else { showMessage("Konto nie ma aktywnej roli.", false); loginScreen(); }
    }

    private int roleCount(){int n=0;if(backend.hasRole("driver"))n++;if(backend.hasRole("dispatcher"))n++;if(backend.hasRole("admin"))n++;return n;}

    private void showModeChooser() {
        backend.stop(); operatorBackend.stop(); stopCountdown();
        ScrollView scroll=baseScroll(); LinearLayout content=Ui.column(this); content.setPadding(Ui.dp(this,24),Ui.dp(this,42),Ui.dp(this,24),Ui.dp(this,30)); scroll.addView(content); setContentView(scroll); root=null;
        Ui.text(this,content,"WOLFTAXI",15,Ui.GREEN,true); Ui.text(this,content,"Wybierz tryb",31,Ui.TEXT,true);
        Ui.text(this,content,safe(backend.currentDisplayName()),14,Ui.MUTED,false);
        if(backend.hasRole("driver")) Ui.button(this,content,"KIEROWCA",Ui.GREEN,v->switchMode(AppMode.DRIVER));
        if(backend.hasRole("dispatcher")) Ui.button(this,content,"DYSPOZYTOR",Ui.BLUE,v->switchMode(AppMode.DISPATCHER));
        if(backend.hasRole("admin")) Ui.button(this,content,"ADMINISTRATOR",Ui.ORANGE,v->switchMode(AppMode.ADMIN));
        Ui.button(this,content,"WYLOGUJ",Color.rgb(93,112,118),v->logout());
    }

    private void switchMode(AppMode next) {
        mode=next; stopCountdown(); backend.stop(); operatorBackend.stop();
        if(next!=AppMode.DRIVER) stopLocationService();
        dashboardShell();
        if(next==AppMode.DRIVER){ snapshot=null; renderDriverDashboard(); backend.start(); }
        else { operatorSnapshot=null; renderOperatorDashboard(); operatorBackend.start(next==AppMode.ADMIN); }
    }

    private EditText field(String hint, boolean password) {
        EditText input = new EditText(this); input.setHint(hint); input.setHintTextColor(Color.rgb(104,132,132)); input.setTextColor(Ui.TEXT); input.setSingleLine(true);
        input.setInputType(password ? InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        input.setPadding(Ui.dp(this,10),Ui.dp(this,12),Ui.dp(this,10),Ui.dp(this,12)); return input;
    }
    private EditText plainField(String hint){EditText x=field(hint,false);x.setInputType(InputType.TYPE_CLASS_TEXT);return x;}
    private EditText numberField(String hint){EditText x=field(hint,false);x.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);return x;}

    private void dashboardShell() {
        ScrollView scroll=baseScroll(); root=Ui.column(this); root.setPadding(Ui.dp(this,6),Ui.dp(this,6),Ui.dp(this,6),Ui.dp(this,14)); scroll.addView(root); setContentView(scroll);
    }
    private ScrollView baseScroll() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Ui.BG);
        if(Build.VERSION.SDK_INT>=30)scroll.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        return scroll;
    }

    // ---------------- DRIVER UI ----------------
    private void renderDriverDashboard() {
        if(root==null||mode!=AppMode.DRIVER)return; stopCountdown(); root.removeAllViews(); toastLine=null;
        if(snapshot==null){header("Terminal kierowcy","Łączenie…",Ui.GREEN);return;}
        renderDriverHeader(); if(!snapshot.connected)renderConnectionWarning(); renderSafetyAlert(); renderDriverState(); renderRegionAndTariff(); renderRegionStats(); renderOrderArea(); renderExchange(); renderMessages(); renderHistory(); renderDriverActions(); toastLine=Ui.text(this,root,"",13,Ui.MUTED,false);
    }
    private void renderDriverHeader(){LinearLayout h=Ui.row(this);root.addView(h,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));LinearLayout l=Ui.column(this);h.addView(l,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));Ui.text(this,l,"WOLFTAXI",15,Ui.GREEN,true);Ui.text(this,l,"TERMINAL KIEROWCY",20,Ui.TEXT,true);String taxi=snapshot.driver.number>0?"TAXI "+snapshot.driver.number:"KIEROWCA";TextView b=Ui.text(this,h,taxi+" · ORACLE",12,snapshot.connected?Ui.GREEN:Ui.RED,true);b.setGravity(Gravity.END);}
    private void header(String title,String subtitle,int color){Ui.text(this,root,"WOLFTAXI",15,Ui.GREEN,true);Ui.text(this,root,title,25,Ui.TEXT,true);Ui.text(this,root,subtitle,13,color,false);}
    private void renderConnectionWarning(){LinearLayout card=Ui.card(this,root);Ui.text(this,card,"BRAK POŁĄCZENIA",15,Ui.RED,true);Ui.text(this,card,"Bieżące informacje mogą być nieaktualne.",13,Ui.TEXT,false);}
    private void renderDriverState(){LinearLayout card=Ui.card(this,root);Ui.text(this,card,snapshot.driver.status.label.toUpperCase(Locale.ROOT),28,statusColor(snapshot.driver.status),true);Ui.text(this,card,(snapshot.driver.onShift?"Zmiana aktywna":"Poza zmianą")+" · "+safe(snapshot.driver.name),14,Ui.MUTED,false);if(!safe(snapshot.driver.vehicleId).isEmpty())Ui.text(this,card,"Samochód: "+snapshot.driver.vehicleId,13,Ui.MUTED,false);}
    private void renderRegionAndTariff(){LinearLayout row=Ui.row(this);root.addView(row,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));LinearLayout r=compactCard(row);Ui.text(this,r,"REGION",11,Ui.MUTED,true);Ui.text(this,r,snapshot.region==null?"—":snapshot.region.shortName.isEmpty()?snapshot.region.name:snapshot.region.shortName,22,Ui.TEXT,true);Ui.text(this,r,snapshot.queuePosition>0?snapshot.queuePosition+" / "+snapshot.queueSize:"poza kolejką",13,snapshot.queuePosition>0?Ui.BLUE:Ui.MUTED,false);LinearLayout t=compactCard(row);Ui.text(this,t,"TARYFA",11,Ui.MUTED,true);Ui.text(this,t,snapshot.tariff==null?"—":snapshot.tariff.shortName,22,Ui.TEXT,true);Ui.text(this,t,snapshot.tariff==null?"brak danych":money.format(snapshot.tariff.pricePerKm)+"/km",13,Ui.MUTED,false);LinearLayout z=compactCard(row);Ui.text(this,z,"STREFA",11,Ui.MUTED,true);Ui.text(this,z,snapshot.fareZone==null?"—":snapshot.fareZone.name,17,Ui.TEXT,true);Ui.text(this,z,"taryfowa",12,Ui.MUTED,false);}
    private LinearLayout compactCard(LinearLayout row){LinearLayout card=Ui.column(this);card.setPadding(Ui.dp(this,10),Ui.dp(this,10),Ui.dp(this,10),Ui.dp(this,10));android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(Ui.CARD);bg.setCornerRadius(Ui.dp(this,2));bg.setStroke(Ui.dp(this,1),Ui.LINE);card.setBackground(bg);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);p.setMargins(Ui.dp(this,3),0,Ui.dp(this,3),Ui.dp(this,10));row.addView(card,p);return card;}
    private void renderOrderArea(){if(snapshot.offer!=null){renderOffer(snapshot.offer);return;}if(snapshot.activeOrder!=null){renderActiveOrder(snapshot.activeOrder);return;}LinearLayout card=Ui.card(this,root);Ui.text(this,card,"BRAK AKTYWNEGO ZLECENIA",17,Ui.TEXT,true);Ui.text(this,card,snapshot.driver.status==DriverStatus.IN_QUEUE?"Oczekujesz w kolejce regionu.":"Ustaw status WOLNY lub wejdź do kolejki regionu.",13,Ui.MUTED,false);if(("DEMO".equals(snapshot.backendMode)||(BuildConfig.DEBUG&&"ORACLE".equals(snapshot.backendMode)))&&snapshot.driver.onShift)Ui.button(this,card,"TEST · WYGENERUJ ZLECENIE",Ui.ORANGE,v->action(backend::simulateOffer));}
    private void renderOffer(Order order){
        LinearLayout card=Ui.card(this,root);
        Ui.header(this,card,"NOWE ZLECENIE / OFERTA");
        countdown=Ui.text(this,card,"",30,Ui.ORANGE,true);
        Ui.text(this,card,safe(order.pickupAddress),21,Ui.TEXT,true);
        if(!safe(order.destinationAddress).isEmpty())Ui.text(this,card,"→ "+order.destinationAddress,16,Ui.TEXT,false);
        String meta=order.passengerCount+" os. · "+order.paymentMethod.label;
        if(order.cardRequired)meta+=" · KARTA"; if(order.luggage)meta+=" · BAGAŻ"; if(order.pet)meta+=" · ZWIERZĘ"; if(order.englishRequired)meta+=" · EN";
        Ui.text(this,card,meta,12,Ui.MUTED,false);
        if(order.mineWarning)Ui.text(this,card,"⚠ UWAGA: ZLECENIE OZNACZONE JAKO RYZYKOWNE",12,Ui.MAGENTA,true);
        if(!safe(order.notes).isEmpty())Ui.text(this,card,order.notes,12,Ui.MUTED,false);
        if(order.estimatedPrice>0)Ui.text(this,card,"Szacunkowo: "+money.format(order.estimatedPrice),13,Ui.TEXT,true);
        LinearLayout row=Ui.row(this);card.addView(row);
        Ui.rowButton(this,row,"ODRZUĆ",Ui.RED,v->confirm("Odrzucić zlecenie?",()->action(cb->backend.rejectOrder(order.id,cb))));
        Ui.rowButton(this,row,"PRZYJMIJ",Ui.GREEN,v->action(cb->backend.acceptOrder(order.id,cb)));
        startCountdown(order.id,order.offerExpiresAt);
    }
    private void renderActiveOrder(Order order){
        LinearLayout card=Ui.card(this,root);
        Ui.header(this,card,(order.forced?"ZLECENIE Z NAKAZU · ":"ZLECENIE · ")+safe(order.id));
        if(order.forced)Ui.text(this,card,"NAKAZ CENTRALI",15,Ui.MAGENTA,true);
        Ui.text(this,card,order.status.label.toUpperCase(Locale.ROOT),22,statusColor(snapshot.driver.status),true);
        Ui.text(this,card,"PODSTAWIENIE",10,Ui.MUTED,true); Ui.text(this,card,safe(order.pickupAddress),19,Ui.TEXT,true);
        if(!safe(order.destinationAddress).isEmpty()){Ui.text(this,card,"CEL",10,Ui.MUTED,true);Ui.text(this,card,order.destinationAddress,17,Ui.TEXT,false);}
        String req=order.passengerCount+" os."+(order.luggage?" · bagaż":"")+(order.pet?" · zwierzę":"")+(order.englishRequired?" · EN":""); Ui.text(this,card,req,12,Ui.MUTED,false);
        if(order.mineWarning)Ui.text(this,card,"⚠ OZNACZENIE RYZYKA",12,Ui.MAGENTA,true);
        if(!safe(order.passengerPhone).isEmpty())Ui.text(this,card,"Kontakt: "+order.passengerPhone,12,Ui.MUTED,false);
        if(!safe(order.notes).isEmpty())Ui.text(this,card,"Uwagi: "+order.notes,12,Ui.MUTED,false);
        LinearLayout utilities=Ui.row(this);card.addView(utilities);String navAddress=order.status==OrderStatus.IN_PROGRESS&&!safe(order.destinationAddress).isEmpty()?order.destinationAddress:order.pickupAddress;
        Ui.rowButton(this,utilities,"NAWIGACJA",Ui.BLUE,v->openNavigation(navAddress));if(!safe(order.passengerPhone).isEmpty())Ui.rowButton(this,utilities,"ZADZWOŃ",Ui.BLUE,v->openDialer(order.passengerPhone));
        OrderStatus next=nextStatus(order.status);if(next!=null)Ui.button(this,card,nextAction(next),next==OrderStatus.COMPLETED?Ui.RED:Ui.GREEN,v->nextOrderAction(order,next));
    }
    private void nextOrderAction(Order order,OrderStatus next){if(next==OrderStatus.COMPLETED)confirm("Zakończyć kurs?",()->action(cb->backend.advanceOrder(order.id,next,cb)));else action(cb->backend.advanceOrder(order.id,next,cb));}
    private void renderMessages(){
        LinearLayout card=Ui.card(this,root);Ui.header(this,card,"CENTRALA / KOMUNIKATY");
        if(snapshot.messages.isEmpty()){Ui.text(this,card,"Brak wiadomości.",12,Ui.MUTED,false);return;}
        for(int i=0;i<Math.min(snapshot.messages.size(),5);i++){
            DispatchMessage m=snapshot.messages.get(i);int color="urgent".equals(m.type)?Ui.RED:("warning".equals(m.type)?Ui.ORANGE:Ui.TEXT);String time=m.createdAt>0?clock.format(new Date(m.createdAt))+" · ":"";
            Ui.text(this,card,time+safe(m.title)+(m.acknowledged?" · POTWIERDZONO":""),11,Ui.MUTED,true);Ui.text(this,card,safe(m.body),13,color,false);
            if(m.requiresAck&&!m.acknowledged)Ui.button(this,card,"POTWIERDZAM ODCZYT",Ui.GREEN,v->action(cb->backend.acknowledgeMessage(m.id,cb)));
        }
    }
    private void renderHistory(){if(snapshot.history.isEmpty())return;LinearLayout card=Ui.card(this,root);Ui.text(this,card,"OSTATNIE KURSY",13,Ui.MUTED,true);for(int i=0;i<Math.min(snapshot.history.size(),3);i++){Order o=snapshot.history.get(i);String route=safe(o.pickupAddress)+(safe(o.destinationAddress).isEmpty()?"":" → "+o.destinationAddress);Ui.text(this,card,route,14,Ui.TEXT,i==0);String meta=o.createdAt>0?clock.format(new Date(o.createdAt)):"";if(o.finalPrice>0)meta+=(meta.isEmpty()?"":" · ")+money.format(o.finalPrice);if(!meta.isEmpty())Ui.text(this,card,meta,12,Ui.MUTED,false);}}
    private void renderDriverActions(){
        LinearLayout primary=Ui.row(this);root.addView(primary);
        if(!snapshot.driver.onShift)Ui.rowButton(this,primary,"ROZPOCZNIJ ZMIANĘ",Ui.GREEN,v->action(cb->backend.startShift((ok,msg)->{cb.complete(ok,msg);if(ok)runOnUiThread(()->ensureLocationService(true));})));
        else{Ui.rowButton(this,primary,"STATUS",Ui.BLUE,v->chooseStatus());Ui.rowButton(this,primary,"REGION",Ui.BLUE,v->chooseRegion());Ui.rowButton(this,primary,"TARYFA",Ui.BLUE,v->chooseTariff());}
        if(snapshot.driver.onShift){
            LinearLayout emergency=Ui.row(this);root.addView(emergency);
            if(snapshot.safetyAlert==null)Ui.rowButton(this,emergency,"SOS",Ui.RED,v->confirm("Wysłać ALARM SOS do centrali?",()->action(cb->backend.sendSos("SOS kierowcy",cb))));
            else Ui.rowButton(this,emergency,"ODWOŁAJ SOS",Ui.ORANGE,v->confirm("Odwołać alarm SOS?",()->action(backend::cancelSos)));
            if(snapshot.queuePosition>0)Ui.rowButton(this,emergency,"OPUŚĆ KOLEJKĘ",Ui.ORANGE,v->action(backend::leaveQueue));
        }
        if(snapshot.driver.onShift)Ui.button(this,root,"ZAKOŃCZ ZMIANĘ",Color.rgb(130,130,130),v->confirm("Zakończyć zmianę?",()->action(cb->backend.endShift((ok,msg)->{cb.complete(ok,msg);if(ok)runOnUiThread(this::stopLocationService);})))) ;
        renderSessionActions();
    }


    private void renderSafetyAlert(){if(snapshot.safetyAlert==null)return;LinearLayout card=Ui.card(this,root);Ui.header(this,card,"!!! ALARM SOS AKTYWNY !!!");Ui.text(this,card,"Status centrali: "+snapshot.safetyAlert.status.toUpperCase(Locale.ROOT),15,Ui.RED,true);if(!safe(snapshot.safetyAlert.note).isEmpty())Ui.text(this,card,snapshot.safetyAlert.note,12,Ui.TEXT,false);}

    private void renderRegionStats(){if(snapshot.regionStats.isEmpty())return;LinearLayout card=Ui.card(this,root);Ui.header(this,card,"REJONY / STAN");StringBuilder line=new StringBuilder();for(int i=0;i<Math.min(snapshot.regionStats.size(),8);i++){RegionStat r=snapshot.regionStats.get(i);if(line.length()>0)line.append("   ");line.append(r.shortName.isEmpty()?r.id:r.shortName).append(":").append(r.queued);}Ui.text(this,card,line.toString(),12,Ui.GREEN,true);}

    private void renderExchange(){if(snapshot.exchange.isEmpty()||snapshot.activeOrder!=null||snapshot.offer!=null)return;LinearLayout card=Ui.card(this,root);Ui.header(this,card,"GIEŁDA ZLECEŃ · "+snapshot.exchange.size());for(int i=0;i<Math.min(snapshot.exchange.size(),5);i++){Order o=snapshot.exchange.get(i);LinearLayout block=Ui.column(this);card.addView(block);String when=o.scheduledFor>0?new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date(o.scheduledFor)):"TERAZ";Ui.text(this,block,when+" · "+safe(o.pickupAddress)+(safe(o.destinationAddress).isEmpty()?"":" → "+o.destinationAddress),13,o.mineWarning?Ui.MAGENTA:Ui.TEXT,true);String req=o.passengerCount+" os."+(o.luggage?" · bagaż":"")+(o.pet?" · zwierzę":"")+(o.englishRequired?" · EN":"");Ui.text(this,block,req,11,Ui.MUTED,false);Ui.button(this,block,"POBIERZ Z GIEŁDY",Ui.GREEN,v->confirm("Pobrać "+o.id+"?",()->action(cb->backend.claimExchange(o.id,cb))));}}

    // ---------------- OPERATOR / ADMIN UI ----------------
    private void renderOperatorDashboard(){
        if(root==null||(mode!=AppMode.DISPATCHER&&mode!=AppMode.ADMIN))return;root.removeAllViews();toastLine=null;
        if(operatorSnapshot==null){header(mode==AppMode.ADMIN?"Administrator":"Dyspozytornia","Łączenie z Oracle…",Ui.BLUE);return;}
        LinearLayout h=Ui.row(this);root.addView(h);LinearLayout left=Ui.column(this);h.addView(left,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));Ui.text(this,left,"WOLFTAXI",15,Ui.GREEN,true);Ui.text(this,left,mode==AppMode.ADMIN?"Administrator":"Dyspozytornia",25,Ui.TEXT,true);TextView badge=Ui.text(this,h,(operatorSnapshot.connected?"ONLINE":"OFFLINE")+" · ORACLE",12,operatorSnapshot.connected?Ui.GREEN:Ui.RED,true);badge.setGravity(Gravity.END);
        renderOperatorAlerts(); renderOperatorStats(); renderOperatorActions(); renderOperatorDrivers(); renderOperatorOrders(); renderOperatorMessages(); if(mode==AppMode.ADMIN)renderAdminSummary(); renderSessionActions(); toastLine=Ui.text(this,root,"",13,Ui.MUTED,false);
    }
    private void renderOperatorAlerts(){if(operatorSnapshot.alerts.isEmpty())return;LinearLayout card=Ui.card(this,root);Ui.header(this,card,"!!! SOS / ALARMY !!!");for(SafetyAlertItem a:operatorSnapshot.alerts){Ui.text(this,card,a.taxiId+" · "+a.driverName+" · "+a.status.toUpperCase(Locale.ROOT),15,Ui.RED,true);if(!safe(a.note).isEmpty())Ui.text(this,card,a.note,12,Ui.TEXT,false);LinearLayout row=Ui.row(this);card.addView(row);if("active".equals(a.status))Ui.rowButton(this,row,"POTWIERDŹ",Ui.ORANGE,v->operatorAction(cb->operatorBackend.acknowledgeAlert(a.id,cb)));Ui.rowButton(this,row,"ZAMKNIJ",Ui.GREEN,v->operatorAction(cb->operatorBackend.closeAlert(a.id,cb)));}}

    private void renderOperatorStats(){int online=0,queued=0,active=0,waiting=0;for(OperatorDriver d:operatorSnapshot.drivers){if(d.online)online++;if(d.queuePosition>0)queued++;}for(Order o:operatorSnapshot.orders){if(o.status==OrderStatus.OFFERED||o.status.isActive())active++;if(o.status==OrderStatus.SEARCHING_DRIVER||o.status==OrderStatus.NO_DRIVER)waiting++;}LinearLayout row=Ui.row(this);root.addView(row);statCard(row,"ONLINE",String.valueOf(online),Ui.GREEN);statCard(row,"KOLEJKA",String.valueOf(queued),Ui.BLUE);statCard(row,"AKTYWNE",String.valueOf(active),Ui.ORANGE);statCard(row,"OCZEKUJE",String.valueOf(waiting),Ui.RED);}
    private void statCard(LinearLayout row,String label,String value,int color){LinearLayout c=compactCard(row);Ui.text(this,c,label,10,Ui.MUTED,true);Ui.text(this,c,value,25,color,true);}
    private void renderOperatorActions(){LinearLayout row=Ui.row(this);root.addView(row);Ui.rowButton(this,row,"NOWE ZLECENIE",Ui.GREEN,v->newOrderDialog());Ui.rowButton(this,row,"KOMUNIKAT",Ui.BLUE,v->messageDialog());if(mode==AppMode.ADMIN)Ui.rowButton(this,row,"ADMIN",Ui.ORANGE,v->adminMenu());}
    private void renderOperatorDrivers(){LinearLayout card=Ui.card(this,root);Ui.text(this,card,"TAXI",13,Ui.MUTED,true);if(operatorSnapshot.drivers.isEmpty()){Ui.text(this,card,"Brak kierowców.",13,Ui.MUTED,false);return;}for(OperatorDriver d:operatorSnapshot.drivers){LinearLayout row=Ui.row(this);card.addView(row);LinearLayout left=Ui.column(this);row.addView(left,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));Ui.text(this,left,safe(d.taxiId)+" · "+safe(d.name),15,Ui.TEXT,true);String meta=(d.online?"online":"offline")+" · "+d.status+" · "+(d.queueRegionId.isEmpty()?safe(d.currentRegionId):d.queueRegionId)+(d.queuePosition>0?" "+d.queuePosition+"/"+d.queueSize:"");Ui.text(this,left,meta,12,d.online?Ui.GREEN:Ui.MUTED,false);TextView t=Ui.text(this,row,safe(d.currentTariffId),12,Ui.MUTED,true);t.setGravity(Gravity.END);}}
    private void renderOperatorOrders(){LinearLayout card=Ui.card(this,root);Ui.text(this,card,"ZLECENIA",13,Ui.MUTED,true);int shown=0;for(Order o:operatorSnapshot.orders){if(shown++>=12)break;LinearLayout block=Ui.column(this);block.setPadding(0,Ui.dp(this,5),0,Ui.dp(this,8));card.addView(block);Ui.text(this,block,o.id+" · "+o.status.label.toUpperCase(Locale.ROOT),13,orderColor(o.status),true);Ui.text(this,block,safe(o.pickupAddress)+(safe(o.destinationAddress).isEmpty()?"":" → "+o.destinationAddress),15,Ui.TEXT,true);Ui.text(this,block,"Region: "+safe(o.pickupRegionId)+" · "+(o.estimatedPrice>0?money.format(o.estimatedPrice):"bez wyceny"),12,Ui.MUTED,false);if(o.status!=OrderStatus.COMPLETED&&o.status!=OrderStatus.CANCELLED){LinearLayout buttons=Ui.row(this);block.addView(buttons);Ui.rowButton(this,buttons,"PRZYPISZ",Ui.BLUE,v->assignOrderDialog(o));Ui.rowButton(this,buttons,"NAKAZ",Ui.MAGENTA,v->forceOrderDialog(o));Ui.rowButton(this,buttons,"ANULUJ",Ui.RED,v->confirm("Anulować "+o.id+"?",()->operatorAction(cb->operatorBackend.cancelOrder(o.id,cb))));}}if(shown==0)Ui.text(this,card,"Brak zleceń.",13,Ui.MUTED,false);}
    private void renderOperatorMessages(){LinearLayout card=Ui.card(this,root);Ui.text(this,card,"KOMUNIKATY",13,Ui.MUTED,true);for(int i=0;i<Math.min(operatorSnapshot.messages.size(),5);i++){DispatchMessage m=operatorSnapshot.messages.get(i);Ui.text(this,card,safe(m.title),12,Ui.MUTED,true);Ui.text(this,card,safe(m.body),14,"urgent".equals(m.type)?Ui.RED:("warning".equals(m.type)?Ui.ORANGE:Ui.TEXT),false);}if(operatorSnapshot.messages.isEmpty())Ui.text(this,card,"Brak komunikatów.",13,Ui.MUTED,false);}
    private void renderAdminSummary(){LinearLayout card=Ui.card(this,root);Ui.text(this,card,"ADMINISTRACJA",13,Ui.MUTED,true);Ui.text(this,card,"Konta: "+operatorSnapshot.users.size()+" · Taryfy: "+operatorSnapshot.tariffs.size()+" · Regiony: "+operatorSnapshot.regions.size()+" · Strefy: "+operatorSnapshot.fareZones.size(),14,Ui.TEXT,true);for(int i=0;i<Math.min(operatorSnapshot.users.size(),4);i++){UserAccount u=operatorSnapshot.users.get(i);Ui.text(this,card,(u.enabled?"● ":"○ ")+safe(u.name)+" · "+String.join("/",u.roles),12,u.enabled?Ui.GREEN:Ui.MUTED,false);}}
    private int orderColor(OrderStatus s){if(s==OrderStatus.NO_DRIVER||s==OrderStatus.CANCELLED)return Ui.RED;if(s==OrderStatus.OFFERED||s==OrderStatus.SEARCHING_DRIVER)return Ui.ORANGE;if(s.isActive())return Ui.BLUE;return Ui.MUTED;}

    private void newOrderDialog(){if(operatorSnapshot==null)return;
        ScrollView scroll=new ScrollView(this);LinearLayout box=dialogColumn();scroll.addView(box);EditText pickup=plainField("Adres podstawienia");EditText destination=plainField("Adres docelowy");box.addView(pickup);box.addView(destination);
        Spinner regions=spinner(labelsRegions());Spinner tariffs=spinner(labelsTariffs());Spinner mode=spinner(new String[]{"Automat / kolejka","Giełda"});box.addView(regions);box.addView(tariffs);box.addView(mode);
        EditText passengers=numberField("Liczba pasażerów");passengers.setText("1");CheckBox luggage=new CheckBox(this),pet=new CheckBox(this),english=new CheckBox(this),mine=new CheckBox(this);luggage.setText("Bagaż");pet.setText("Zwierzę");english.setText("Wymagany angielski");mine.setText("Oznacz jako ryzykowne / mina");for(CheckBox c:new CheckBox[]{luggage,pet,english,mine}){c.setTextColor(Ui.TEXT);box.addView(c);}box.addView(passengers);
        new AlertDialog.Builder(this).setTitle("Nowe zlecenie").setView(scroll).setNegativeButton("Wróć",null).setPositiveButton("UTWÓRZ",(d,w)->{String region=regions.getSelectedItemPosition()<=0?"":operatorSnapshot.regions.get(regions.getSelectedItemPosition()-1).id;String tariff=tariffs.getSelectedItemPosition()<=0?"":operatorSnapshot.tariffs.get(tariffs.getSelectedItemPosition()-1).id;int pc=1;try{pc=Integer.parseInt(passengers.getText().toString());}catch(Exception ignored){}final int count=Math.max(1,pc);String dm=mode.getSelectedItemPosition()==1?"exchange":"queue";operatorAction(cb->operatorBackend.createOrderAdvanced(pickup.getText().toString(),destination.getText().toString(),region,tariff,dm,count,luggage.isChecked(),pet.isChecked(),english.isChecked(),mine.isChecked(),0,cb));}).show();}
    private String[] labelsRegions(){ArrayList<String>x=new ArrayList<>();x.add("Region: automatycznie / brak");for(Region r:operatorSnapshot.regions)x.add((r.shortName.isEmpty()?r.id:r.shortName)+" · "+r.name);return x.toArray(new String[0]);}
    private String[] labelsTariffs(){ArrayList<String>x=new ArrayList<>();x.add("Taryfa: domyślna");for(Tariff t:operatorSnapshot.tariffs)x.add(t.shortName+" · "+t.name);return x.toArray(new String[0]);}
    private Spinner spinner(String[] values){Spinner s=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values);s.setAdapter(a);return s;}
    private LinearLayout dialogColumn(){LinearLayout box=Ui.column(this);box.setPadding(Ui.dp(this,18),Ui.dp(this,8),Ui.dp(this,18),0);return box;}
    private void assignOrderDialog(Order order){ArrayList<OperatorDriver> drivers=new ArrayList<>();for(OperatorDriver d:operatorSnapshot.drivers)if(d.enabled&&d.onShift)drivers.add(d);if(drivers.isEmpty()){showMessage("Brak kierowców na zmianie.",false);return;}String[] labels=new String[drivers.size()];for(int i=0;i<labels.length;i++){OperatorDriver d=drivers.get(i);labels[i]=d.taxiId+" · "+d.status+(d.queuePosition>0?" · "+d.queueRegionId+" "+d.queuePosition+"/"+d.queueSize:"");}new AlertDialog.Builder(this).setTitle("Przypisz "+order.id).setItems(labels,(dialog,which)->operatorAction(cb->operatorBackend.assignOrder(order.id,drivers.get(which).id,cb))).show();}
    private void forceOrderDialog(Order order){ArrayList<OperatorDriver> drivers=new ArrayList<>();for(OperatorDriver d:operatorSnapshot.drivers)if(d.enabled&&d.onShift)drivers.add(d);if(drivers.isEmpty()){showMessage("Brak kierowców na zmianie.",false);return;}String[] labels=new String[drivers.size()];for(int i=0;i<labels.length;i++){OperatorDriver d=drivers.get(i);labels[i]=d.taxiId+" · "+d.status+" · priorytet "+d.priorityPoints;}new AlertDialog.Builder(this).setTitle("NAKAZ · "+order.id).setItems(labels,(dialog,which)->confirm("Wysłać zlecenie z nakazu do "+drivers.get(which).taxiId+"?",()->operatorAction(cb->operatorBackend.forceOrder(order.id,drivers.get(which).id,cb)))).show();}
    private void messageDialog(){
        LinearLayout box=dialogColumn();EditText title=plainField("Tytuł");EditText body=plainField("Treść");body.setSingleLine(false);body.setMinLines(3);
        Spinner target=spinner(new String[]{"Wszyscy","Konkretny kierowca","Region"}); EditText targetId=plainField("ID celu: UUID kierowcy lub R1"); CheckBox ack=new CheckBox(this),voice=new CheckBox(this);ack.setText("Wymagaj potwierdzenia");voice.setText("Czytaj głosowo");voice.setChecked(true);ack.setTextColor(Ui.TEXT);voice.setTextColor(Ui.TEXT);
        box.addView(title);box.addView(body);box.addView(target);box.addView(targetId);box.addView(ack);box.addView(voice);
        new AlertDialog.Builder(this).setTitle("Komunikat do kierowców").setView(box).setNegativeButton("Wróć",null).setPositiveButton("WYŚLIJ",(d,w)->{String tt=target.getSelectedItemPosition()==1?"driver":(target.getSelectedItemPosition()==2?"region":"all");operatorAction(cb->operatorBackend.sendMessage(title.getText().toString(),body.getText().toString(),"info",tt,targetId.getText().toString().trim(),ack.isChecked(),voice.isChecked(),cb));}).show();
    }
    private void adminMenu(){new AlertDialog.Builder(this).setTitle("Administracja").setItems(new String[]{"Nowe konto","Nowa / zmień taryfę","Nowy / zmień region","Nowa / zmień strefę"},(d,w)->{if(w==0)createUserDialog();else if(w==1)tariffDialog();else if(w==2)regionDialog();else zoneDialog();}).show();}
    private void createUserDialog(){ScrollView scroll=new ScrollView(this);LinearLayout box=dialogColumn();scroll.addView(box);EditText email=field("E-mail",false),name=plainField("Nazwa"),password=field("Hasło",true),taxi=plainField("ID taxi, np. TX2"),number=numberField("Numer taxi");CheckBox driver=new CheckBox(this),dispatcher=new CheckBox(this),admin=new CheckBox(this);driver.setText("Kierowca");dispatcher.setText("Dyspozytor");admin.setText("Administrator");driver.setTextColor(Ui.TEXT);dispatcher.setTextColor(Ui.TEXT);admin.setTextColor(Ui.TEXT);box.addView(email);box.addView(name);box.addView(password);box.addView(driver);box.addView(dispatcher);box.addView(admin);box.addView(taxi);box.addView(number);new AlertDialog.Builder(this).setTitle("Nowe konto").setView(scroll).setNegativeButton("Wróć",null).setPositiveButton("UTWÓRZ",(d,w)->{int n=0;try{n=Integer.parseInt(number.getText().toString().trim());}catch(Exception ignored){}final int taxiNumber=n;operatorAction(cb->operatorBackend.createUser(email.getText().toString(),name.getText().toString(),password.getText().toString(),driver.isChecked(),dispatcher.isChecked(),admin.isChecked(),taxi.getText().toString(),taxiNumber,cb));}).show();}
    private void tariffDialog(){LinearLayout box=dialogColumn();EditText id=plainField("ID, np. T3"),name=plainField("Nazwa"),start=numberField("Opłata startowa"),km=numberField("Cena / km");box.addView(id);box.addView(name);box.addView(start);box.addView(km);new AlertDialog.Builder(this).setTitle("Taryfa").setView(box).setNegativeButton("Wróć",null).setPositiveButton("ZAPISZ",(d,w)->operatorAction(cb->operatorBackend.saveTariff(id.getText().toString().trim().toUpperCase(),name.getText().toString(),parseDouble(start),parseDouble(km),cb))).show();}
    private void regionDialog(){LinearLayout box=dialogColumn();EditText id=plainField("ID, np. R2"),name=plainField("Nazwa"),priority=numberField("Priorytet");box.addView(id);box.addView(name);box.addView(priority);new AlertDialog.Builder(this).setTitle("Region").setView(box).setNegativeButton("Wróć",null).setPositiveButton("ZAPISZ",(d,w)->operatorAction(cb->operatorBackend.saveRegion(id.getText().toString().trim().toUpperCase(),name.getText().toString(),(int)parseDouble(priority),cb))).show();}
    private void zoneDialog(){LinearLayout box=dialogColumn();EditText id=plainField("ID, np. S2"),name=plainField("Nazwa"),tariff=plainField("Domyślna taryfa, np. T2");box.addView(id);box.addView(name);box.addView(tariff);new AlertDialog.Builder(this).setTitle("Strefa taryfowa").setView(box).setNegativeButton("Wróć",null).setPositiveButton("ZAPISZ",(d,w)->operatorAction(cb->operatorBackend.saveZone(id.getText().toString().trim().toUpperCase(),name.getText().toString(),tariff.getText().toString().trim().toUpperCase(),cb))).show();}
    private double parseDouble(EditText input){try{return Double.parseDouble(input.getText().toString().replace(',','.').trim());}catch(Exception e){return 0;}}

    private void renderSessionActions(){if(roleCount()>1)Ui.button(this,root,"ZMIEŃ TRYB",Color.rgb(93,112,118),v->showModeChooser());Ui.button(this,root,"WYLOGUJ",Color.rgb(93,112,118),v->confirm("Wylogować?",this::logout));}
    private void logout(){backend.signOut((ok,message)->runOnUiThread(()->{operatorBackend.stop();stopLocationService();loginScreen();}));}

    private void announceDriverSnapshot(DriverSnapshot value){
        if(!ttsReady||value==null||value.driver==null||!value.driver.ttsEnabled)return;
        if(value.offer!=null&&!value.offer.id.equals(lastSpokenOfferId)){lastSpokenOfferId=value.offer.id;speak("Nowe zlecenie. "+value.offer.pickupAddress+(value.offer.destinationAddress.isEmpty()?"":". Cel "+value.offer.destinationAddress));}
        if(value.activeOrder!=null&&value.activeOrder.forced&&!value.activeOrder.id.equals(lastSpokenForcedId)){lastSpokenForcedId=value.activeOrder.id;speak("Zlecenie z nakazu. "+value.activeOrder.pickupAddress);}
        if(!value.exchange.isEmpty()&&!value.exchange.get(0).id.equals(lastSpokenExchangeId)){Order o=value.exchange.get(0);lastSpokenExchangeId=o.id;speak("Giełda. "+o.pickupAddress+(o.destinationAddress.isEmpty()?"":" do "+o.destinationAddress));}
        for(DispatchMessage m:value.messages){if(m.voiceRead&&!spokenMessageIds.contains(m.id)){spokenMessageIds.add(m.id);if("urgent".equals(m.type)||m.requiresAck)speak((m.title.isEmpty()?"Komunikat centrali":m.title)+". "+m.body);}}
    }
    private void speak(String text){if(ttsReady&&tts!=null&&text!=null&&!text.trim().isEmpty())tts.speak(text,TextToSpeech.QUEUE_ADD,null,"wolftaxi-"+System.nanoTime());}

    // ---------------- shared helpers ----------------
    private void chooseStatus(){DriverStatus[] statuses={DriverStatus.AVAILABLE,DriverStatus.BREAK,DriverStatus.OUT_OF_SERVICE};String[] labels={"Wolny","Przerwa","Niedostępny"};new AlertDialog.Builder(this).setTitle("Status kierowcy").setItems(labels,(d,w)->action(cb->backend.setStatus(statuses[w],cb))).show();}
    private void chooseRegion(){if(snapshot.regions.isEmpty()){showMessage("Brak aktywnych regionów.",false);return;}String[] labels=new String[snapshot.regions.size()];for(int i=0;i<labels.length;i++){Region r=snapshot.regions.get(i);labels[i]=r.name+(r.queueEnabled?"":" · kolejka wyłączona");}new AlertDialog.Builder(this).setTitle("Wybierz region").setItems(labels,(d,w)->action(cb->backend.joinQueue(snapshot.regions.get(w).id,cb))).show();}
    private void chooseTariff(){if(snapshot.tariffs.isEmpty()){showMessage("Brak aktywnych taryf.",false);return;}String[] labels=new String[snapshot.tariffs.size()];for(int i=0;i<labels.length;i++){Tariff t=snapshot.tariffs.get(i);labels[i]=t.shortName+" · "+t.name+" · "+money.format(t.pricePerKm)+"/km";}new AlertDialog.Builder(this).setTitle("Taryfa").setItems(labels,(d,w)->action(cb->backend.setTariff(snapshot.tariffs.get(w).id,cb))).show();}
    private OrderStatus nextStatus(OrderStatus current){if(current==OrderStatus.ACCEPTED)return OrderStatus.EN_ROUTE;if(current==OrderStatus.EN_ROUTE)return OrderStatus.ARRIVED;if(current==OrderStatus.ARRIVED)return OrderStatus.IN_PROGRESS;if(current==OrderStatus.IN_PROGRESS)return OrderStatus.COMPLETED;return null;}
    private String nextAction(OrderStatus next){if(next==OrderStatus.EN_ROUTE)return "RUSZAM DO KLIENTA";if(next==OrderStatus.ARRIVED)return "JESTEM NA MIEJSCU";if(next==OrderStatus.IN_PROGRESS)return "KLIENT WSIADŁ · ROZPOCZNIJ KURS";return "ZAKOŃCZ KURS";}
    private int statusColor(DriverStatus status){if(status==DriverStatus.AVAILABLE)return Ui.GREEN;if(status==DriverStatus.IN_QUEUE)return Ui.BLUE;if(status==DriverStatus.OFFER_RECEIVED)return Ui.ORANGE;if(status==DriverStatus.DRIVING_TO_PICKUP||status==DriverStatus.AT_PICKUP||status==DriverStatus.IN_RIDE)return Ui.RED;return Ui.MUTED;}
    private interface BackendAction{void run(ActionCallback callback);}private void action(BackendAction action){if(snapshot!=null&&!snapshot.connected&&!"DEMO".equals(snapshot.backendMode)){showMessage("Brak połączenia z centralą.",false);return;}action.run((ok,msg)->runOnUiThread(()->showMessage(msg,ok)));}
    private void operatorAction(BackendAction action){if(operatorSnapshot!=null&&!operatorSnapshot.connected){showMessage("Brak połączenia z centralą.",false);return;}action.run((ok,msg)->runOnUiThread(()->showMessage(msg,ok)));}
    private void showMessage(String message,boolean ok){if(message==null||message.isEmpty())return;if(toastLine!=null){toastLine.setText(message);toastLine.setTextColor(ok?Ui.GREEN:Ui.ORANGE);}Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    private void confirm(String message,Runnable yes){new AlertDialog.Builder(this).setMessage(message).setNegativeButton("Wróć",null).setPositiveButton("Potwierdź",(d,w)->yes.run()).show();}
    private void startCountdown(String orderId,long expiresAt){stopCountdown();countdownTick=new Runnable(){@Override public void run(){if(countdown==null)return;long ms=Math.max(0,expiresAt-System.currentTimeMillis());countdown.setText(ms>0?String.format(Locale.getDefault(),"%02d s",(ms+999)/1000):"WYGASŁO");if(ms>0)handler.postDelayed(this,250);else{countdownTick=null;backend.expireOrder(orderId,(ok,msg)->{});}}};handler.post(countdownTick);}
    private void stopCountdown(){if(countdownTick!=null)handler.removeCallbacks(countdownTick);countdownTick=null;countdown=null;}
    private void ensureLocationService(boolean ask){if(mode!=AppMode.DRIVER||"DEMO".equals(snapshot==null?"":snapshot.backendMode))return;boolean fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED,coarse=checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;if(!fine&&!coarse){if(ask){if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.POST_NOTIFICATIONS},REQUEST_LOCATION);else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},REQUEST_LOCATION);}return;}Intent service=new Intent(this,DriverLocationService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);}
    private void stopLocationService(){stopService(new Intent(this,DriverLocationService.class));}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==REQUEST_LOCATION)ensureLocationService(false);}
    private void openNavigation(String address){if(safe(address).isEmpty()){showMessage("Brak adresu do nawigacji.",false);return;}Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q="+Uri.encode(address)));try{startActivity(intent);}catch(Exception e){showMessage("Nie znaleziono aplikacji nawigacyjnej.",false);}}
    private void openDialer(String phone){Intent intent=new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+Uri.encode(phone)));try{startActivity(intent);}catch(Exception e){showMessage("Nie można otworzyć telefonu.",false);}}
    private String safe(String value){return value==null?"":value;}
}
