package pl.wolftaxi.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class Ui {
    public static final int BG = Color.rgb(7, 16, 20);
    public static final int CARD = Color.rgb(15, 29, 35);
    public static final int CARD_ALT = Color.rgb(20, 38, 45);
    public static final int TEXT = Color.rgb(238, 247, 244);
    public static final int MUTED = Color.rgb(153, 178, 174);
    public static final int GREEN = Color.rgb(66, 230, 164);
    public static final int ORANGE = Color.rgb(255, 176, 66);
    public static final int RED = Color.rgb(255, 92, 92);
    public static final int BLUE = Color.rgb(90, 170, 255);

    private Ui() {}

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static LinearLayout card(Context context, ViewGroup parent) {
        LinearLayout card = column(context);
        card.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(context, 14));
        background.setStroke(dp(context, 1), Color.rgb(33, 61, 69));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(context, 10));
        parent.addView(card, params);
        return card;
    }

    public static TextView text(Context context, ViewGroup parent, String value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setPadding(0, dp(context, 2), 0, dp(context, 2));
        parent.addView(view);
        return view;
    }

    public static Button button(Context context, ViewGroup parent, String label, int accent, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextColor(Color.rgb(4, 14, 17));
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setOnClickListener(listener);
        GradientDrawable background = new GradientDrawable();
        background.setColor(accent);
        background.setCornerRadius(dp(context, 12));
        button.setBackground(background);
        button.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 52));
        params.setMargins(0, dp(context, 6), 0, 0);
        parent.addView(button, params);
        return button;
    }

    public static Button rowButton(Context context, LinearLayout parent, String label, int accent, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextColor(Color.rgb(4, 14, 17));
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setOnClickListener(listener);
        GradientDrawable background = new GradientDrawable();
        background.setColor(accent);
        background.setCornerRadius(dp(context, 10));
        button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(context, 48), 1f);
        params.setMargins(dp(context, 3), dp(context, 4), dp(context, 3), 0);
        parent.addView(button, params);
        return button;
    }
}
