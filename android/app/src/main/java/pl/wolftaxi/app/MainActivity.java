package pl.wolftaxi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Native client for driver and passenger. No WebView, no embedded secrets. */
public class MainActivity extends Activity {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root, list;
    private TextView feedback, availability;
    private String base="", token="", role="";
    private boolean visible=false, loading=false;
    private int epoch=0;
    private final Runnable poll = new Runnable(){ public void run(){ if(visible&&!token.isEmpty()) refresh(); ui.postDelayed(this,5000); }};
    interface Work { Object run() throws Exception; }
    interface Done { void accept(Object value) throws Exception; }
    @Override public void onCreate(Bundle state){super.onCreate(state); loginScreen();}
    @Override public void onResume(){super.onResume();visible=true;ui.post(poll);}
    @Override public void onPause(){super.onPause();visible=false;ui.removeCallbacks(poll);}
    @Override public void onDestroy(){super.onDestroy();epoch++;network.shutdownNow();ui.removeCallbacksAndMessages(null);}
    private void screen(String title){
        ScrollView scroll=new ScrollView(this);root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(28,36,28,28);root.setBackgroundColor(Color.rgb(242,245,243));scroll.setFillViewport(true);scroll.addView(root);setContentView(scroll);
        if(android.os.Build.VERSION.SDK_INT>=30) scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        text(root,"WolfTaxi",14);text(root,title,28);feedback=text(root,"",14);
    }
    private TextView text(LinearLayout parent,String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.rgb(23,45,53));t.setPadding(0,12,0,12);parent.addView(t);return t;}
    private EditText input(String hint,boolean password){EditText e=new EditText(this);e.setHint(hint);e.setSingleLine(true);e.setInputType(password?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT);root.addView(e);return e;}
    private Button button(LinearLayout parent,String label,View.OnClickListener click){Button b=new Button(this);b.setText(label);b.setOnClickListener(click);parent.addView(b);return b;}
    private JSONObject json(String... pairs) throws JSONException {JSONObject j=new JSONObject();for(int i=0;i<pairs.length;i+=2)j.put(pairs[i],pairs[i+1]);return j;}
    private void task(Work work,Done done){int version=epoch;network.execute(()->{try{Object value=work.run();ui.post(()->{if(isDestroyed()||epoch!=version)return;try{done.accept(value);}catch(Exception e){feedback.setText(e.getMessage());}});}catch(Exception e){ui.post(()->{if(!isDestroyed()&&epoch==version)feedback.setText("Błąd: "+e.getMessage());});}});}
    private Object api(String path,JSONObject body) throws Exception{
        HttpURLConnection conn=(HttpURLConnection)new URL(base+"/api"+path).openConnection();conn.setConnectTimeout(8000);conn.setReadTimeout(8000);conn.setInstanceFollowRedirects(false);conn.setRequestProperty("Authorization","Bearer "+token);
        try{
            if(body!=null){conn.setRequestMethod("POST");conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json");try(OutputStream out=conn.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=conn.getResponseCode();InputStream source=status>=400?conn.getErrorStream():conn.getInputStream();if(source==null)throw new IOException("HTTP "+status);
            ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=source){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>2_000_000)throw new IOException("Odpowiedź jest za duża");}}
            Object value=new JSONTokener(out.toString("UTF-8")).nextValue();if(status<200||status>=300)throw new IOException(value instanceof JSONObject?((JSONObject)value).optString("error","HTTP "+status):"HTTP "+status);return value;
        }finally{conn.disconnect();}
    }
    private void loginScreen(){
        epoch++;token="";role="";list=null;screen("Witaj w centrali");
        EditText server=input("Adres serwera, np. https://taxi.example.pl",false);server.setText(getPreferences(0).getString("server","http://10.0.2.2:8080"));
        EditText login=input("Login",false),pass=input("Hasło",true);
        button(root,"Zaloguj",v->{try{URI uri=new URI(server.getText().toString().trim());if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!("https".equals(uri.getScheme())||"http".equals(uri.getScheme()))||!(uri.getPath().isEmpty()||uri.getPath().equals("/")))throw new Exception("Podaj adres HTTP/HTTPS bez ścieżki.");base=uri.toString().replaceAll("/+$","");JSONObject data=json("login",login.getText().toString(),"password",pass.getText().toString());v.setEnabled(false);task(()->{try{return api("/login",data);}finally{ui.post(()->v.setEnabled(true));}},value->{JSONObject session=(JSONObject)value;token=session.getString("token");role=session.getString("role");getPreferences(0).edit().putString("server",base).apply();dashboard(session.getString("name"));});}catch(Exception e){feedback.setText(e.getMessage());}});
        text(root,"Konto klienta utworzysz w panelu pod adresem serwera. Konto kierowcy zakłada administrator. Sesja jest przechowywana tylko w pamięci aplikacji.",14);
    }
    private void mutate(String path,JSONObject data){task(()->api(path,data),r->{feedback.setText("Zapisano.");refresh();});}
    private void dashboard(String name){
        screen(name+" · "+label(role));
        button(root,"Wyloguj",v->task(()->api("/logout",new JSONObject()),r->loginScreen()));
        if(role.equals("admin")||role.equals("dispatcher")){text(root,"Panel centrali jest dostępny w przeglądarce: "+base,18);return;}
        if(role.equals("driver")){
            availability=text(root,"",16);
            String[][] states={{"Wolny","available"},{"Przerwa","break"},{"Poza pracą","offline"}};
            for(String[] s:states)button(root,s[0],v->{try{mutate("/availability",json("availability",s[1]));}catch(Exception e){feedback.setText(e.getMessage());}});
        }else{
            EditText pickup=input("Adres odbioru",false),destination=input("Adres docelowy",false),contact=input("Telefon kontaktowy",false);
            button(root,"Zamów taxi",v->{try{JSONObject data=json("pickup",pickup.getText().toString(),"destination",destination.getText().toString(),"contact",contact.getText().toString());task(()->api("/rides",data),r->{pickup.setText("");destination.setText("");contact.setText("");feedback.setText("Zamówienie przyjęte.");refresh();});}catch(Exception e){feedback.setText(e.getMessage());}});
        }
        text(root,"Twoje zlecenia",24);text(root,"Aktualizacja co 5 sekund, gdy aplikacja jest otwarta.",13);
        button(root,"Odśwież",v->refresh());list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);root.addView(list);refresh();
    }
    private void refresh(){
        if(loading||list==null||!(role.equals("client")||role.equals("driver")))return;loading=true;
        task(()->{try{JSONObject result=new JSONObject();result.put("me",api("/me",null));result.put("rides",api("/rides",null));return result;}finally{ui.post(()->loading=false);}},value->{JSONObject result=(JSONObject)value;if(role.equals("driver"))availability.setText("Status: "+label(result.getJSONObject("me").getString("availability")));render(result.getJSONArray("rides"));});
    }
    private void render(JSONArray rides) throws Exception{
        list.removeAllViews();if(rides.length()==0)text(list,"Brak zleceń.",18);
        for(int i=0;i<rides.length();i++){
            JSONObject ride=rides.getJSONObject(i);int id=ride.getInt("id");String status=ride.getString("status");
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(12,18,12,18);list.addView(card);
            text(card,"#"+id+" · "+label(status),20);text(card,ride.getString("pickup")+" → "+ride.getString("destination"),18);text(card,"Kontakt: "+ride.getString("contact"),14);
            if(!ride.isNull("driver_name"))text(card,"Kierowca: "+ride.getString("driver_name"),14);
            String next=status.equals("assigned")?"accepted":status.equals("accepted")?"arrived":status.equals("arrived")?"in_progress":status.equals("in_progress")?"completed":"";
            if(role.equals("driver")&&!next.isEmpty())button(card,action(next),v->change(id,next));
            if(role.equals("client")&&(status.equals("new")||status.equals("assigned")||status.equals("accepted")||status.equals("arrived")))button(card,"Anuluj zamówienie",v->new AlertDialog.Builder(this).setMessage("Anulować kurs #"+id+"?").setNegativeButton("Wróć",null).setPositiveButton("Anuluj",(d,w)->change(id,"cancelled")).show());
        }
    }
    private void change(int id,String status){try{mutate("/rides/"+id+"/status",json("status",status));}catch(Exception e){feedback.setText(e.getMessage());}}
    private String action(String status){switch(status){case "accepted":return "Przyjmij zlecenie";case "arrived":return "Jestem na miejscu";case "in_progress":return "Rozpocznij kurs";default:return "Zakończ kurs";}}
    private String label(String value){switch(value){case "admin":return "Administrator";case "dispatcher":return "Dyspozytor";case "driver":return "Kierowca";case "client":return "Klient";case "new":return "Nowe";case "assigned":return "Przydzielone";case "accepted":return "Kierowca w drodze";case "arrived":return "Na miejscu";case "in_progress":return "Kurs w trakcie";case "completed":return "Zakończone";case "cancelled":return "Anulowane";case "available":return "Wolny";case "busy":return "Zajęty";case "break":return "Przerwa";default:return "Poza pracą";}}
}
