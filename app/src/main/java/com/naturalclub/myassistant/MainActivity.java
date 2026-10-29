package com.naturalclub.myassistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView web;
    private SpeechRecognizer speechRecognizer;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    public void startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
            web.evaluateJavascript("setStatus('Microphone permission is required. Tap Voice Command again after allowing it.');", null);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            web.evaluateJavascript("setStatus('Voice recognition is not available on this phone.');", null);
            return;
        }

        if (speechRecognizer != null) speechRecognizer.destroy();
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { setStatus("Listening… speak now"); }
            @Override public void onBeginningOfSpeech() { setStatus("Listening…"); }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { setStatus("Processing…"); }
            @Override public void onError(int error) { setStatus("I couldn't hear that. Tap Voice Command and try again."); }
            @Override public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches == null || matches.isEmpty()) { setStatus("I didn't catch the command."); return; }
                String command = matches.get(0).toLowerCase(Locale.ROOT).trim();
                setStatus("Heard: " + command);
                handleCommand(command);
            }
            @Override public void onPartialResults(Bundle partialResults) { }
            @Override public void onEvent(int eventType, Bundle params) { }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        speechRecognizer.startListening(intent);
    }

    private void handleCommand(String command) {
        String normalized = command.replace("open ", "").replace("launch ", "").trim();
        if (normalized.contains("whatsapp")) {
            launchApp("com.whatsapp", "WhatsApp");
        } else if (normalized.contains("youtube")) {
            launchApp("com.google.android.youtube", "YouTube");
        } else if (normalized.contains("chrome")) {
            launchApp("com.android.chrome", "Chrome");
        } else {
            setStatus("I heard: " + command + ". Try: Open WhatsApp, Open YouTube, or Open Chrome.");
        }
    }

    private void launchApp(String packageName, String appName) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            setStatus(appName + " is not installed on this phone.");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
        setStatus("Opening " + appName + "…");
    }

    private void setStatus(String message) {
        if (web != null) {
            String safe = message.replace("\\", "\\\\").replace("'", "\\'");
            web.post(() -> web.evaluateJavascript("setStatus('" + safe + "');", null));
        }
    }

    @Override protected void onDestroy() {
        if (speechRecognizer != null) speechRecognizer.destroy();
        super.onDestroy();
    }

    public class Bridge {
        @JavascriptInterface public void startVoice() { MainActivity.this.startVoice(); }
    }
}
