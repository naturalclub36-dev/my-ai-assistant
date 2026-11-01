package com.naturalclub.myassistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public class OverlayController {
    private final Context context;
    private final WindowManager windowManager;
    private LinearLayout panel;
    private TextView title;
    private TextView message;

    public OverlayController(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void show(String state, String text) {
        if (!android.provider.Settings.canDrawOverlays(context)) return;
        if (panel == null) create();
        title.setText("✨ Sundri");
        message.setText(text == null ? state : text);
        panel.setVisibility(View.VISIBLE);
        updateLayout();
    }

    public void hide() {
        if (panel != null) panel.setVisibility(View.GONE);
    }

    private void create() {
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(42, 26, 42, 28);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(24, 24, 28));
        bg.setCornerRadius(42);
        panel.setBackground(bg);
        panel.setElevation(20f);

        title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(title, new LinearLayout.LayoutParams(-1, 52));

        message = new TextView(context);
        message.setTextColor(Color.LTGRAY);
        message.setTextSize(16);
        message.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(message, new LinearLayout.LayoutParams(-1, 56));

        panel.setOnClickListener(v -> hide());
        updateLayout();
        try { windowManager.addView(panel, layoutParams()); } catch (Exception ignored) { }
    }

    private WindowManager.LayoutParams layoutParams() {
        int type = android.os.Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                -1, -2, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.y = 42;
        return p;
    }

    private void updateLayout() {
        if (panel == null) return;
        try { windowManager.updateViewLayout(panel, layoutParams()); } catch (Exception ignored) { }
    }
}
