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
    // Terminalowy motyw inspirowany publicznymi ekranami RT3000: ciemne tło,
    // zielone nagłówki, bardzo gęsty układ i mocne kolory stanów.
    public static final int BG = Color.rgb(2, 5, 2);
    public static final int CARD = Color.rgb(4, 15, 5);
    public static final int CARD_ALT = Color.rgb(7, 29, 9);
    public static final int TEXT = Color.rgb(240, 244, 236);
    public static final int MUTED = Color.rgb(166, 177, 163);
    public static final int GREEN = Color.rgb(77, 217, 91);
    public static final int DARK_GREEN = Color.rgb(8, 92, 30);
    public static final int ORANGE = Color.rgb(255, 207, 69);
    public static final int RED = Color.rgb(255, 73, 73);
    public static final int BLUE = Color.rgb(84, 187, 255);
    public static final int MAGENTA = Color.rgb(255, 86, 218);
    public static final int LINE = Color.rgb(68, 91, 67);

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
        card.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(context, 2));
        background.setStroke(dp(context, 1), LINE);
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(context, 5));
        parent.addView(card, params);
        return card;
    }

    public static TextView text(Context context, ViewGroup parent, String value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setPadding(0, dp(context, 1), 0, dp(context, 1));
        parent.addView(view);
        return view;
    }

    public static TextView header(Context context, ViewGroup parent, String value) {
        TextView view = text(context, parent, value, 12, TEXT, true);
        view.setBackgroundColor(DARK_GREEN);
        view.setPadding(dp(context, 6), dp(context, 4), dp(context, 6), dp(context, 4));
        return view;
    }

    public static Button button(Context context, ViewGroup parent, String label, int accent, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextColor(Color.rgb(0, 0, 0));
        button.setTextSize(13);
        button.setAllCaps(true);
        button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        button.setOnClickListener(listener);
        GradientDrawable background = new GradientDrawable();
        background.setColor(accent);
        background.setCornerRadius(dp(context, 2));
        button.setBackground(background);
        button.setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 46));
        params.setMargins(0, dp(context, 3), 0, 0);
        parent.addView(button, params);
        return button;
    }

    public static Button rowButton(Context context, LinearLayout parent, String label, int accent, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextColor(Color.rgb(0, 0, 0));
        button.setTextSize(11);
        button.setAllCaps(true);
        button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        button.setOnClickListener(listener);
        GradientDrawable background = new GradientDrawable();
        background.setColor(accent);
        background.setCornerRadius(dp(context, 2));
        button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(context, 42), 1f);
        params.setMargins(dp(context, 2), dp(context, 2), dp(context, 2), 0);
        parent.addView(button, params);
        return button;
    }

    public static Button tabButton(Context context, LinearLayout parent, String label, boolean active, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(9);
        button.setAllCaps(true);
        button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        button.setTextColor(active ? Color.rgb(0, 0, 0) : TEXT);
        button.setOnClickListener(listener);
        GradientDrawable background = new GradientDrawable();
        background.setColor(active ? GREEN : CARD_ALT);
        background.setCornerRadius(dp(context, 1));
        background.setStroke(dp(context, 1), active ? GREEN : LINE);
        button.setBackground(background);
        button.setPadding(dp(context, 2), 0, dp(context, 2), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(context, 38), 1f);
        params.setMargins(dp(context, 1), dp(context, 2), dp(context, 1), dp(context, 4));
        parent.addView(button, params);
        return button;
    }

    public static Button terminalButton(Context context, LinearLayout parent, String label, int accent, boolean darkText, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(10);
        button.setAllCaps(true);
        button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        button.setTextColor(darkText ? Color.BLACK : TEXT);
        button.setOnClickListener(listener);
        GradientDrawable background = new GradientDrawable();
        background.setColor(accent);
        background.setCornerRadius(dp(context, 1));
        background.setStroke(dp(context, 1), LINE);
        button.setBackground(background);
        button.setPadding(dp(context, 2), 0, dp(context, 2), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(context, 43), 1f);
        params.setMargins(dp(context, 1), dp(context, 1), dp(context, 1), dp(context, 1));
        parent.addView(button, params);
        return button;
    }

}
