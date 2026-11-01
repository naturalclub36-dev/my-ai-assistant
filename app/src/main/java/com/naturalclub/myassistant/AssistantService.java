package com.naturalclub.myassistant;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import androidx.core.app.NotificationCompat;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AssistantService extends Service {
    private static final String CHANNEL_ID = "sundri_assistant";
    private static final int NOTIFICATION_ID = 1001;
    private static final String AI_ENDPOINT = "https://sundri-ai-brain.naturalclub36.workers.dev/chat";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private OverlayController overlay;
    private boolean running;
    private boolean waitingForCommand;
    private boolean speaking;
    private boolean english;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        overlay = new OverlayController(this);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                tts.setLanguage(new Locale("ur", "PK"));
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { resumeListening(); }
                    @Override public void onError(String id) { resumeListening(); }
                    @Override public void onStop(String id, boolean interrupted) { resumeListening(); }
                });
            }
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY;
        }
        running = true;
        startForeground(NOTIFICATION_ID, buildNotification("Sundri active"));
        show("Ready", "Say “Sundri”");
        scheduleListen(400);
        return START_STICKY;
    }

    private void createChannel() {
        NotificationChannel c = new NotificationChannel(CHANNEL_ID, "Sundri Assistant", NotificationManager.IMPORTANCE_LOW);
        NotificationManager n = getSystemService(NotificationManager.class);
        if (n != null) n.createNotificationChannel(c);
    }

    private Notification buildNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent p = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Sundri").setContentText(text).setOngoing(true).setContentIntent(p).build();
    }

    private void notify(String text) {
        NotificationManager n = getSystemService(NotificationManager.class);
        if (n != null && running) n.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void show(String state, String text) {
        if (overlay != null) overlay.show(state, text);
        notify(text);
    }

    private void scheduleListen(long ms) {
        if (!running || speaking) return;
        handler.removeCallbacksAndMessages("listen");
        handler.postAtTime(() -> startListening(), "listen", android.os.SystemClock.uptimeMillis() + ms);
    }

    private void startListening() {
        if (!running || speaking) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            show("Sundri", "Speech service unavailable"); scheduleListen(5000); return;
        }
        try {
            destroyRecognizer();
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) { show("Listening", waitingForCommand ? "Boliyey…" : "Say “Sundri”"); }
                @Override public void onBeginningOfSpeech() { show("Listening", "Sun rahi hoon…"); }
                @Override public void onRmsChanged(float v) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onError(int e) { destroyRecognizer(); scheduleListen(500); }
                @Override public void onResults(Bundle b) {
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    destroyRecognizer();
                    if (r == null || r.isEmpty()) { scheduleListen(400); return; }
                    String heard = r.get(0).trim(); english = !containsUrdu(heard); processSpeech(heard);
                }
                @Override public void onPartialResults(Bundle b) { }
                @Override public void onEvent(int e, Bundle b) { }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, english ? "en-IN" : "ur-PK");
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, english ? "en-IN" : "ur-PK");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            recognizer.startListening(i);
        } catch (Exception e) { destroyRecognizer(); scheduleListen(1200); }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private void processSpeech(String heard) {
        String text = normalizeUrdu(heard).trim();
        String clean = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        boolean wake = clean.equals("sundri") || clean.startsWith("sundri ") || clean.contains(" sundri ")
                || clean.equals("sundry") || clean.startsWith("sundry ") || clean.equals("sundari")
                || clean.startsWith("sundari ") || clean.equals("sun dry") || clean.startsWith("sun dry ")
                || clean.equals("jag jao") || clean.startsWith("jag jao ");
        if (!waitingForCommand && !wake) { scheduleListen(250); return; }
        if (wake) {
            String command = clean.replaceFirst("^(hey|hello|hi|ok|okay|please)\\s+", "")
                    .replaceFirst("^(sundri|sundry|sundari|sun dry|jag jao)\\s*", "").trim();
            if (command.isEmpty()) {
                waitingForCommand = true; show("Sundri", "Ji, boliyey"); speak("جی، بولیے۔");
            } else { waitingForCommand = false; executeCommand(command); }
            return;
        }
        waitingForCommand = false; executeCommand(clean);
    }

    private String normalizeUrdu(String s) {
        if (s == null) return "";
        return s.replace("سنڈری", "sundri ").replace("سندری", "sundri ").replace("جاگ جاؤ", "sundri ")
                .replace("جاگو", "sundri ").replace("یوٹیوب", "youtube ").replace("یو ٹیوب", "youtube ")
                .replace("واٹس ایپ", "whatsapp ").replace("واٹسایپ", "whatsapp ").replace("انسٹاگرام", "instagram ")
                .replace("فیس بک", "facebook ").replace("کروم", "chrome ").replace("کیمرہ", "camera ")
                .replace("سیٹنگز", "settings ").replace("کھولو", " ").replace("کھول دو", " ").replace("اوپن", " ");
    }

    private boolean containsUrdu(String s) { return s != null && s.matches("(?s).*[\\u0600-\\u06FF].*"); }

    private void executeCommand(String command) {
        String c = command.toLowerCase(Locale.ROOT).trim();
        if (c.equals("stop") || c.equals("sleep") || c.equals("so jao") || c.equals("band ho jao")) {
            speak("ٹھیک ہے، میں سو رہی ہوں۔"); handler.postDelayed(this::stopAssistant, 1800); return;
        }
        if (handleWhatsApp(c)) return;
        c = c.replaceFirst("^(open|launch|start|use|go to|please)\\s+", "")
                .replaceFirst("\\s+(khol|kholo|open|chalao|chala do)$", "").trim();
        c = c.replace("you tube", "youtube").replace("what's app", "whatsapp").replace("whats app", "whatsapp");
        String pkg = knownPackage(c);
        if (pkg != null && launch(pkg, c)) return;
        String target = compact(c); ResolveInfo best = null; int score = 0;
        for (ResolveInfo info : launchableApps()) {
            String label = info.loadLabel(getPackageManager()).toString(); String l = compact(label); String p = compact(info.activityInfo.packageName);
            int s = l.equals(target) ? 100 : (l.contains(target) && target.length() > 3 ? 80 : (p.contains(target) ? 70 : 0));
            if (s > score) { score = s; best = info; }
        }
        if (best != null && score >= 65) launch(best.activityInfo.packageName, best.loadLabel(getPackageManager()).toString());
        else askAI(c);
    }

    private String knownPackage(String c) {
        String x = compact(c);
        if (x.contains("whatsapp") || x.equals("wa")) return "com.whatsapp";
        if (x.contains("youtube") || x.equals("yt")) return "com.google.android.youtube";
        if (x.contains("instagram") || x.equals("insta")) return "com.instagram.android";
        if (x.contains("facebook") || x.equals("fb")) return "com.facebook.katana";
        if (x.contains("chrome") || x.contains("browser")) return "com.android.chrome";
        if (x.equals("maps") || x.equals("map")) return "com.google.android.apps.maps";
        if (x.equals("camera")) return "com.oppo.camera";
        if (x.equals("settings")) return "com.android.settings";
        if (x.equals("spotify")) return "com.spotify.music";
        if (x.equals("tiktok")) return "com.zhiliaoapp.musically";
        return null;
    }

    private boolean launch(String pkg, String name) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg); if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED); startActivity(i);
            show("Sundri", "✓ " + name + " khol diya"); speak("جی، " + name + " کھول رہی ہوں۔"); return true;
        } catch (Exception e) { speak("معذرت، یہ app نہیں کھل سکی۔"); return false; }
    }

    private boolean handleWhatsApp(String c) {
        if (!c.contains("whatsapp") || !(c.contains("message") || c.contains("msg") || c.contains("bhejo") || c.contains("send"))) return false;
        String msg = c.replaceFirst(".*?(message|msg)\\s*", "").replaceFirst("^(bhejo|send|karo)\\s*", "").trim();
        if (msg.isEmpty()) { speak("Message ke alfaaz boliyey."); return true; }
        try {
            Intent i = new Intent(Intent.ACTION_SEND); i.setType("text/plain"); i.putExtra(Intent.EXTRA_TEXT, msg); i.setPackage("com.whatsapp"); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i);
            show("WhatsApp", "Message ready — Send dabana hai"); speak("Message tayyar hai. WhatsApp mein contact select karke Send dabayein.");
        } catch (Exception e) { speak("WhatsApp open nahi ho saka."); }
        return true;
    }

    private void askAI(String q) {
        speaking = true; show("Thinking", "Sundri soch rahi hai…");
        aiExecutor.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(AI_ENDPOINT).openConnection(); c.setRequestMethod("POST"); c.setConnectTimeout(8000); c.setReadTimeout(15000); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                JSONObject p = new JSONObject(); p.put("message", q);
                try (OutputStream o = c.getOutputStream()) { o.write(p.toString().getBytes(StandardCharsets.UTF_8)); }
                int code = c.getResponseCode(); InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream(); StringBuilder s = new StringBuilder();
                if (in != null) try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) { String line; while ((line=r.readLine())!=null) s.append(line); }
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
                String answer = new JSONObject(s.toString()).optString("reply", "").trim(); if (answer.isEmpty()) throw new Exception("empty");
                handler.post(() -> speak(answer));
            } catch (Exception e) { handler.post(() -> speak("Abhi AI se jawab nahi aa saka. Local commands phir bhi chal sakti hain.")); }
            finally { if (c != null) c.disconnect(); }
        });
    }

    private List<ResolveInfo> launchableApps() { Intent i = new Intent(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_LAUNCHER); return getPackageManager().queryIntentActivities(i, PackageManager.MATCH_ALL); }
    private String compact(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

    private void speak(String text) {
        if (tts == null || !running) return; speaking = true; destroyRecognizer(); show("Sundri", text);
        Locale l = containsUrdu(text) ? new Locale("ur", "PK") : new Locale("en", "IN"); int result = tts.setLanguage(l); if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US);
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sundri"); if (r == TextToSpeech.ERROR) resumeListening();
    }

    private void resumeListening() { handler.post(() -> { speaking = false; if (!running) return; show("Ready", waitingForCommand ? "Boliyey…" : "Say “Sundri”"); scheduleListen(600); }); }

    public void stopAssistant() { running = false; waitingForCommand = false; speaking = false; handler.removeCallbacksAndMessages(null); destroyRecognizer(); if (overlay != null) overlay.hide(); stopForeground(true); stopSelf(); }

    @Override public void onDestroy() { running = false; handler.removeCallbacksAndMessages(null); destroyRecognizer(); if (overlay != null) overlay.hide(); if (tts != null) { tts.stop(); tts.shutdown(); } aiExecutor.shutdownNow(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}