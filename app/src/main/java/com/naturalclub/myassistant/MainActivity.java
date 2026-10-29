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

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    public void startVoice() {
        runOnUiThread(this::startVoiceOnUiThread);
    }

    private void startVoiceOnUiThread() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
            setStatus("Allow microphone permission, then tap Voice Command again.");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Speech recognition is unavailable. Enable Google speech services on your phone.");
            return;
        }
        try {
            if (speechRecognizer != null) {
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p) { setStatus("Listening… speak now"); }
                @Override public void onBeginningOfSpeech() { setStatus("Listening…"); }
                @Override public void onRmsChanged(float r) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { setStatus("Processing…"); }
                @Override public void onError(int e) {
                    String msg;
                    switch (e) {
                        case SpeechRecognizer.ERROR_AUDIO: msg = "Microphone audio error."; break;
                        case SpeechRecognizer.ERROR_CLIENT: msg = "Speech service stopped. Tap again."; break;
                        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: msg = "Microphone permission is missing."; break;
                        case SpeechRecognizer.ERROR_NETWORK:
                        case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: msg = "Speech service network error. Check internet."; break;
                        case SpeechRecognizer.ERROR_NO_MATCH: msg = "No command recognized. Say Open WhatsApp clearly."; break;
                        case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: msg = "Speech service busy. Tap again."; break;
                        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: msg = "No speech heard. Tap and speak right away."; break;
                        default: msg = "Voice recognition error: " + e;
                    }
                    setStatus(msg);
                }
                @Override public void onResults(Bundle r) {
                    ArrayList<String> matches = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches == null || matches.isEmpty()) { setStatus("No command recognized. Try again."); return; }
                    String command = matches.get(0).toLowerCase(Locale.ROOT).trim();
                    setStatus("Heard: " + command);
                    handleCommand(command);
                }
                @Override public void onPartialResults(Bundle p) { }
                @Override public void onEvent(int e, Bundle p) { }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            speechRecognizer.startListening(i);
            setStatus("Starting voice command…");
        } catch (Exception e) {
            setStatus("Could not start speech recognition. Check Google speech service.");
        }
    }

    private void handleCommand(String command) {
        String normalized = command.replace("open ", "").replace("launch ", "").trim();
        if (normalized.contains("whatsapp")) launchApp("com.whatsapp", "WhatsApp");
        else if (normalized.contains("youtube")) launchApp("com.google.android.youtube", "YouTube");
        else if (normalized.contains("chrome")) launchApp("com.android.chrome", "Chrome");
        else setStatus("Heard: " + command + ". Try Open WhatsApp, Open YouTube, or Open Chrome.");
    }

    private void launchApp(String pkg, String name) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) { setStatus(name + " is not installed or cannot be opened."); return; }
        try { startActivity(launch); }
        catch (Exception e) { setStatus("Could not open " + name + "."); }
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
        @JavascriptInterface public void startVoice() {
            MainActivity.this.runOnUiThread(MainActivity.this::startVoiceOnUiThread);
        }
    }
}