package pl.wolftaxi.app.service;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import pl.wolftaxi.app.data.SessionStore;

/** Wznawia prywatną bramkę SMS po restarcie telefonu, jeżeli użytkownik wcześniej ją włączył. */
public final class SmsGatewayBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!SmsGatewayService.isEnabled(context)) return;

        SessionStore session = new SessionStore(context);
        if (!session.hasSession() || !session.hasRole("sms_gateway")) {
            SmsGatewayService.setEnabled(context, false);
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return;

        Intent service = new Intent(context, SmsGatewayService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
        else context.startService(service);
    }
}
