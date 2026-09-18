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
import android.webkit.WebView;
import android.webkit.WebSettings;

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
import pl.wolftaxi.app.domain.PaymentMethod;
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
import pl.wolftaxi.app.domain.operator.OperatorClient;
import pl.wolftaxi.app.domain.operator.OperatorCompany;
import pl.wolftaxi.app.domain.operator.OperatorVoucher;
import pl.wolftaxi.app.domain.operator.OperatorSettlement;
import pl.wolftaxi.app.domain.operator.OperatorAuditEntry;
import pl.wolftaxi.app.service.DriverLocationService;
import pl.wolftaxi.app.service.SmsGatewayService;
import pl.wolftaxi.app.ui.Ui;

public final class MainActivity extends Activity implements BackendListener, OperatorListener {
    private enum AppMode { DRIVER, DISPATCHER, ADMIN, SMS_GATEWAY }
    private enum DriverTab { REGIONS, ORDER, EXCHANGE, CENTRAL, MENU }
    private enum OperatorTab { DISPATCH, ORDERS, DRIVERS, MAP, MESSAGES, CRM, SETTLEMENTS, HISTORY, ADMIN }
    private static final int REQUEST_LOCATION = 140;
    private static final int REQUEST_SMS = 141;
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
    private DriverTab driverTab = DriverTab.REGIONS;
    private String regionCodeBuffer = "";
    private OperatorTab operatorTab = OperatorTab.DISPATCH;
    private String operatorDriverFilter = "";

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
        else if (mode == AppMode.SMS_GATEWAY) renderSmsGatewayDashboard();
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
        Ui.text(this, content, "Kierowca · Dyspozytor · Administrator · Bramka SMS", 14, Ui.MUTED, false);
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
        resumeSmsGatewayIfEnabled();
        int count = roleCount();
        if (count > 1) showModeChooser();
        else if (backend.hasRole("driver")) switchMode(AppMode.DRIVER);
        else if (backend.hasRole("dispatcher")) switchMode(AppMode.DISPATCHER);
        else if (backend.hasRole("admin")) switchMode(AppMode.ADMIN);
        else if (backend.hasRole("sms_gateway")) switchMode(AppMode.SMS_GATEWAY);
        else { showMessage("Konto nie ma aktywnej roli.", false); loginScreen(); }
    }

    private int roleCount(){int n=0;if(backend.hasRole("driver"))n++;if(backend.hasRole("dispatcher"))n++;if(backend.hasRole("admin"))n++;if(backend.hasRole("sms_gateway"))n++;return n;}

    private void showModeChooser() {
        backend.stop(); operatorBackend.stop(); stopCountdown();
        ScrollView scroll=baseScroll(); LinearLayout content=Ui.column(this); content.setPadding(Ui.dp(this,24),Ui.dp(this,42),Ui.dp(this,24),Ui.dp(this,30)); scroll.addView(content); setContentView(scroll); root=null;
        Ui.text(this,content,"WOLFTAXI",15,Ui.GREEN,true); Ui.text(this,content,"Wybierz tryb",31,Ui.TEXT,true);
        Ui.text(this,content,safe(backend.currentDisplayName()),14,Ui.MUTED,false);
        if(backend.hasRole("driver")) Ui.button(this,content,"KIEROWCA",Ui.GREEN,v->switchMode(AppMode.DRIVER));
        if(backend.hasRole("dispatcher")) Ui.button(this,content,"DYSPOZYTOR",Ui.BLUE,v->switchMode(AppMode.DISPATCHER));
        if(backend.hasRole("admin")) Ui.button(this,content,"ADMINISTRATOR",Ui.ORANGE,v->switchMode(AppMode.ADMIN));
        if(backend.hasRole("sms_gateway")) Ui.button(this,content,"BRAMKA SMS",Ui.MAGENTA,v->switchMode(AppMode.SMS_GATEWAY));
        Ui.button(this,content,"WYLOGUJ",Color.rgb(93,112,118),v->logout());
    }

    private void switchMode(AppMode next) {
        AppMode previous=mode;
        mode=next; stopCountdown(); backend.stop(); operatorBackend.stop();
        if(next!=AppMode.DRIVER) stopLocationService();
        // Bramka SMS jest usługą urządzenia, nie ekranem. Zmiana trybu nie może jej zatrzymywać.
        dashboardShell();
        if(next==AppMode.DRIVER){ snapshot=null; renderDriverDashboard(); backend.start(); }
        else if(next==AppMode.DISPATCHER || next==AppMode.ADMIN){ operatorTab=OperatorTab.DISPATCH; operatorSnapshot=null; renderOperatorDashboard(); operatorBackend.start(next==AppMode.ADMIN); }
        else { renderSmsGatewayDashboard(); ensureSmsGateway(true); }
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
        if(root==null||mode!=AppMode.DRIVER)return;
        stopCountdown();
        root.removeAllViews();
        toastLine=null;
        if(snapshot==null){header("Terminal kierowcy","Łączenie…",Ui.GREEN);return;}

        // Nowa oferta nadal otwiera zakładkę ZLEC., ale aktywny kurs nie blokuje
        // ręcznego przejścia do REJONY/KODY. Kierowca musi móc używać kodów
        // i klawiatury terminala także podczas trwającego zlecenia.
        if(snapshot.offer!=null) driverTab=DriverTab.ORDER;
        else if(pendingQuestion()!=null && snapshot.activeOrder==null && snapshot.offer==null) driverTab=DriverTab.CENTRAL;

        renderDriverHeader();
        renderDriverTabs();
        if(!snapshot.connected)renderConnectionWarning();
        renderDriverStatusStrip();

        if(driverTab==DriverTab.REGIONS) renderDriverRegionsTab();
        else if(driverTab==DriverTab.ORDER) renderDriverOrderTab();
        else if(driverTab==DriverTab.EXCHANGE) renderDriverExchangeTab();
        else if(driverTab==DriverTab.CENTRAL) renderDriverCentralTab();
        else renderDriverMenuTab();

        toastLine=Ui.text(this,root,"",12,Ui.MUTED,false);
    }

    private void renderDriverHeader(){
        LinearLayout h=Ui.row(this);
        root.addView(h,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout l=Ui.column(this);
        h.addView(l,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        Ui.text(this,l,"WOLFTAXI",13,Ui.GREEN,true);
        Ui.text(this,l,"TERMINAL KIEROWCY",18,Ui.TEXT,true);
        String taxi=snapshot.driver.number>0?"TAXI "+snapshot.driver.number:"KIEROWCA";
        TextView b=Ui.text(this,h,taxi+" · "+(snapshot.connected?"ONLINE":"OFFLINE"),11,snapshot.connected?Ui.GREEN:Ui.RED,true);
        b.setGravity(Gravity.END);
    }

    private void header(String title,String subtitle,int color){
        Ui.text(this,root,"WOLFTAXI",15,Ui.GREEN,true);
        Ui.text(this,root,title,25,Ui.TEXT,true);
        Ui.text(this,root,subtitle,13,color,false);
    }

    private void renderDriverTabs(){
        LinearLayout row=Ui.row(this);
        root.addView(row,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        driverTab(row,"REJONY",DriverTab.REGIONS);
        driverTab(row,"ZLEC.",DriverTab.ORDER);
        driverTab(row,"GIEŁDA",DriverTab.EXCHANGE);
        driverTab(row,"CENTR.",DriverTab.CENTRAL);
        driverTab(row,"MENU",DriverTab.MENU);
    }

    private void driverTab(LinearLayout row,String label,DriverTab target){
        Ui.tabButton(this,row,label,driverTab==target,v->{driverTab=target;renderDriverDashboard();});
    }

    private void renderConnectionWarning(){
        LinearLayout card=Ui.card(this,root);
        Ui.text(this,card,"BRAK POŁĄCZENIA Z CENTRALĄ",13,Ui.RED,true);
        Ui.text(this,card,"Bieżące informacje mogą być nieaktualne.",11,Ui.TEXT,false);
    }

    private void renderDriverStatusStrip(){
        LinearLayout row=Ui.row(this);
        root.addView(row,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout s=compactCard(row);
        Ui.text(this,s,"STATUS",9,Ui.MUTED,true);
        Ui.text(this,s,snapshot.driver.status.label.toUpperCase(Locale.ROOT),15,statusColor(snapshot.driver.status),true);
        LinearLayout r=compactCard(row);
        Ui.text(this,r,"REJON",9,Ui.MUTED,true);
        String region=snapshot.region==null?"—":(snapshot.region.shortName.isEmpty()?snapshot.region.name:snapshot.region.shortName);
        Ui.text(this,r,region,16,Ui.TEXT,true);
        String regionMeta=snapshot.queuePosition>0?snapshot.queuePosition+"/"+snapshot.queueSize:"—";
        if(!safe(snapshot.driver.targetRegionId).isEmpty()) regionMeta="→ "+regionDisplay(snapshot.driver.targetRegionId);
        Ui.text(this,r,regionMeta,10,!safe(snapshot.driver.targetRegionId).isEmpty()?Ui.ORANGE:(snapshot.queuePosition>0?Ui.BLUE:Ui.MUTED),false);
        LinearLayout t=compactCard(row);
        Ui.text(this,t,"TARYFA",9,Ui.MUTED,true);
        Ui.text(this,t,snapshot.tariff==null?"—":snapshot.tariff.shortName,16,Ui.TEXT,true);
        Ui.text(this,t,snapshot.fareZone==null?"—":snapshot.fareZone.id,10,Ui.MUTED,false);
    }

    private LinearLayout compactCard(LinearLayout row){
        LinearLayout card=Ui.column(this);
        card.setPadding(Ui.dp(this,7),Ui.dp(this,6),Ui.dp(this,7),Ui.dp(this,6));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setColor(Ui.CARD); bg.setCornerRadius(Ui.dp(this,1)); bg.setStroke(Ui.dp(this,1),Ui.LINE);
        card.setBackground(bg);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);
        p.setMargins(Ui.dp(this,2),0,Ui.dp(this,2),Ui.dp(this,4)); row.addView(card,p); return card;
    }

    private void renderDriverRegionsTab(){
        if(!snapshot.driver.onShift){
            LinearLayout card=Ui.card(this,root);
            Ui.header(this,card,"TERMINAL NIEAKTYWNY");
            Ui.text(this,card,"Rozpocznij zmianę, aby zgłaszać rejony i statusy.",12,Ui.MUTED,false);
            Ui.button(this,card,"ROZPOCZNIJ ZMIANĘ",Ui.GREEN,v->action(cb->backend.startShift((ok,msg)->{cb.complete(ok,msg);if(ok)runOnUiThread(()->ensureLocationService(true));})));
            renderRegionLegend();
            return;
        }
        renderRegionStats();
        renderTerminalControlPanel();
        renderRegionLegend();
    }

    private void renderTerminalControlPanel(){
        LinearLayout card=Ui.card(this,root);
        Ui.header(this,card,"KLAWIATURA / REJONY / STATUS");
        TextView code=Ui.text(this,card,"KOD REJONU: "+(regionCodeBuffer.isEmpty()?"_":regionCodeBuffer),18,Ui.ORANGE,true);
        LinearLayout body=Ui.row(this);
        card.addView(body,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout keypad=Ui.column(this);
        body.addView(keypad,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1.0f));
        addKeyRow(keypad,new String[]{"1","2","3"},code);
        addKeyRow(keypad,new String[]{"4","5","6"},code);
        addKeyRow(keypad,new String[]{"7","8","9"},code);
        LinearLayout last=Ui.row(this); keypad.addView(last);
        Ui.terminalButton(this,last,"C",Color.rgb(90,90,90),false,v->{regionCodeBuffer="";code.setText("KOD REJONU: _");});
        Ui.terminalButton(this,last,"0",Ui.CARD_ALT,false,v->appendRegionDigit("0",code));
        Ui.terminalButton(this,last,"OK",Ui.GREEN,true,v->submitRegionCode());

        LinearLayout funcs=Ui.column(this);
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1.25f);
        fp.setMargins(Ui.dp(this,4),0,0,0); body.addView(funcs,fp);
        addFunctionRow(funcs,"KURSEM",Ui.MAGENTA,v->terminalCourse(),"DOJAZD",Ui.ORANGE,v->terminalDriveToPickup());
        addFunctionRow(funcs,"WOLNY",Ui.GREEN,v->terminalStatus(DriverStatus.AVAILABLE),"PRZERWA",Ui.BLUE,v->terminalStatus(DriverStatus.BREAK));
        addFunctionRow(funcs,"ZAJĘTY",Ui.RED,v->terminalStatus(DriverStatus.BUSY),"NA MIEJSCU",Ui.ORANGE,v->terminalArrived());
        addFunctionRow(funcs,"TARYFA",Ui.BLUE,v->chooseTariff(),"SOS",Ui.RED,v->terminalSos());
    }

    private void addKeyRow(LinearLayout keypad,String[] keys,TextView code){
        LinearLayout row=Ui.row(this); keypad.addView(row);
        for(String key:keys) Ui.terminalButton(this,row,key,Ui.CARD_ALT,false,v->appendRegionDigit(key,code));
    }

    private void addFunctionRow(LinearLayout parent,String left,int leftColor,android.view.View.OnClickListener leftClick,String right,int rightColor,android.view.View.OnClickListener rightClick){
        LinearLayout row=Ui.row(this); parent.addView(row);
        Ui.terminalButton(this,row,left,leftColor,leftColor!=Ui.CARD_ALT,leftClick);
        Ui.terminalButton(this,row,right,rightColor,rightColor!=Ui.CARD_ALT,rightClick);
    }

    private void appendRegionDigit(String digit,TextView code){
        if(regionCodeBuffer.length()>=4)return;
        regionCodeBuffer+=digit;
        code.setText("KOD REJONU: "+regionCodeBuffer);
    }

    private void submitRegionCode(){
        if(regionCodeBuffer.isEmpty()){showMessage("Wpisz kod rejonu.",false);return;}
        Region match=null;
        for(Region r:snapshot.regions){if(regionNumericCode(r).equals(regionCodeBuffer)){match=r;break;}}
        if(match==null){showMessage("Nieznany kod rejonu: "+regionCodeBuffer,false);regionCodeBuffer="";renderDriverDashboard();return;}
        final Region selected=match;
        final String currentLabel=selected.shortName.isEmpty()?selected.name:selected.shortName;

        // RT3000: OK zawsze ustawia BIEŻĄCY rejon.
        // Nie zmienia celu KURSEM/DOJAZD i nie jest blokowane przez aktywne zlecenie.
        // Backend sam dołącza do kolejki tylko wtedy, gdy bieżący status na to pozwala.
        backend.setCurrentRegion(selected.id,(ok,msg)->runOnUiThread(()->{
            if(ok){regionCodeBuffer="";showMessage("REJON: "+currentLabel,true);renderDriverDashboard();}
            else showMessage(msg,false);
        }));
    }

    private String regionNumericCode(Region r){
        if(!safe(r.numericCode).isEmpty()) return r.numericCode;
        String source=!safe(r.shortName).isEmpty()?r.shortName:r.id;
        String digits=source.replaceAll("[^0-9]","");
        return digits;
    }

    private void renderRegionLegend(){
        LinearLayout card=Ui.card(this,root);
        Ui.header(this,card,"LEGENDA KODÓW NUMERYCZNYCH");
        if(snapshot.regions.isEmpty()) Ui.text(this,card,"Brak skonfigurowanych rejonów.",11,Ui.MUTED,false);
        for(Region r:snapshot.regions){
            String code=regionNumericCode(r); if(code.isEmpty())continue;
            RegionStat stat=findRegionStat(r.id);
            String q=stat==null?"":(" · kolejka "+stat.queued);
            Ui.text(this,card,code+" = "+(r.shortName.isEmpty()?r.id:r.shortName)+" · "+r.name+q,11,Ui.TEXT,false);
        }
        Ui.text(this,card,"OK = zgłoś rejon / wejdź do kolejki   C = kasuj kod",10,Ui.GREEN,true);
        Ui.text(this,card,"KOD + KURSEM = jadę kursem do rejonu   KOD + DOJAZD = jadę do rejonu",10,Ui.ORANGE,true);
        Ui.text(this,card,"KURSEM = z pasażerem   DOJAZD = do klienta   WOLNY = gotowy",10,Ui.MUTED,false);
        Ui.text(this,card,"PRZERWA = przerwa   ZAJĘTY = niedostępny",10,Ui.MUTED,false);
    }

    private RegionStat findRegionStat(String regionId){for(RegionStat r:snapshot.regionStats)if(r.id.equals(regionId))return r;return null;}

    private void renderDriverOrderTab(){
        renderOrderArea();
        if(snapshot.driver.onShift && snapshot.offer==null && snapshot.activeOrder==null){
            LinearLayout card=Ui.card(this,root);Ui.text(this,card,"SZYBKIE STATUSY",11,Ui.MUTED,true);
            LinearLayout row=Ui.row(this);card.addView(row);
            Ui.rowButton(this,row,"WOLNY",Ui.GREEN,v->terminalStatus(DriverStatus.AVAILABLE));
            Ui.rowButton(this,row,"DOJAZD",Ui.ORANGE,v->terminalDriveToPickup());
            Ui.rowButton(this,row,"KURSEM",Ui.MAGENTA,v->terminalCourse());
        }
    }

    private void renderOrderArea(){
        if(snapshot.offer!=null){renderOffer(snapshot.offer);return;}
        if(snapshot.activeOrder!=null){renderActiveOrder(snapshot.activeOrder);return;}
        LinearLayout card=Ui.card(this,root);
        Ui.header(this,card,"ZLECENIE");
        Ui.text(this,card,"BRAK AKTYWNEGO ZLECENIA",16,Ui.TEXT,true);
        Ui.text(this,card,snapshot.driver.status==DriverStatus.IN_QUEUE?"Oczekujesz w kolejce rejonu.":"Brak zlecenia z centrali.",11,Ui.MUTED,false);
        if(("DEMO".equals(snapshot.backendMode)||(BuildConfig.DEBUG&&"ORACLE".equals(snapshot.backendMode)))&&snapshot.driver.onShift)Ui.button(this,card,"TEST · WYGENERUJ ZLECENIE",Ui.ORANGE,v->action(backend::simulateOffer));
    }

    private void renderOffer(Order order){
        LinearLayout card=Ui.card(this,root);
        Ui.header(this,card,"NOWE ZLECENIE / OFERTA");
        countdown=Ui.text(this,card,"",28,Ui.ORANGE,true);
        Ui.text(this,card,safe(order.pickupAddress),20,Ui.TEXT,true);
        if(!safe(order.destinationAddress).isEmpty())Ui.text(this,card,"→ "+order.destinationAddress,15,Ui.TEXT,false);
        String meta=order.passengerCount+" os. · "+order.paymentMethod.label;
        if(order.cardRequired)meta+=" · KARTA"; if(order.luggage)meta+=" · BAGAŻ"; if(order.pet)meta+=" · ZWIERZĘ"; if(order.englishRequired)meta+=" · EN";
        Ui.text(this,card,meta,11,Ui.MUTED,false);
        if(order.mineWarning)Ui.text(this,card,"⚠ UWAGA: ZLECENIE OZNACZONE JAKO RYZYKOWNE",11,Ui.MAGENTA,true);
        if(order.cashless)Ui.text(this,card,"BEZGOTÓWKOWE"+(!safe(order.voucherCode).isEmpty()?" · VOUCHER "+order.voucherCode:""),11,Ui.BLUE,true);
        if(!safe(order.notes).isEmpty())Ui.text(this,card,order.notes,11,Ui.MUTED,false);
        if(order.estimatedPrice>0)Ui.text(this,card,"Szacunkowo: "+money.format(order.estimatedPrice),12,Ui.TEXT,true);
        LinearLayout row=Ui.row(this);card.addView(row);
        Ui.rowButton(this,row,"NIE / ODRZUĆ",Ui.RED,v->confirm("Odrzucić zlecenie?",()->action(cb->backend.rejectOrder(order.id,cb))));
        Ui.rowButton(this,row,"TAK / PRZYJMIJ",Ui.GREEN,v->action(cb->backend.acceptOrder(order.id,cb)));
        startCountdown(order.id,order.offerExpiresAt);
    }

    private void renderActiveOrder(Order order){
        LinearLayout card=Ui.card(this,root);
        Ui.header(this,card,(order.forced?"ZLECENIE Z NAKAZU · ":"ZLECENIE · ")+safe(order.id));
        if(order.forced)Ui.text(this,card,"NAKAZ CENTRALI",14,Ui.MAGENTA,true);
        Ui.text(this,card,order.status.label.toUpperCase(Locale.ROOT),20,statusColor(snapshot.driver.status),true);
        Ui.text(this,card,"PODSTAWIENIE",9,Ui.MUTED,true); Ui.text(this,card,safe(order.pickupAddress),18,Ui.TEXT,true);
        if(!safe(order.destinationAddress).isEmpty()){Ui.text(this,card,"CEL",9,Ui.MUTED,true);Ui.text(this,card,order.destinationAddress,16,Ui.TEXT,false);}
        String req=order.passengerCount+" os."+(order.luggage?" · bagaż":"")+(order.pet?" · zwierzę":"")+(order.englishRequired?" · EN":""); Ui.text(this,card,req,11,Ui.MUTED,false);
        if(order.mineWarning)Ui.text(this,card,"⚠ OZNACZENIE RYZYKA",11,Ui.MAGENTA,true);
        if(order.cashless)Ui.text(this,card,"BEZGOTÓWKOWE"+(!safe(order.voucherCode).isEmpty()?" · VOUCHER "+order.voucherCode:"")+(!safe(order.costCenter).isEmpty()?" · MPK "+order.costCenter:""),11,Ui.BLUE,true);
        if(!safe(order.passengerPhone).isEmpty())Ui.text(this,card,"Kontakt: "+order.passengerPhone,11,Ui.MUTED,false);
        if(!safe(order.notes).isEmpty())Ui.text(this,card,"Uwagi: "+order.notes,11,Ui.MUTED,false);
        if(order.meterActive || order.meterAmount>0 || order.status==OrderStatus.IN_PROGRESS){
            LinearLayout meter=Ui.column(this);card.addView(meter);
            Ui.text(this,meter,"TAKSOMETR",9,Ui.MUTED,true);
            Ui.text(this,meter,money.format(order.meterAmount),28,Ui.GREEN,true);
            String meterMeta=String.format(Locale.getDefault(),"%.2f km · postój %d min",order.meterDistanceM/1000.0,(int)Math.floor(order.meterWaitingSeconds/60.0));
            Ui.text(this,meter,meterMeta,11,Ui.MUTED,false);
        }
        LinearLayout utilities=Ui.row(this);card.addView(utilities);String navAddress=order.status==OrderStatus.IN_PROGRESS&&!safe(order.destinationAddress).isEmpty()?order.destinationAddress:order.pickupAddress;
        Ui.rowButton(this,utilities,"NAWIGACJA",Ui.BLUE,v->openNavigation(navAddress));if(!safe(order.passengerPhone).isEmpty())Ui.rowButton(this,utilities,"ZADZWOŃ",Ui.BLUE,v->openDialer(order.passengerPhone));
        if(!safe(order.trackingUrl).isEmpty()){
            LinearLayout tracking=Ui.row(this);card.addView(tracking);
            Ui.rowButton(this,tracking,"MAPA LIVE",Ui.GREEN,v->openTrackingMap(order.trackingUrl));
            Ui.rowButton(this,tracking,"LINK KLIENTA",Ui.ORANGE,v->copyTrackingLink(order.trackingUrl));
        }
        OrderStatus next=nextStatus(order.status);if(next!=null)Ui.button(this,card,nextAction(next),next==OrderStatus.COMPLETED?Ui.RED:Ui.GREEN,v->nextOrderAction(order,next));
    }

    private void nextOrderAction(Order order,OrderStatus next){if(next==OrderStatus.COMPLETED)completeOrderDialog(order);else action(cb->backend.advanceOrder(order.id,next,cb));}

    private void completeOrderDialog(Order order){
        LinearLayout box=dialogColumn();EditText price=numberField("Kwota końcowa");double suggested=order.meterAmount>0?order.meterAmount:(order.estimatedPrice>0?order.estimatedPrice:order.finalPrice);price.setText(String.format(Locale.US,"%.2f",suggested));
        Spinner payment=spinner(new String[]{"Gotówka","Karta","Firma","Inna"});box.addView(price);box.addView(payment);
        new AlertDialog.Builder(this).setTitle("Zakończ kurs").setView(box).setNegativeButton("Wróć",null).setPositiveButton("ZAKOŃCZ",(d,w)->{
            double amount=parseDouble(price);PaymentMethod method=new PaymentMethod[]{PaymentMethod.CASH,PaymentMethod.CARD,PaymentMethod.COMPANY,PaymentMethod.OTHER}[payment.getSelectedItemPosition()];
            action(cb->backend.completeOrder(order.id,amount,method,cb));
        }).show();
    }

    private void renderDriverExchangeTab(){
        if(snapshot.exchange.isEmpty()){
            LinearLayout card=Ui.card(this,root);Ui.header(this,card,"GIEŁDA ZLECEŃ");Ui.text(this,card,"Brak zleceń na giełdzie.",12,Ui.MUTED,false);return;
        }
        renderExchange();
    }

    private void renderExchange(){
        LinearLayout card=Ui.card(this,root);Ui.header(this,card,"GIEŁDA ZLECEŃ · "+snapshot.exchange.size());
        for(int i=0;i<Math.min(snapshot.exchange.size(),8);i++){
            Order o=snapshot.exchange.get(i);LinearLayout block=Ui.column(this);card.addView(block);
            String when=o.scheduledFor>0?new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date(o.scheduledFor)):"TERAZ";
            Ui.text(this,block,when+" · "+safe(o.pickupAddress)+(safe(o.destinationAddress).isEmpty()?"":" → "+o.destinationAddress),12,o.mineWarning?Ui.MAGENTA:Ui.TEXT,true);
            String req=o.passengerCount+" os."+(o.luggage?" · bagaż":"")+(o.pet?" · zwierzę":"")+(o.englishRequired?" · EN":"");Ui.text(this,block,req,10,Ui.MUTED,false);
            Ui.button(this,block,"POBIERZ Z GIEŁDY",Ui.GREEN,v->confirm("Pobrać "+o.id+"?",()->action(cb->backend.claimExchange(o.id,cb))));
        }
    }

    private void renderDriverCentralTab(){
        renderPendingQuestion();
        renderSafetyAlert();
        renderMessages();
    }

    private void renderPendingQuestion(){
        DispatchMessage q=pendingQuestion(); if(q==null)return;
        LinearLayout card=Ui.card(this,root);Ui.header(this,card,"? PYTANIE OD CENTRALI");
        Ui.text(this,card,safe(q.title),11,Ui.ORANGE,true);Ui.text(this,card,safe(q.body),17,Ui.TEXT,true);
        LinearLayout row=Ui.row(this);card.addView(row);
        Ui.rowButton(this,row,"TAK",Ui.GREEN,v->answerQuestion(true));
        Ui.rowButton(this,row,"NIE",Ui.RED,v->answerQuestion(false));
    }

    private DispatchMessage pendingQuestion(){
        if(snapshot==null)return null;
        for(DispatchMessage m:snapshot.messages)if("question".equals(m.type)&&!m.answered)return m;
        return null;
    }

    private void answerQuestion(boolean yes){
        DispatchMessage q=pendingQuestion();
        if(q==null){showMessage("Brak aktywnego pytania z centrali.",false);return;}
        action(cb->backend.answerMessage(q.id,yes,cb));
    }

    private void renderMessages(){
        LinearLayout card=Ui.card(this,root);Ui.header(this,card,"CENTRALA / KOMUNIKATY");
        if(snapshot.messages.isEmpty()){Ui.text(this,card,"Brak wiadomości.",11,Ui.MUTED,false);return;}
        for(int i=0;i<Math.min(snapshot.messages.size(),10);i++){
            DispatchMessage m=snapshot.messages.get(i);int color="urgent".equals(m.type)?Ui.RED:("warning".equals(m.type)?Ui.ORANGE:("question".equals(m.type)?Ui.BLUE:Ui.TEXT));String time=m.createdAt>0?clock.format(new Date(m.createdAt))+" · ":"";
            String state=m.answered?(" · "+("yes".equals(m.answer)?"TAK":"NIE")):(m.acknowledged?" · POTWIERDZONO":"");
            Ui.text(this,card,time+safe(m.title)+state,10,Ui.MUTED,true);Ui.text(this,card,safe(m.body),12,color,false);
            if("question".equals(m.type)&&!m.answered){LinearLayout qrow=Ui.row(this);card.addView(qrow);Ui.rowButton(this,qrow,"TAK",Ui.GREEN,v->action(cb->backend.answerMessage(m.id,true,cb)));Ui.rowButton(this,qrow,"NIE",Ui.RED,v->action(cb->backend.answerMessage(m.id,false,cb)));}
            else if(m.requiresAck&&!m.acknowledged)Ui.button(this,card,"POTWIERDZAM ODCZYT",Ui.GREEN,v->action(cb->backend.acknowledgeMessage(m.id,cb)));
        }
    }

    private void renderDriverMenuTab(){
        LinearLayout info=Ui.card(this,root);Ui.header(this,info,"USTAWIENIA TERMINALA");
        Ui.text(this,info,"Kierowca: "+safe(snapshot.driver.name),12,Ui.TEXT,true);
        Ui.text(this,info,"Tryb: "+snapshot.backendMode+" · GPS "+(snapshot.driver.onShift?"aktywny":"poza zmianą"),10,Ui.MUTED,false);
        LinearLayout row=Ui.row(this);info.addView(row);
        Ui.rowButton(this,row,"REGION LISTA",Ui.BLUE,v->chooseRegion());
        Ui.rowButton(this,row,"TARYFA",Ui.BLUE,v->chooseTariff());
        if(snapshot.queuePosition>0)Ui.button(this,info,"OPUŚĆ KOLEJKĘ",Ui.ORANGE,v->action(backend::leaveQueue));
        LinearLayout daily=Ui.card(this,root);Ui.header(this,daily,"DZISIAJ / ROZLICZENIE");
        Ui.text(this,daily,"Kursy: "+snapshot.todayRides+" · Obrót: "+money.format(snapshot.todayGross),12,Ui.TEXT,true);
        Ui.text(this,daily,"Gotówka "+money.format(snapshot.todayCash)+" · Karta "+money.format(snapshot.todayCard)+" · Bezgot. "+money.format(snapshot.todayCashless),10,Ui.MUTED,false);
        renderHistory();
        if(snapshot.driver.onShift)Ui.button(this,root,"ZAKOŃCZ ZMIANĘ",Color.rgb(130,130,130),v->confirm("Zakończyć zmianę?",()->action(cb->backend.endShift((ok,msg)->{cb.complete(ok,msg);if(ok)runOnUiThread(this::stopLocationService);})))) ;
        else Ui.button(this,root,"ROZPOCZNIJ ZMIANĘ",Ui.GREEN,v->action(cb->backend.startShift((ok,msg)->{cb.complete(ok,msg);if(ok)runOnUiThread(()->ensureLocationService(true));})));
        renderSessionActions();
    }

    private void renderHistory(){if(snapshot.history.isEmpty())return;LinearLayout card=Ui.card(this,root);Ui.text(this,card,"OSTATNIE KURSY",11,Ui.MUTED,true);for(int i=0;i<Math.min(snapshot.history.size(),5);i++){Order o=snapshot.history.get(i);String route=safe(o.pickupAddress)+(safe(o.destinationAddress).isEmpty()?"":" → "+o.destinationAddress);Ui.text(this,card,route,12,Ui.TEXT,i==0);String meta=o.createdAt>0?clock.format(new Date(o.createdAt)):"";if(o.finalPrice>0)meta+=(meta.isEmpty()?"":" · ")+money.format(o.finalPrice);if(!meta.isEmpty())Ui.text(this,card,meta,10,Ui.MUTED,false);}}

    private void renderSafetyAlert(){if(snapshot.safetyAlert==null)return;LinearLayout card=Ui.card(this,root);Ui.header(this,card,"!!! ALARM SOS AKTYWNY !!!");Ui.text(this,card,"Status centrali: "+snapshot.safetyAlert.status.toUpperCase(Locale.ROOT),13,Ui.RED,true);if(!safe(snapshot.safetyAlert.note).isEmpty())Ui.text(this,card,snapshot.safetyAlert.note,11,Ui.TEXT,false);Ui.button(this,card,"ODWOŁAJ SOS",Ui.ORANGE,v->confirm("Odwołać alarm SOS?",()->action(backend::cancelSos)));}

    private void renderRegionStats(){
        LinearLayout card=Ui.card(this,root);Ui.header(this,card,"REJONY / STAN");
        if(snapshot.regionStats.isEmpty()){Ui.text(this,card,"Brak statystyk rejonów.",10,Ui.MUTED,false);return;}
        StringBuilder line=new StringBuilder();
        for(int i=0;i<Math.min(snapshot.regionStats.size(),12);i++){RegionStat r=snapshot.regionStats.get(i);if(line.length()>0)line.append("   ");line.append(r.shortName.isEmpty()?r.id:r.shortName).append(":").append(r.queued);}
        Ui.text(this,card,line.toString(),11,Ui.GREEN,true);
    }

    private void terminalStatus(DriverStatus status){
        if(!snapshot.driver.onShift){showMessage("Najpierw rozpocznij zmianę.",false);return;}
        if(snapshot.activeOrder!=null||snapshot.offer!=null){showMessage("Status sterowany przez aktywne zlecenie.",false);return;}
        action(cb->backend.setStatus(status,cb));
    }

    private Region selectedRegionFromBuffer(){
        if(regionCodeBuffer.isEmpty()) return null;
        for(Region r:snapshot.regions) if(regionNumericCode(r).equals(regionCodeBuffer)) return r;
        return null;
    }

    private void terminalMovingStatus(DriverStatus status){
        if(!snapshot.driver.onShift){showMessage("Najpierw rozpocznij zmianę.",false);return;}
        if(snapshot.offer!=null){showMessage("Najpierw przyjmij lub odrzuć ofertę.",false);return;}
        if(snapshot.activeOrder!=null && regionCodeBuffer.isEmpty()){
            showMessage("Status kursu jest sterowany przez zlecenie. Wpisz kod rejonu, aby ustawić cel.",false);return;
        }
        if(regionCodeBuffer.isEmpty()){
            action(cb->backend.setStatus(status,cb));
            return;
        }
        Region selected=selectedRegionFromBuffer();
        if(selected==null){
            String bad=regionCodeBuffer;
            regionCodeBuffer="";
            showMessage("Nieznany kod rejonu: "+bad,false);
            renderDriverDashboard();
            return;
        }
        String targetId=selected.id;
        String targetLabel=selected.shortName.isEmpty()?selected.name:selected.shortName;
        backend.setStatusForRegion(status,targetId,(ok,msg)->runOnUiThread(()->{
            if(ok){regionCodeBuffer="";showMessage(status.label+" → "+targetLabel,true);}
            else showMessage(msg,false);
        }));
    }

    private String regionDisplay(String regionId){
        for(Region r:snapshot.regions) if(r.id.equals(regionId)) return r.shortName.isEmpty()?r.name:r.shortName;
        return regionId;
    }

    private void terminalCourse(){
        if(snapshot.activeOrder!=null){
            if(!regionCodeBuffer.isEmpty()){terminalMovingStatus(DriverStatus.COURSE);return;}
            if(snapshot.activeOrder.status==OrderStatus.ARRIVED){action(cb->backend.advanceOrder(snapshot.activeOrder.id,OrderStatus.IN_PROGRESS,cb));return;}
            if(snapshot.activeOrder.status==OrderStatus.IN_PROGRESS){showMessage("Już jesteś KURSEM.",true);return;}
            showMessage("Najpierw ustaw DOJAZD i NA MIEJSCU.",false);return;
        }
        terminalMovingStatus(DriverStatus.COURSE);
    }

    private void terminalDriveToPickup(){
        if(snapshot.activeOrder!=null){
            if(!regionCodeBuffer.isEmpty()){terminalMovingStatus(DriverStatus.DRIVING_TO_PICKUP);return;}
            if(snapshot.activeOrder.status==OrderStatus.ACCEPTED){action(cb->backend.advanceOrder(snapshot.activeOrder.id,OrderStatus.EN_ROUTE,cb));return;}
            if(snapshot.activeOrder.status==OrderStatus.EN_ROUTE){showMessage("Status DOJAZD jest już aktywny.",true);return;}
            showMessage("DOJAZD nie pasuje do bieżącego etapu kursu.",false);return;
        }
        terminalMovingStatus(DriverStatus.DRIVING_TO_PICKUP);
    }

    private void terminalArrived(){
        if(snapshot.activeOrder!=null&&snapshot.activeOrder.status==OrderStatus.EN_ROUTE){action(cb->backend.advanceOrder(snapshot.activeOrder.id,OrderStatus.ARRIVED,cb));return;}
        showMessage("Brak zlecenia w statusie DOJAZD.",false);
    }

    private void terminalSos(){
        if(snapshot.safetyAlert==null)confirm("Wysłać ALARM SOS do centrali?",()->action(cb->backend.sendSos("SOS kierowcy",cb)));
        else confirm("Odwołać alarm SOS?",()->action(backend::cancelSos));
    }


    // ---------------- OPERATOR / ADMIN UI ----------------
    private void renderOperatorDashboard(){
        if(root==null||(mode!=AppMode.DISPATCHER&&mode!=AppMode.ADMIN))return;
        root.removeAllViews();toastLine=null;
        if(operatorSnapshot==null){header(mode==AppMode.ADMIN?"Administrator":"Dyspozytornia","Łączenie z Oracle…",Ui.BLUE);return;}
        LinearLayout h=Ui.row(this);root.addView(h);
        LinearLayout left=Ui.column(this);h.addView(left,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        Ui.text(this,left,"WOLFTAXI",15,Ui.GREEN,true);Ui.text(this,left,mode==AppMode.ADMIN?"Administrator":"Dyspozytornia",23,Ui.TEXT,true);
        TextView badge=Ui.text(this,h,(operatorSnapshot.connected?"ONLINE":"OFFLINE")+" · ORACLE",11,operatorSnapshot.connected?Ui.GREEN:Ui.RED,true);badge.setGravity(Gravity.END);
        renderOperatorAlerts();
        renderOperatorTabs();
        switch(operatorTab){
            case DISPATCH: renderOperatorDispatchTab(); break;
            case ORDERS: renderOperatorOrdersTab(); break;
            case DRIVERS: renderOperatorDriversTab(); break;
            case MAP: renderOperatorMapTab(); break;
            case MESSAGES: renderOperatorMessagesTab(); break;
            case CRM: renderOperatorCrmTab(); break;
            case SETTLEMENTS: renderOperatorSettlementsTab(); break;
            case HISTORY: renderOperatorHistoryTab(); break;
            case ADMIN: if(mode==AppMode.ADMIN)renderOperatorAdminTab(); else {operatorTab=OperatorTab.DISPATCH;renderOperatorDispatchTab();} break;
        }
        renderSessionActions();
        toastLine=Ui.text(this,root,"",12,Ui.MUTED,false);
    }

    private void renderOperatorTabs(){
        LinearLayout r1=Ui.row(this);root.addView(r1);
        opTab(r1,"DYSPO",OperatorTab.DISPATCH);opTab(r1,"ZLEC.",OperatorTab.ORDERS);opTab(r1,"KIER.",OperatorTab.DRIVERS);opTab(r1,"MAPA",OperatorTab.MAP);
        LinearLayout r2=Ui.row(this);root.addView(r2);
        opTab(r2,"KOMUN.",OperatorTab.MESSAGES);opTab(r2,"CRM",OperatorTab.CRM);opTab(r2,"ROZL.",OperatorTab.SETTLEMENTS);opTab(r2,"HIST.",OperatorTab.HISTORY);
        if(mode==AppMode.ADMIN){LinearLayout r3=Ui.row(this);root.addView(r3);opTab(r3,"ADMIN",OperatorTab.ADMIN);}
    }
    private void opTab(LinearLayout row,String label,OperatorTab tab){Ui.tabButton(this,row,label,operatorTab==tab,v->{operatorTab=tab;renderOperatorDashboard();});}

    private void renderOperatorDispatchTab(){renderOperatorStats();renderOperatorActions();renderOperatorDriversCompact();renderOperatorOrdersCompact();renderOperatorSettlementSummary();}
    private void renderOperatorDriversCompact(){LinearLayout card=Ui.card(this,root);Ui.header(this,card,"TAXI / REJONY / KOLEJKI");int n=0;for(OperatorDriver d:operatorSnapshot.drivers){if(n++>=12)break;renderDriverLine(card,d,false);}if(n==0)Ui.text(this,card,"Brak kierowców.",12,Ui.MUTED,false);}
    private void renderOperatorOrdersCompact(){LinearLayout card=Ui.card(this,root);Ui.header(this,card,"BIEŻĄCE ZLECENIA");int n=0;for(Order o:operatorSnapshot.orders){if(o.status==OrderStatus.COMPLETED||o.status==OrderStatus.CANCELLED)continue;if(n++>=10)break;renderOrderBlock(card,o,false);}if(n==0)Ui.text(this,card,"Brak bieżących zleceń.",12,Ui.MUTED,false);}

    private void renderOperatorDriversTab(){
        LinearLayout tools=Ui.card(this,root);Ui.header(this,tools,"KIEROWCY / TAXI");
        LinearLayout row=Ui.row(this);tools.addView(row);EditText f=plainField("numer / status / rejon");f.setText(operatorDriverFilter);row.addView(f,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        Button b=new Button(this);b.setText("FILTRUJ");b.setOnClickListener(v->{operatorDriverFilter=f.getText().toString().trim().toLowerCase(Locale.ROOT);renderOperatorDashboard();});row.addView(b);
        LinearLayout card=Ui.card(this,root);String filter=operatorDriverFilter;
        for(OperatorDriver d:operatorSnapshot.drivers){String hay=(d.taxiId+" "+d.number+" "+d.status+" "+d.currentRegionId+" "+d.queueRegionId+" "+d.name).toLowerCase(Locale.ROOT);if(!filter.isEmpty()&&!hay.contains(filter))continue;renderDriverLine(card,d,true);}
    }
    private void renderDriverLine(LinearLayout card,OperatorDriver d,boolean actions){
        Ui.text(this,card,safe(d.taxiId)+" #"+d.number+" · "+safe(d.name),14,Ui.TEXT,true);
        String region=d.queueRegionId.isEmpty()?safe(d.currentRegionId):d.queueRegionId;String meta=(d.online?"ONLINE":"offline")+" · "+d.status+" · "+(region.isEmpty()?"—":region)+(d.queuePosition>0?" "+d.queuePosition+"/"+d.queueSize:"");if(!safe(d.targetRegionId).isEmpty())meta+=" → "+d.targetRegionId;
        Ui.text(this,card,meta,11,d.online?Ui.GREEN:Ui.MUTED,false);Ui.text(this,card,"T:"+safe(d.currentTariffId)+" · PRI:"+d.priorityPoints+" · GPS "+(d.lastLocationAt>0?clock.format(new Date(d.lastLocationAt)):"—"),10,Ui.MUTED,false);
        if(actions){LinearLayout a=Ui.row(this);card.addView(a);Ui.rowButton(this,a,"PRI",Ui.BLUE,v->priorityDialog(d));if(!Double.isNaN(d.lat)&&!Double.isNaN(d.lng))Ui.rowButton(this,a,"MAPA",Ui.GREEN,v->openGeo(d.lat,d.lng,d.taxiId));}
    }
    private void priorityDialog(OperatorDriver d){EditText x=numberField("Priorytet -100…100");x.setText(String.valueOf(d.priorityPoints));new AlertDialog.Builder(this).setTitle("Priorytet "+d.taxiId).setView(x).setNegativeButton("Wróć",null).setPositiveButton("ZAPISZ",(q,w)->operatorAction(cb->operatorBackend.setDriverPriority(d.id,(int)parseDouble(x),cb))).show();}

    private void renderOperatorOrdersTab(){
        Ui.button(this,root,"NOWE ZLECENIE",Ui.GREEN,v->newOrderDialog());LinearLayout card=Ui.card(this,root);Ui.header(this,card,"WSZYSTKIE ZLECENIA");
        if(operatorSnapshot.orders.isEmpty()){Ui.text(this,card,"Brak zleceń.",12,Ui.MUTED,false);return;}for(Order o:operatorSnapshot.orders)renderOrderBlock(card,o,true);
    }
    private void renderOrderBlock(LinearLayout card,Order o,boolean full){
        Ui.text(this,card,o.id+" · "+o.status.label.toUpperCase(Locale.ROOT),12,orderColor(o.status),true);Ui.text(this,card,safe(o.pickupAddress)+(safe(o.destinationAddress).isEmpty()?"":" → "+o.destinationAddress),14,Ui.TEXT,true);
        String p=(safe(o.pickupRegionId).isEmpty()?"—":o.pickupRegionId)+" · "+o.passengerCount+" os."+(o.luggage?" · BAG":"")+(o.pet?" · PET":"")+(o.englishRequired?" · EN":"")+(o.mineWarning?" · MINA":"");Ui.text(this,card,p,10,Ui.MUTED,false);
        if(o.meterAmount>0)Ui.text(this,card,"TAKSOMETR "+money.format(o.meterAmount)+" · "+String.format(Locale.getDefault(),"%.2f km",o.meterDistanceM/1000d),10,Ui.GREEN,false);
        if(!safe(o.passengerPhone).isEmpty())Ui.text(this,card,"SMS: "+safe(o.trackingSmsStatus)+(safe(o.trackingSmsLastError).isEmpty()?"":" · "+o.trackingSmsLastError),10,"failed".equals(o.trackingSmsStatus)?Ui.RED:Ui.MUTED,false);
        if(full){LinearLayout a=Ui.row(this);card.addView(a);Ui.rowButton(this,a,"LINK",Ui.GREEN,v->trackingLink(o));if(o.status!=OrderStatus.COMPLETED&&o.status!=OrderStatus.CANCELLED){Ui.rowButton(this,a,"PRZYP",Ui.BLUE,v->assignOrderDialog(o));Ui.rowButton(this,a,"NAKAZ",Ui.MAGENTA,v->forceOrderDialog(o));}if(o.status!=OrderStatus.COMPLETED&&o.status!=OrderStatus.CANCELLED)Ui.button(this,card,"ANULUJ "+o.id,Ui.RED,v->confirm("Anulować "+o.id+"?",()->operatorAction(cb->operatorBackend.cancelOrder(o.id,cb))));}
    }
    private void trackingLink(Order o){if(!safe(o.trackingUrl).isEmpty()){copyTrackingLink(o.trackingUrl);return;}operatorBackend.trackingLink(o.id,(ok,msg)->runOnUiThread(()->{if(ok)copyTrackingLink(msg);else showMessage(msg,false);}));}

    private void renderOperatorMessagesTab(){Ui.button(this,root,"NOWY KOMUNIKAT",Ui.BLUE,v->messageDialog());renderOperatorMessages();}

    private void renderOperatorCrmTab(){
        LinearLayout actions=Ui.row(this);root.addView(actions);Ui.rowButton(this,actions,"KLIENT",Ui.GREEN,v->clientDialog());Ui.rowButton(this,actions,"FIRMA",Ui.BLUE,v->companyDialog());Ui.rowButton(this,actions,"VOUCHER",Ui.ORANGE,v->voucherDialog());
        LinearLayout c=Ui.card(this,root);Ui.header(this,c,"KLIENCI / CRM");for(OperatorClient x:operatorSnapshot.clients){Ui.text(this,c,(x.blocked?"⛔ ":"")+safe(x.name)+" · "+safe(x.phone),12,x.blocked?Ui.RED:Ui.TEXT,true);Ui.text(this,c,safe(x.email)+" · "+x.ridesCount+" kursów · "+money.format(x.totalSpend),10,Ui.MUTED,false);}if(operatorSnapshot.clients.isEmpty())Ui.text(this,c,"Brak klientów.",11,Ui.MUTED,false);
        LinearLayout co=Ui.card(this,root);Ui.header(this,co,"FIRMY");for(OperatorCompany x:operatorSnapshot.companies){Ui.text(this,co,(x.active?"● ":"○ ")+x.name+" · NIP "+safe(x.nip),12,x.active?Ui.TEXT:Ui.MUTED,true);Ui.text(this,co,safe(x.billingEmail)+" · limit "+money.format(x.monthlyLimit),10,Ui.MUTED,false);}if(operatorSnapshot.companies.isEmpty())Ui.text(this,co,"Brak firm.",11,Ui.MUTED,false);
        LinearLayout v=Ui.card(this,root);Ui.header(this,v,"VOUCHERY");for(OperatorVoucher x:operatorSnapshot.vouchers){Ui.text(this,v,x.code+" · "+(x.active?"AKTYWNY":"OFF"),12,x.active?Ui.GREEN:Ui.MUTED,true);Ui.text(this,v,money.format(x.amount)+" · pozostało "+money.format(x.remainingAmount),10,Ui.MUTED,false);}if(operatorSnapshot.vouchers.isEmpty())Ui.text(this,v,"Brak voucherów.",11,Ui.MUTED,false);
    }
    private void clientDialog(){LinearLayout box=dialogColumn();EditText n=plainField("Nazwa"),p=plainField("Telefon"),e=plainField("E-mail"),notes=plainField("Uwagi");p.setInputType(InputType.TYPE_CLASS_PHONE);box.addView(n);box.addView(p);box.addView(e);box.addView(notes);new AlertDialog.Builder(this).setTitle("Nowy klient").setView(box).setNegativeButton("Wróć",null).setPositiveButton("ZAPISZ",(d,w)->operatorAction(cb->operatorBackend.createClient(n.getText().toString(),p.getText().toString(),e.getText().toString(),notes.getText().toString(),cb))).show();}
    private void companyDialog(){LinearLayout box=dialogColumn();EditText n=plainField("Nazwa"),nip=plainField("NIP"),e=plainField("E-mail rozliczeń"),limit=numberField("Limit miesięczny");box.addView(n);box.addView(nip);box.addView(e);box.addView(limit);new AlertDialog.Builder(this).setTitle("Nowa firma").setView(box).setNegativeButton("Wróć",null).setPositiveButton("ZAPISZ",(d,w)->operatorAction(cb->operatorBackend.createCompany(n.getText().toString(),nip.getText().toString(),e.getText().toString(),parseDouble(limit),cb))).show();}
    private void voucherDialog(){LinearLayout box=dialogColumn();EditText code=plainField("Kod"),amount=numberField("Kwota");Spinner company=spinner(labelsCompanies()),client=spinner(labelsClients());box.addView(code);box.addView(amount);box.addView(company);box.addView(client);new AlertDialog.Builder(this).setTitle("Voucher").setView(box).setNegativeButton("Wróć",null).setPositiveButton("UTWÓRZ",(d,w)->{String cid=company.getSelectedItemPosition()<=0?"":operatorSnapshot.companies.get(company.getSelectedItemPosition()-1).id;String cl=client.getSelectedItemPosition()<=0?"":operatorSnapshot.clients.get(client.getSelectedItemPosition()-1).id;operatorAction(cb->operatorBackend.createVoucher(code.getText().toString().trim().toUpperCase(Locale.ROOT),parseDouble(amount),cid,cl,cb));}).show();}
    private String[] labelsCompanies(){ArrayList<String>x=new ArrayList<>();x.add("Firma: —");for(OperatorCompany c:operatorSnapshot.companies)x.add(c.name);return x.toArray(new String[0]);}
    private String[] labelsClients(){ArrayList<String>x=new ArrayList<>();x.add("Klient: —");for(OperatorClient c:operatorSnapshot.clients)x.add((safe(c.name).isEmpty()?c.phone:c.name)+(safe(c.phone).isEmpty()?"":" · "+c.phone));return x.toArray(new String[0]);}

    private void renderOperatorSettlementsTab(){renderOperatorSettlementSummary();LinearLayout card=Ui.card(this,root);Ui.header(this,card,"ROZLICZENIA KURSÓW");for(OperatorSettlement x:operatorSnapshot.settlements){Ui.text(this,card,x.orderId+" · "+safe(x.taxiId)+" · "+money.format(x.grossAmount),12,Ui.TEXT,true);Ui.text(this,card,safe(x.clientName).isEmpty()?safe(x.companyName):x.clientName+" · "+x.paymentMethod+" · "+x.status,10,Ui.MUTED,false);if(!"settled".equals(x.status))Ui.button(this,card,"ROZLICZ "+x.orderId,Ui.GREEN,v->confirm("Zamknąć rozliczenie "+x.orderId+"?",()->operatorAction(cb->operatorBackend.closeSettlement(x.id,cb))));}if(operatorSnapshot.settlements.isEmpty())Ui.text(this,card,"Brak rozliczeń.",11,Ui.MUTED,false);}
    private void renderOperatorHistoryTab(){LinearLayout card=Ui.card(this,root);Ui.header(this,card,"AUDYT / HISTORIA SYSTEMU");for(OperatorAuditEntry x:operatorSnapshot.audit){Ui.text(this,card,(x.createdAt>0?new SimpleDateFormat("dd.MM HH:mm",Locale.getDefault()).format(new Date(x.createdAt))+" · ":"")+x.action,11,Ui.TEXT,true);Ui.text(this,card,x.entityType+" "+x.entityId+(safe(x.details).isEmpty()?"":" · "+x.details),9,Ui.MUTED,false);}if(operatorSnapshot.audit.isEmpty())Ui.text(this,card,"Brak wpisów audytu.",11,Ui.MUTED,false);}

    private void renderOperatorAdminTab(){
        LinearLayout actions=Ui.row(this);root.addView(actions);Ui.rowButton(this,actions,"KONTO",Ui.GREEN,v->createUserDialog());Ui.rowButton(this,actions,"TARYFA",Ui.BLUE,v->tariffDialog());Ui.rowButton(this,actions,"REGION",Ui.ORANGE,v->regionDialog());Ui.rowButton(this,actions,"STREFA",Ui.MAGENTA,v->zoneDialog());
        LinearLayout users=Ui.card(this,root);Ui.header(this,users,"KONTA");for(UserAccount u:operatorSnapshot.users){Ui.text(this,users,(u.enabled?"● ":"○ ")+safe(u.name)+" · "+u.email,12,u.enabled?Ui.TEXT:Ui.MUTED,true);Ui.text(this,users,String.join("/",u.roles),10,Ui.MUTED,false);Ui.button(this,users,u.enabled?"BLOKUJ":"ODBLOKUJ",u.enabled?Ui.RED:Ui.GREEN,v->operatorAction(cb->operatorBackend.setUserEnabled(u.id,!u.enabled,cb)));}
        LinearLayout cfg=Ui.card(this,root);Ui.header(this,cfg,"KONFIGURACJA");Ui.text(this,cfg,"Taryfy: "+operatorSnapshot.tariffs.size()+" · Regiony: "+operatorSnapshot.regions.size()+" · Strefy: "+operatorSnapshot.fareZones.size(),12,Ui.TEXT,true);for(Tariff t:operatorSnapshot.tariffs)Ui.text(this,cfg,t.id+" · "+t.name+" · "+money.format(t.pricePerKm)+"/km",10,Ui.MUTED,false);for(Region r:operatorSnapshot.regions)Ui.text(this,cfg,r.numericCode+" → "+r.id+" · "+r.name,10,Ui.MUTED,false);
    }

    private void renderOperatorMapTab(){
        LinearLayout card=Ui.card(this,root);Ui.header(this,card,"MAPA FLOTY NA ŻYWO");
        WebView web=new WebView(this);web.setBackgroundColor(Color.BLACK);WebSettings ws=web.getSettings();ws.setJavaScriptEnabled(true);ws.setDomStorageEnabled(true);
        StringBuilder markers=new StringBuilder();for(OperatorDriver d:operatorSnapshot.drivers){if(Double.isNaN(d.lat)||Double.isNaN(d.lng))continue;if(markers.length()>0)markers.append(',');markers.append("{").append("lat:").append(d.lat).append(",lng:").append(d.lng).append(",name:").append(js(safe(d.taxiId)+" · "+safe(d.status))).append("}");}
        String html="<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><link rel=stylesheet href='https://wolftaxi.starcore.pl/track-static/leaflet/leaflet.css'><style>html,body,#map{height:100%;margin:0;background:#000}</style></head><body><div id=map></div><script src='https://wolftaxi.starcore.pl/track-static/leaflet/leaflet.js'></script><script>const p=["+markers+"];const m=L.map('map').setView([54.5189,18.5305],12);L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19}).addTo(m);p.forEach(x=>L.marker([x.lat,x.lng]).addTo(m).bindPopup(x.name));if(p.length){m.fitBounds(p.map(x=>[x.lat,x.lng]),{padding:[30,30],maxZoom:15});}</script></body></html>";
        web.loadDataWithBaseURL("https://wolftaxi.starcore.pl/",html,"text/html","UTF-8",null);card.addView(web,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,Ui.dp(this,520)));
    }
    private String js(String s){return "\""+safe(s).replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ").replace("\r"," ")+"\"";}
    private void openGeo(double lat,double lng,String label){Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse("geo:"+lat+","+lng+"?q="+lat+","+lng+"("+Uri.encode(label)+")"));try{startActivity(i);}catch(Exception e){showMessage("Nie można otworzyć mapy.",false);}}

    private void renderOperatorAlerts(){if(operatorSnapshot.alerts.isEmpty())return;LinearLayout card=Ui.card(this,root);Ui.header(this,card,"!!! SOS / ALARMY !!!");for(SafetyAlertItem a:operatorSnapshot.alerts){Ui.text(this,card,a.taxiId+" · "+a.driverName+" · "+a.status.toUpperCase(Locale.ROOT),15,Ui.RED,true);if(!safe(a.note).isEmpty())Ui.text(this,card,a.note,12,Ui.TEXT,false);LinearLayout row=Ui.row(this);card.addView(row);if("active".equals(a.status))Ui.rowButton(this,row,"POTWIERDŹ",Ui.ORANGE,v->operatorAction(cb->operatorBackend.acknowledgeAlert(a.id,cb)));Ui.rowButton(this,row,"ZAMKNIJ",Ui.GREEN,v->operatorAction(cb->operatorBackend.closeAlert(a.id,cb)));}}

    private void renderOperatorStats(){int online=0,free=0,queued=0,active=0,waiting=0,exchange=0;for(OperatorDriver d:operatorSnapshot.drivers){if(d.online)online++;if("available".equals(d.status)||"in_queue".equals(d.status))free++;if(d.queuePosition>0)queued++;}for(Order o:operatorSnapshot.orders){if(o.status==OrderStatus.OFFERED||o.status.isActive())active++;if(o.status==OrderStatus.SEARCHING_DRIVER||o.status==OrderStatus.NO_DRIVER)waiting++;if(o.status==OrderStatus.EXCHANGE)exchange++;}LinearLayout r1=Ui.row(this);root.addView(r1);statCard(r1,"WSZYST.",String.valueOf(operatorSnapshot.drivers.size()),Ui.TEXT);statCard(r1,"ONLINE",String.valueOf(online),Ui.GREEN);statCard(r1,"WOLNYCH",String.valueOf(free),Ui.GREEN);LinearLayout r2=Ui.row(this);root.addView(r2);statCard(r2,"KOLEJKA",String.valueOf(queued),Ui.BLUE);statCard(r2,"W KURSIE",String.valueOf(active),Ui.ORANGE);statCard(r2,"OCZ./GIEŁDA",String.valueOf(waiting+exchange),Ui.RED);}
    private void statCard(LinearLayout row,String label,String value,int color){LinearLayout c=compactCard(row);Ui.text(this,c,label,10,Ui.MUTED,true);Ui.text(this,c,value,25,color,true);}
    private void renderOperatorActions(){LinearLayout row=Ui.row(this);root.addView(row);Ui.rowButton(this,row,"NOWE ZLECENIE",Ui.GREEN,v->newOrderDialog());Ui.rowButton(this,row,"KOMUNIKAT",Ui.BLUE,v->messageDialog());if(mode==AppMode.ADMIN)Ui.rowButton(this,row,"ADMIN",Ui.ORANGE,v->adminMenu());}
    private void renderOperatorDrivers(){LinearLayout card=Ui.card(this,root);Ui.text(this,card,"TAXI",13,Ui.MUTED,true);if(operatorSnapshot.drivers.isEmpty()){Ui.text(this,card,"Brak kierowców.",13,Ui.MUTED,false);return;}for(OperatorDriver d:operatorSnapshot.drivers){LinearLayout row=Ui.row(this);card.addView(row);LinearLayout left=Ui.column(this);row.addView(left,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));Ui.text(this,left,safe(d.taxiId)+" · "+safe(d.name),15,Ui.TEXT,true);String meta=(d.online?"online":"offline")+" · "+d.status+" · "+(d.queueRegionId.isEmpty()?safe(d.currentRegionId):d.queueRegionId)+(d.queuePosition>0?" "+d.queuePosition+"/"+d.queueSize:"");if(!safe(d.targetRegionId).isEmpty())meta+=" → "+d.targetRegionId;Ui.text(this,left,meta,12,d.online?Ui.GREEN:Ui.MUTED,false);TextView t=Ui.text(this,row,safe(d.currentTariffId),12,Ui.MUTED,true);t.setGravity(Gravity.END);}}
    private void renderOperatorOrders(){LinearLayout card=Ui.card(this,root);Ui.text(this,card,"ZLECENIA",13,Ui.MUTED,true);int shown=0;for(Order o:operatorSnapshot.orders){if(shown++>=12)break;LinearLayout block=Ui.column(this);block.setPadding(0,Ui.dp(this,5),0,Ui.dp(this,8));card.addView(block);Ui.text(this,block,o.id+" · "+o.status.label.toUpperCase(Locale.ROOT),13,orderColor(o.status),true);Ui.text(this,block,safe(o.pickupAddress)+(safe(o.destinationAddress).isEmpty()?"":" → "+o.destinationAddress),15,Ui.TEXT,true);Ui.text(this,block,"Region: "+safe(o.pickupRegionId)+" · "+(o.estimatedPrice>0?money.format(o.estimatedPrice):"bez wyceny"),12,Ui.MUTED,false);if(!safe(o.passengerPhone).isEmpty())Ui.text(this,block,"SMS tracking: "+safe(o.trackingSmsStatus)+(safe(o.trackingSmsLastError).isEmpty()?"":" · "+safe(o.trackingSmsLastError)),11,"sent".equals(o.trackingSmsStatus)?Ui.GREEN:("failed".equals(o.trackingSmsStatus)?Ui.RED:Ui.ORANGE),false);if(o.status!=OrderStatus.COMPLETED&&o.status!=OrderStatus.CANCELLED){LinearLayout buttons=Ui.row(this);block.addView(buttons);Ui.rowButton(this,buttons,"PRZYPISZ",Ui.BLUE,v->assignOrderDialog(o));Ui.rowButton(this,buttons,"NAKAZ",Ui.MAGENTA,v->forceOrderDialog(o));Ui.rowButton(this,buttons,"ANULUJ",Ui.RED,v->confirm("Anulować "+o.id+"?",()->operatorAction(cb->operatorBackend.cancelOrder(o.id,cb))));}}if(shown==0)Ui.text(this,card,"Brak zleceń.",13,Ui.MUTED,false);}
    private void renderOperatorMessages(){
        LinearLayout card=Ui.card(this,root);Ui.text(this,card,"KOMUNIKATY",13,Ui.MUTED,true);
        for(int i=0;i<Math.min(operatorSnapshot.messages.size(),8);i++){
            DispatchMessage m=operatorSnapshot.messages.get(i);
            String target="all".equals(m.targetType)?"WSZYSCY":("driver".equals(m.targetType)?"KIEROWCA "+operatorDriverLabel(m.targetId):"REGION "+m.targetId);
            String title=safe(m.title).isEmpty()?("question".equals(m.type)?"PYTANIE":"KOMUNIKAT"):safe(m.title);
            Ui.text(this,card,title+" · "+target,12,Ui.MUTED,true);
            Ui.text(this,card,safe(m.body),14,"urgent".equals(m.type)?Ui.RED:("warning".equals(m.type)?Ui.ORANGE:("question".equals(m.type)?Ui.BLUE:Ui.TEXT)),false);
            if("question".equals(m.type))Ui.text(this,card,"TAK "+m.yesCount+"  ·  NIE "+m.noCount,11,Ui.GREEN,true);
        }
        if(operatorSnapshot.messages.isEmpty())Ui.text(this,card,"Brak komunikatów.",13,Ui.MUTED,false);
    }
    private String operatorDriverLabel(String driverId){for(OperatorDriver d:operatorSnapshot.drivers)if(d.id.equals(driverId))return safe(d.taxiId).isEmpty()?safe(d.name):safe(d.taxiId);return driverId;}

    private void renderOperatorSettlementSummary(){
        LinearLayout card=Ui.card(this,root);Ui.text(this,card,"DZISIAJ / ROZLICZENIA",13,Ui.MUTED,true);
        Ui.text(this,card,"Kursy: "+operatorSnapshot.todayRides+" · Obrót: "+money.format(operatorSnapshot.todayGross),14,Ui.TEXT,true);
        Ui.text(this,card,"Gotówka "+money.format(operatorSnapshot.todayCash)+" · Karta "+money.format(operatorSnapshot.todayCard)+" · Bezgot. "+money.format(operatorSnapshot.todayCashless),11,Ui.MUTED,false);
    }

    private void renderAdminSummary(){LinearLayout card=Ui.card(this,root);Ui.text(this,card,"ADMINISTRACJA",13,Ui.MUTED,true);Ui.text(this,card,"Konta: "+operatorSnapshot.users.size()+" · Taryfy: "+operatorSnapshot.tariffs.size()+" · Regiony: "+operatorSnapshot.regions.size()+" · Strefy: "+operatorSnapshot.fareZones.size(),14,Ui.TEXT,true);for(int i=0;i<Math.min(operatorSnapshot.users.size(),4);i++){UserAccount u=operatorSnapshot.users.get(i);Ui.text(this,card,(u.enabled?"● ":"○ ")+safe(u.name)+" · "+String.join("/",u.roles),12,u.enabled?Ui.GREEN:Ui.MUTED,false);}}
    private int orderColor(OrderStatus s){if(s==OrderStatus.NO_DRIVER||s==OrderStatus.CANCELLED)return Ui.RED;if(s==OrderStatus.OFFERED||s==OrderStatus.SEARCHING_DRIVER)return Ui.ORANGE;if(s.isActive())return Ui.BLUE;return Ui.MUTED;}

    private void newOrderDialog(){if(operatorSnapshot==null)return;
        ScrollView scroll=new ScrollView(this);LinearLayout box=dialogColumn();scroll.addView(box);
        EditText pickup=plainField("Podstawienie"),destination=plainField("Cel"),passengerName=plainField("Klient"),passengerPhone=plainField("Telefon"),scheduled=plainField("Termin YYYY-MM-DD HH:mm"),voucher=plainField("Voucher"),costCenter=plainField("Centrum kosztów"),bookingRef=plainField("Rezerwacja / ref."),notes=plainField("Uwagi");
        passengerPhone.setInputType(InputType.TYPE_CLASS_PHONE);EditText passengers=numberField("Pasażerowie"),estimated=numberField("Kwota orientacyjna");passengers.setText("1");
        Spinner regions=spinner(labelsRegions()),tariffs=spinner(labelsTariffs()),dispatch=spinner(new String[]{"KOLEJKA / AUTOMAT","GIEŁDA"}),source=spinner(new String[]{"DYSP.","TELEFON","APLIKACJA","HOTEL/FIRMA"}),client=spinner(labelsClients()),company=spinner(labelsCompanies());
        box.addView(pickup);box.addView(destination);box.addView(regions);box.addView(tariffs);box.addView(dispatch);box.addView(scheduled);box.addView(source);box.addView(passengerName);box.addView(passengerPhone);box.addView(client);box.addView(company);box.addView(voucher);box.addView(costCenter);box.addView(bookingRef);box.addView(passengers);box.addView(estimated);
        CheckBox card=new CheckBox(this),luggage=new CheckBox(this),pet=new CheckBox(this),english=new CheckBox(this),mine=new CheckBox(this);card.setText("KARTA");luggage.setText("BAGAŻ");pet.setText("ZWIERZĘ");english.setText("ANGIELSKI");mine.setText("MINA / RYZYKO");for(CheckBox c:new CheckBox[]{card,luggage,pet,english,mine}){c.setTextColor(Ui.TEXT);box.addView(c);}box.addView(notes);
        new AlertDialog.Builder(this).setTitle("Nowe zlecenie").setView(scroll).setNegativeButton("Wróć",null).setPositiveButton("ZAPISZ / WYDAJ",(d,w)->{
            String region=regions.getSelectedItemPosition()<=0?"":operatorSnapshot.regions.get(regions.getSelectedItemPosition()-1).id;
            String tariff=tariffs.getSelectedItemPosition()<=0?"":operatorSnapshot.tariffs.get(tariffs.getSelectedItemPosition()-1).id;
            String clientId=client.getSelectedItemPosition()<=0?"":operatorSnapshot.clients.get(client.getSelectedItemPosition()-1).id;
            String companyId=company.getSelectedItemPosition()<=0?"":operatorSnapshot.companies.get(company.getSelectedItemPosition()-1).id;
            int pc=Math.max(1,(int)parseDouble(passengers));String dm=dispatch.getSelectedItemPosition()==1?"exchange":"queue";String[] sources={"dispatch","phone","app","hotel"};long when=parseDateTime(scheduled.getText().toString());
            operatorAction(cb->operatorBackend.createOrderFull(pickup.getText().toString(),destination.getText().toString(),region,tariff,dm,sources[source.getSelectedItemPosition()],when,passengerName.getText().toString(),passengerPhone.getText().toString(),clientId,companyId,voucher.getText().toString(),costCenter.getText().toString(),bookingRef.getText().toString(),pc,parseDouble(estimated),card.isChecked(),luggage.isChecked(),pet.isChecked(),english.isChecked(),mine.isChecked(),notes.getText().toString(),cb));
        }).show();
    }
    private long parseDateTime(String value){String x=safe(value).trim();if(x.isEmpty())return 0;try{return new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).parse(x).getTime();}catch(Exception e){return 0;}}
    private String[] labelsRegions(){ArrayList<String>x=new ArrayList<>();x.add("Region: automatycznie / brak");for(Region r:operatorSnapshot.regions)x.add((r.shortName.isEmpty()?r.id:r.shortName)+" · "+r.name);return x.toArray(new String[0]);}
    private String[] labelsTariffs(){ArrayList<String>x=new ArrayList<>();x.add("Taryfa: domyślna");for(Tariff t:operatorSnapshot.tariffs)x.add(t.shortName+" · "+t.name);return x.toArray(new String[0]);}
    private Spinner spinner(String[] values){Spinner s=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values);s.setAdapter(a);return s;}
    private LinearLayout dialogColumn(){LinearLayout box=Ui.column(this);box.setPadding(Ui.dp(this,18),Ui.dp(this,8),Ui.dp(this,18),0);return box;}
    private void assignOrderDialog(Order order){ArrayList<OperatorDriver> drivers=new ArrayList<>();for(OperatorDriver d:operatorSnapshot.drivers)if(d.enabled&&d.onShift)drivers.add(d);if(drivers.isEmpty()){showMessage("Brak kierowców na zmianie.",false);return;}String[] labels=new String[drivers.size()];for(int i=0;i<labels.length;i++){OperatorDriver d=drivers.get(i);labels[i]=d.taxiId+" · "+d.status+(d.queuePosition>0?" · "+d.queueRegionId+" "+d.queuePosition+"/"+d.queueSize:"");}new AlertDialog.Builder(this).setTitle("Przypisz "+order.id).setItems(labels,(dialog,which)->operatorAction(cb->operatorBackend.assignOrder(order.id,drivers.get(which).id,cb))).show();}
    private void forceOrderDialog(Order order){ArrayList<OperatorDriver> drivers=new ArrayList<>();for(OperatorDriver d:operatorSnapshot.drivers)if(d.enabled&&d.onShift)drivers.add(d);if(drivers.isEmpty()){showMessage("Brak kierowców na zmianie.",false);return;}String[] labels=new String[drivers.size()];for(int i=0;i<labels.length;i++){OperatorDriver d=drivers.get(i);labels[i]=d.taxiId+" · "+d.status+" · priorytet "+d.priorityPoints;}new AlertDialog.Builder(this).setTitle("NAKAZ · "+order.id).setItems(labels,(dialog,which)->confirm("Wysłać zlecenie z nakazu do "+drivers.get(which).taxiId+"?",()->operatorAction(cb->operatorBackend.forceOrder(order.id,drivers.get(which).id,cb)))).show();}
    private void messageDialog(){
        LinearLayout box=dialogColumn();
        EditText title=plainField("Tytuł");EditText body=plainField("Treść");body.setSingleLine(false);body.setMinLines(3);
        Spinner type=spinner(new String[]{"Informacja","Ostrzeżenie","Pilny","System","Pytanie TAK/NIE"});
        ArrayList<String> recipientLabels=new ArrayList<>();ArrayList<String> recipientTypes=new ArrayList<>();ArrayList<String> recipientIds=new ArrayList<>();
        recipientLabels.add("WSZYSCY");recipientTypes.add("all");recipientIds.add("");
        for(OperatorDriver d:operatorSnapshot.drivers){if(!d.enabled)continue;recipientLabels.add("KIEROWCA · "+safe(d.taxiId)+" · "+safe(d.name));recipientTypes.add("driver");recipientIds.add(d.id);}
        for(Region r:operatorSnapshot.regions){if(!r.active)continue;recipientLabels.add("REGION · "+(safe(r.shortName).isEmpty()?r.id:r.shortName)+" · "+safe(r.name));recipientTypes.add("region");recipientIds.add(r.id);}
        Spinner recipient=spinner(recipientLabels.toArray(new String[0]));
        CheckBox ack=new CheckBox(this),voice=new CheckBox(this);ack.setText("Wymagaj potwierdzenia");voice.setText("Czytaj głosowo");voice.setChecked(true);ack.setTextColor(Ui.TEXT);voice.setTextColor(Ui.TEXT);
        box.addView(title);box.addView(body);box.addView(type);box.addView(recipient);box.addView(ack);box.addView(voice);
        new AlertDialog.Builder(this).setTitle("Komunikat do kierowców").setView(box).setNegativeButton("Wróć",null).setPositiveButton("WYŚLIJ",(d,w)->{
            int ri=recipient.getSelectedItemPosition();String tt=recipientTypes.get(ri);String tid=recipientIds.get(ri);
            int ti=type.getSelectedItemPosition();String mt=ti==1?"warning":(ti==2?"urgent":(ti==3?"system":(ti==4?"question":"info")));
            operatorAction(cb->operatorBackend.sendMessage(title.getText().toString(),body.getText().toString(),mt,tt,tid,ack.isChecked(),voice.isChecked(),cb));
        }).show();
    }
    private void adminMenu(){new AlertDialog.Builder(this).setTitle("Administracja").setItems(new String[]{"Nowe konto","Nowa / zmień taryfę","Nowy / zmień region","Nowa / zmień strefę"},(d,w)->{if(w==0)createUserDialog();else if(w==1)tariffDialog();else if(w==2)regionDialog();else zoneDialog();}).show();}
    private void createUserDialog(){ScrollView scroll=new ScrollView(this);LinearLayout box=dialogColumn();scroll.addView(box);EditText email=field("E-mail",false),name=plainField("Nazwa"),password=field("Hasło",true),taxi=plainField("ID taxi, np. TX2"),number=numberField("Numer taxi");CheckBox driver=new CheckBox(this),dispatcher=new CheckBox(this),admin=new CheckBox(this),smsGateway=new CheckBox(this);driver.setText("Kierowca");dispatcher.setText("Dyspozytor");admin.setText("Administrator");smsGateway.setText("Bramka SMS");driver.setTextColor(Ui.TEXT);dispatcher.setTextColor(Ui.TEXT);admin.setTextColor(Ui.TEXT);smsGateway.setTextColor(Ui.TEXT);box.addView(email);box.addView(name);box.addView(password);box.addView(driver);box.addView(dispatcher);box.addView(admin);box.addView(smsGateway);box.addView(taxi);box.addView(number);new AlertDialog.Builder(this).setTitle("Nowe konto").setView(scroll).setNegativeButton("Wróć",null).setPositiveButton("UTWÓRZ",(d,w)->{int n=0;try{n=Integer.parseInt(number.getText().toString().trim());}catch(Exception ignored){}final int taxiNumber=n;operatorAction(cb->operatorBackend.createUser(email.getText().toString(),name.getText().toString(),password.getText().toString(),driver.isChecked(),dispatcher.isChecked(),admin.isChecked(),smsGateway.isChecked(),taxi.getText().toString(),taxiNumber,cb));}).show();}
    private void tariffDialog(){LinearLayout box=dialogColumn();EditText id=plainField("ID, np. T3"),name=plainField("Nazwa"),start=numberField("Opłata startowa"),km=numberField("Cena / km");box.addView(id);box.addView(name);box.addView(start);box.addView(km);new AlertDialog.Builder(this).setTitle("Taryfa").setView(box).setNegativeButton("Wróć",null).setPositiveButton("ZAPISZ",(d,w)->operatorAction(cb->operatorBackend.saveTariff(id.getText().toString().trim().toUpperCase(),name.getText().toString(),parseDouble(start),parseDouble(km),cb))).show();}
    private void regionDialog(){LinearLayout box=dialogColumn();EditText id=plainField("ID, np. R21"),code=plainField("Kod numeryczny, np. 21"),name=plainField("Nazwa"),priority=numberField("Priorytet");box.addView(id);box.addView(code);box.addView(name);box.addView(priority);new AlertDialog.Builder(this).setTitle("Region").setView(box).setNegativeButton("Wróć",null).setPositiveButton("ZAPISZ",(d,w)->operatorAction(cb->operatorBackend.saveRegion(id.getText().toString().trim().toUpperCase(),code.getText().toString().trim(),name.getText().toString(),(int)parseDouble(priority),cb))).show();}
    private void zoneDialog(){LinearLayout box=dialogColumn();EditText id=plainField("ID, np. S2"),name=plainField("Nazwa"),tariff=plainField("Domyślna taryfa, np. T2");box.addView(id);box.addView(name);box.addView(tariff);new AlertDialog.Builder(this).setTitle("Strefa taryfowa").setView(box).setNegativeButton("Wróć",null).setPositiveButton("ZAPISZ",(d,w)->operatorAction(cb->operatorBackend.saveZone(id.getText().toString().trim().toUpperCase(),name.getText().toString(),tariff.getText().toString().trim().toUpperCase(),cb))).show();}
    private double parseDouble(EditText input){try{return Double.parseDouble(input.getText().toString().replace(',','.').trim());}catch(Exception e){return 0;}}

    private void renderSessionActions(){if(roleCount()>1)Ui.button(this,root,"ZMIEŃ TRYB",Color.rgb(93,112,118),v->showModeChooser());Ui.button(this,root,"WYLOGUJ",Color.rgb(93,112,118),v->confirm("Wylogować?",this::logout));}
    private void logout(){backend.signOut((ok,message)->runOnUiThread(()->{operatorBackend.stop();stopLocationService();stopSmsGateway();loginScreen();}));}

    private void announceDriverSnapshot(DriverSnapshot value){
        if(!ttsReady||value==null||value.driver==null||!value.driver.ttsEnabled)return;
        if(value.offer!=null&&!value.offer.id.equals(lastSpokenOfferId)){lastSpokenOfferId=value.offer.id;speak("Nowe zlecenie. "+value.offer.pickupAddress+(value.offer.destinationAddress.isEmpty()?"":". Cel "+value.offer.destinationAddress));}
        if(value.activeOrder!=null&&value.activeOrder.forced&&!value.activeOrder.id.equals(lastSpokenForcedId)){lastSpokenForcedId=value.activeOrder.id;speak("Zlecenie z nakazu. "+value.activeOrder.pickupAddress);}
        if(!value.exchange.isEmpty()&&!value.exchange.get(0).id.equals(lastSpokenExchangeId)){Order o=value.exchange.get(0);lastSpokenExchangeId=o.id;speak("Giełda. "+o.pickupAddress+(o.destinationAddress.isEmpty()?"":" do "+o.destinationAddress));}
        for(DispatchMessage m:value.messages){if(m.voiceRead&&!spokenMessageIds.contains(m.id)){spokenMessageIds.add(m.id);if("urgent".equals(m.type)||"question".equals(m.type)||m.requiresAck)speak((m.title.isEmpty()?("question".equals(m.type)?"Pytanie centrali":"Komunikat centrali"):m.title)+". "+m.body);}}
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
    private void renderSmsGatewayDashboard(){
        if(mode!=AppMode.SMS_GATEWAY||root==null)return;
        root.removeAllViews();
        LinearLayout top=Ui.card(this,root);
        Ui.header(this,top,"WOLFTAXI · BRAMKA SMS");
        Ui.text(this,top,safe(backend.currentDisplayName()),14,Ui.TEXT,true);
        boolean enabled=SmsGatewayService.isEnabled(this);
        Ui.text(this,top,enabled?"● AKTYWNA":"○ WYŁĄCZONA",18,enabled?Ui.GREEN:Ui.RED,true);
        Ui.text(this,top,"Telefon wysyła prywatne SMS-y z linkiem śledzenia przez własną kartę SIM.",11,Ui.MUTED,false);

        LinearLayout state=Ui.card(this,root);
        Ui.header(this,state,"STAN BRAMKI");
        Ui.text(this,state,SmsGatewayService.lastStatus(this),14,Ui.TEXT,true);
        String recipient=SmsGatewayService.lastRecipient(this); if(!recipient.isEmpty())Ui.text(this,state,"Ostatni numer: "+recipient,12,Ui.MUTED,false);
        long at=SmsGatewayService.lastAt(this); if(at>0)Ui.text(this,state,"Aktualizacja: "+clock.format(new Date(at)),11,Ui.MUTED,false);
        String error=SmsGatewayService.lastError(this); if(!error.isEmpty())Ui.text(this,state,error,11,Ui.RED,false);

        if(enabled) Ui.button(this,root,"WYŁĄCZ BRAMKĘ",Ui.RED,v->{stopSmsGateway();renderSmsGatewayDashboard();});
        else Ui.button(this,root,"WŁĄCZ BRAMKĘ",Ui.GREEN,v->ensureSmsGateway(true));
        Ui.button(this,root,"ODŚWIEŻ",Ui.BLUE,v->renderSmsGatewayDashboard());
        if(roleCount()>1)Ui.button(this,root,"ZMIEŃ TRYB",Color.rgb(93,112,118),v->showModeChooser());
        Ui.button(this,root,"WYLOGUJ",Color.rgb(93,112,118),v->logout());
    }

    private void ensureSmsGateway(boolean ask){
        if(!backend.isSignedIn()||!backend.hasRole("sms_gateway"))return;
        boolean sms=checkSelfPermission(Manifest.permission.SEND_SMS)==PackageManager.PERMISSION_GRANTED;
        if(!sms){
            if(ask){
                if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.SEND_SMS,Manifest.permission.POST_NOTIFICATIONS},REQUEST_SMS);
                else requestPermissions(new String[]{Manifest.permission.SEND_SMS},REQUEST_SMS);
            }
            return;
        }
        Intent service=new Intent(this,SmsGatewayService.class);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);
        handler.postDelayed(this::renderSmsGatewayDashboard,500);
    }
    private void stopSmsGateway(){
        SmsGatewayService.setEnabled(this,false);
        stopService(new Intent(this,SmsGatewayService.class));
    }
    private void resumeSmsGatewayIfEnabled(){
        if(!SmsGatewayService.isEnabled(this)||!backend.isSignedIn()||!backend.hasRole("sms_gateway"))return;
        if(checkSelfPermission(Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED)return;
        Intent service=new Intent(this,SmsGatewayService.class);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);
    }

    private void ensureLocationService(boolean ask){if(mode!=AppMode.DRIVER||"DEMO".equals(snapshot==null?"":snapshot.backendMode))return;boolean fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED,coarse=checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;if(!fine&&!coarse){if(ask){if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.POST_NOTIFICATIONS},REQUEST_LOCATION);else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},REQUEST_LOCATION);}return;}Intent service=new Intent(this,DriverLocationService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);}
    private void stopLocationService(){stopService(new Intent(this,DriverLocationService.class));}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==REQUEST_LOCATION)ensureLocationService(false);else if(requestCode==REQUEST_SMS){ensureSmsGateway(false);renderSmsGatewayDashboard();}}
    private void openNavigation(String address){if(safe(address).isEmpty()){showMessage("Brak adresu do nawigacji.",false);return;}Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q="+Uri.encode(address)));try{startActivity(intent);}catch(Exception e){showMessage("Nie znaleziono aplikacji nawigacyjnej.",false);}}
    private void openDialer(String phone){Intent intent=new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+Uri.encode(phone)));try{startActivity(intent);}catch(Exception e){showMessage("Nie można otworzyć telefonu.",false);}}
    private void openTrackingMap(String url){if(safe(url).isEmpty()){showMessage("Brak linku śledzenia.",false);return;}Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse(url));try{startActivity(intent);}catch(Exception e){showMessage("Nie można otworzyć mapy kursu.",false);}}
    private void copyTrackingLink(String url){if(safe(url).isEmpty()){showMessage("Brak linku śledzenia.",false);return;}android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WolfTaxi · śledzenie kursu",url));showMessage("Link śledzenia skopiowany.",true);}
    private String safe(String value){return value==null?"":value;}
}
