package com.naturalclub.myassistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView web;
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech tts;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        tts = new TextToSpeech(this, status -> { if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.US); });
        web.loadUrl("file:///android_asset/index.html");
    }

    public void startVoice() { runOnUiThread(this::startVoiceOnUiThread); }

    private void startVoiceOnUiThread() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
            setStatus("Microphone permission required. Allow it and I will start listening.", false);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Google speech recognition is unavailable. Tap Speech Setup below.", true);
            return;
        }
        try {
            if (speechRecognizer != null) { speechRecognizer.cancel(); speechRecognizer.destroy(); }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p) { setStatus("Listening… speak now", false); }
                @Override public void onBeginningOfSpeech() { setStatus("Listening…", false); }
                @Override public void onRmsChanged(float r) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { setStatus("Processing…", false); }
                @Override public void onError(int e) {
                    String msg;
                    switch (e) {
                        case SpeechRecognizer.ERROR_AUDIO: msg = "Microphone audio error. Check microphone permission."; break;
                        case SpeechRecognizer.ERROR_CLIENT: msg = "Voice service stopped. Tap Voice Command again."; break;
                        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: msg = "Microphone permission is missing."; break;
                        case SpeechRecognizer.ERROR_NETWORK:
                        case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: msg = "Internet/speech service error. Check Wi‑Fi or mobile data."; break;
                        case SpeechRecognizer.ERROR_NO_MATCH: msg = "I did not understand. Say: Open WhatsApp."; break;
                        case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: msg = "Voice service is busy. Tap again in a moment."; break;
                        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: msg = "I did not hear you. Tap and speak immediately."; break;
                        default: msg = "Voice recognition error. Tap Voice Command again.";
                    }
                    setStatus(msg, false);
                }
                @Override public void onResults(Bundle r) {
                    ArrayList<String> matches = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches == null || matches.isEmpty()) { setStatus("No command recognized. Try again.", false); return; }
                    String command = matches.get(0).toLowerCase(Locale.ROOT).trim();
                    setStatus("Heard: " + command, false);
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
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            speechRecognizer.startListening(i);
        } catch (Exception e) {
            setStatus("Could not start voice recognition. Use Speech Setup and try again.", true);
        }
    }

    private void handleCommand(String command) {
        String c = command.replace("open ", "").replace("launch ", "").trim();
        if (c.contains("whatsapp") || c.contains("whats app")) launchApp("com.whatsapp", "WhatsApp");
        else if (c.contains("youtube") || c.contains("you tube")) launchApp("com.google.android.youtube", "YouTube");
        else if (c.contains("chrome") || c.contains("browser")) launchApp("com.android.chrome", "Chrome");
        else if (c.contains("whatsapp kholo") || c.contains("whatsapp khol")) launchApp("com.whatsapp", "WhatsApp");
        else if (c.contains("youtube kholo") || c.contains("youtube khol")) launchApp("com.google.android.youtube", "YouTube");
        else if (c.contains("chrome kholo") || c.contains("chrome khol")) launchApp("com.android.chrome", "Chrome");
        else { setStatus("I heard: " + command + ". Try WhatsApp, YouTube, or Chrome.", false); }
    }

    private void launchApp(String pkg, String name) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) { setStatus(name + " is not installed on this phone.", true); return; }
        speak("Opening " + name);
        try { startActivity(launch); } catch (Exception e) { setStatus("Could not open " + name + ".", true); }
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

    private void speak(String message) {
        if (tts != null) tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "assistant");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 10 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            setStatus("Microphone allowed. Listening…", false);
            startVoiceOnUiThread();
        } else if (requestCode == 10) {
            setStatus("Microphone permission was denied. Allow Microphone in Settings.", true);
        }
    }

    @Override protected void onDestroy() {
        if (speechRecognizer != null) { speechRecognizer.cancel(); speechRecognizer.destroy(); }
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    public class Bridge {
        @JavascriptInterface public void startVoice() { MainActivity.this.runOnUiThread(MainActivity.this::startVoiceOnUiThread); }
        @JavascriptInterface public void openSpeechSetup() { MainActivity.this.openSpeechSetup(); }
    }
}