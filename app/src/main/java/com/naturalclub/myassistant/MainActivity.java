package com.naturalclub.myassistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int MIC_REQUEST = 10;
    private static final int VOICE_ACTIVITY_REQUEST = 20;
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
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
            setStatus("Allow Microphone, then I will listen.", false);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            startVoiceActivityFallback(true);
            return;
        }
        try {
            if (speechRecognizer != null) { speechRecognizer.cancel(); speechRecognizer.destroy(); speechRecognizer = null; }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p) { setStatus("Listening… speak now", false); }
                @Override public void onBeginningOfSpeech() { setStatus("Listening…", false); }
                @Override public void onRmsChanged(float r) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { setStatus("Processing…", false); }
                @Override public void onError(int e) {
                    if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        setStatus("I did not hear a command. Tap again and speak clearly.", false);
                    } else if (e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                        setStatus("Offline speech is not available. Trying online voice input…", false);
                        startVoiceActivityFallback(false);
                    } else if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        setStatus("Microphone permission is missing.", true);
                    } else {
                        setStatus("Voice service had a problem. Trying phone voice input…", false);
                        startVoiceActivityFallback(false);
                    }
                }
                @Override public void onResults(Bundle r) { handleResults(r); }
                @Override public void onPartialResults(Bundle p) { }
                @Override public void onEvent(int e, Bundle p) { }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say: Open WhatsApp");
            speechRecognizer.startListening(i);
        } catch (Exception e) {
            startVoiceActivityFallback(true);
        }
    }

    private void startVoiceActivityFallback(boolean preferOffline) {
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline);
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, preferOffline ? "Speak your command (offline if supported)" : "Speak your command");
            startActivityForResult(i, VOICE_ACTIVITY_REQUEST);
        } catch (Exception e) {
            setStatus("Voice input is not available. Install/update Google speech services and allow Microphone.", true);
        }
    }

    private void handleResults(Bundle r) {
        ArrayList<String> matches = r == null ? null : r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) { setStatus("No command recognized. Try again.", false); return; }
        handleRecognizedText(matches.get(0));
    }

    private void handleRecognizedText(String text) {
        if (text == null || text.trim().isEmpty()) { setStatus("No command recognized. Try again.", false); return; }
        String command = text.toLowerCase(Locale.ROOT).trim();
        setStatus("Heard: " + command, false);
        handleCommand(command);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_ACTIVITY_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (results != null && !results.isEmpty()) handleRecognizedText(results.get(0));
                else setStatus("No command recognized. Try again.", false);
            } else {
                setStatus("Voice input cancelled. Tap Voice Command and speak.", false);
            }
        }
    }

    private void handleCommand(String command) {
        String c = command.toLowerCase(Locale.ROOT).trim();
        c = c.replaceFirst("^(open|launch|start)\\s+", "");
        c = c.replaceFirst("\\s+(khol|kholo|open)$", "").trim();
        if (c.equals("whats app")) c = "whatsapp";

        List<ResolveInfo> apps = getLaunchableApps();
        String compact = normalize(c);
        ResolveInfo best = null;
        int bestScore = 0;

        for (ResolveInfo info : apps) {
            String label = info.loadLabel(getPackageManager()).toString();
            String labelNorm = normalize(label);
            String pkgNorm = normalize(info.activityInfo.packageName);
            int score = 0;
            if (labelNorm.equals(compact)) score = 100;
            else if (!compact.isEmpty() && labelNorm.contains(compact)) score = 80;
            else if (!compact.isEmpty() && compact.contains(labelNorm)) score = 70;
            else if (!compact.isEmpty() && pkgNorm.contains(compact)) score = 60;
            if (score > bestScore) { bestScore = score; best = info; }
        }

        if (best != null && bestScore >= 60) {
            String name = best.loadLabel(getPackageManager()).toString();
            launchResolvedApp(best, name);
        } else {
            setStatus("I heard: " + command + ". That app was not found. Say: Open + app name.", false);
        }
    }

    private List<ResolveInfo> getLaunchableApps() {
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        return getPackageManager().queryIntentActivities(launcher, PackageManager.MATCH_ALL);
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private void launchResolvedApp(ResolveInfo info, String name) {
        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.addCategory(Intent.CATEGORY_LAUNCHER);
        launch.setPackage(info.activityInfo.packageName);
        launch.setClassName(info.activityInfo.packageName, info.activityInfo.name);
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
        if (requestCode == MIC_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            setStatus("Microphone allowed. Listening…", false);
            startVoiceOnUiThread();
        } else if (requestCode == MIC_REQUEST) setStatus("Microphone permission denied. Allow it in Settings.", true);
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