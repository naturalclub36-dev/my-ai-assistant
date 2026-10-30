package com.naturalclub.myassistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final int MIC_REQUEST = 10;
    private WebView web;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    public void activateSundri() {
        runOnUiThread(() -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
                setStatus("Microphone permission required. Allow it first.", true);
                return;
            }
            startAssistantService();
        });
    }

    private void startAssistantService() {
        try {
            Intent i = new Intent(this, AssistantService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            setStatus("Sundri active — say “Sundri” anytime.", false);
        } catch (Exception e) {
            setStatus("Could not start Sundri. Allow microphone and try again.", true);
        }
    }

    public void stopSundri() {
        try { stopService(new Intent(this, AssistantService.class)); }
        catch (Exception ignored) { }
        setStatus("Sundri is sleeping.", false);
    }

    public void openSpeechSetup() {
        runOnUiThread(() -> {
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:com.google.android.googlequicksearchbox"));
                startActivity(i);
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { }
            }
        });
    }

    private void setStatus(String message, boolean showSetup) {
        if (web != null) {
            String safe = message.replace("\\", "\\\\").replace("'", "\\'");
            web.post(() -> web.evaluateJavascript("setStatus('" + safe + "'," + showSetup + ");", null));
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == MIC_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startAssistantService();
        } else if (requestCode == MIC_REQUEST) {
            setStatus("Microphone permission denied. Allow Microphone in Settings.", true);
        }
    }

    public class Bridge {
        @JavascriptInterface public void activateSundri() { MainActivity.this.activateSundri(); }
        @JavascriptInterface public void stopSundri() { MainActivity.this.stopSundri(); }
        @JavascriptInterface public void openSpeechSetup() { MainActivity.this.openSpeechSetup(); }
    }
}