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
import android.speech.tts.Voice;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.core.app.NotificationCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AssistantService extends Service {
    private static final String CHANNEL_ID = "sundri_assistant";
    private static final int NOTIFICATION_ID = 1001;
    private static final String AI_ENDPOINT = "https://sundri-ai-brain.naturalclub36.workers.dev/chat";
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean running;
    private boolean waitingForCommand;
    private boolean speaking;
    private int retryDelay = 700;
    private boolean useEnglishFallback;

    private final Runnable listenRunnable = this::startListening;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                int language = tts.setLanguage(new Locale("ur", "PK"));
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    language = tts.setLanguage(new Locale("ur", "IN"));
                }
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(new Locale("en", "IN"));
                }
                chooseFemaleVoice();
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { resumeAfterSpeech(); }
                    @Override public void onError(String id) { resumeAfterSpeech(); }
                });
            }
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification("Starting voice recognition…"));
        running = true;
        scheduleListening(300);
        return START_STICKY;
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Sundri Assistant", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps Sundri ready for the wake word and voice commands.");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String message) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Sundri is active")
                .setContentText(message)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String message) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && running) nm.notify(NOTIFICATION_ID, buildNotification(message));
    }

    private void scheduleListening(long delay) {
        if (!running || speaking) return;
        handler.removeCallbacks(listenRunnable);
        handler.postDelayed(listenRunnable, delay);
    }

    private void startListening() {
        if (!running || speaking) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech service unavailable. Check Google speech services.");
            scheduleListening(5000);
            return;
        }
        try {
            destroyRecognizer();
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    retryDelay = 700;
                    updateNotification(waitingForCommand ? "Listening for your command…" : "Say “Sundri” to wake up");
                }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onError(int error) {
                    handler.post(() -> {
                        destroyRecognizer();
                        if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE && !useEnglishFallback) {
                            useEnglishFallback = true;
                            updateNotification("Urdu speech pack unavailable; switching to English recognition.");
                            scheduleListening(700);
                            return;
                        }
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            updateNotification("Microphone permission is missing. Open Sundri and allow Microphone.");
                            stopAssistant();
                            return;
                        }
                        updateNotification("Voice listening reconnecting…");
                        long delay = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1800 : Math.max(1200, retryDelay);
                        scheduleListening(delay);
                        retryDelay = Math.min(Math.max(retryDelay, 1200) * 2, 8000);
                    });
                }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    destroyRecognizer();
                    retryDelay = 1200;
                    if (list != null && !list.isEmpty()) processSpeech(list.get(0));
                    else scheduleListening(1200);
                }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            String speechLanguage = useEnglishFallback ? "en-IN" : "ur-PK";
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, speechLanguage);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
            recognizer.startListening(i);
        } catch (Exception e) {
            destroyRecognizer();
            updateNotification("Could not start microphone. Retrying…");
            scheduleListening(retryDelay);
            retryDelay = Math.min(retryDelay * 2, 5000);
        }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private void processSpeech(String heard) {
        String text = heard == null ? "" : heard.toLowerCase(Locale.ROOT).trim();
        text = translateUrduSpeech(text);
        String clean = text.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        boolean wake = clean.equals("sundri") || clean.startsWith("sundri ") || clean.contains(" sundri ")
                || clean.equals("sundry") || clean.startsWith("sundry ") || clean.contains(" sundry ")
                || clean.equals("sundari") || clean.startsWith("sundari ") || clean.contains(" sundari ")
                || clean.equals("sun dry") || clean.startsWith("sun dry ");
        if (!waitingForCommand && !wake) {
            scheduleListening(250);
            return;
        }
        if (wake) {
            String command = clean.replaceFirst("^(sundri|sundry|sundari|sun dry)\\s*", "").trim();
            if (command.isEmpty()) {
                waitingForCommand = true;
                speak("جی، بولیے۔");
            } else {
                waitingForCommand = false;
                executeCommand(command);
            }
            return;
        }
        if (waitingForCommand) {
            waitingForCommand = false;
            executeCommand(clean);
        }
    }

    // Convert common Urdu-script speech results into stable command words.
    private String translateUrduSpeech(String value) {
        String s = value;
        String[][] words = {
                {"سنڈری", " sundri "}, {"سندری", " sundri "}, {"سندھری", " sundri "},
                {"واٹس ایپ", " whatsapp "}, {"واٹسایپ", " whatsapp "}, {"واٹسآپ", " whatsapp "},
                {"یوٹیوب", " youtube "}, {"یو ٹیوب", " youtube "},
                {"انسٹاگرام", " instagram "}, {"انسٹا گرام", " instagram "},
                {"فیس بک", " facebook "}, {"فیس بک", " facebook "},
                {"گوگل کروم", " chrome "}, {"کروم", " chrome "},
                {"کھول دو", " "}, {"کھولو", " "}, {"کھولیں", " "}, {"چلا دو", " "},
                {"چلاؤ", " "}, {"اوپن کرو", " "}, {"اوپن", " "}
        };
        for (String[] pair : words) s = s.replace(pair[0], pair[1]);
        return s;
    }

    private void executeCommand(String command) {
        String c = command.toLowerCase(Locale.ROOT).trim();
        c = c.replaceFirst("^(open|launch|start|use|go to|please|sundri)\\s+", "");
        c = c.replaceFirst("\\s+(khol|kholo|kholna|kholo na|open|please)$", "").trim();
        c = c.replace("whats app", "whatsapp").replace("what's app", "whatsapp");
        c = c.replace("insta gram", "instagram").replace("face book", "facebook");
        c = c.replace("google chrome", "chrome").replace("chrome browser", "chrome");
        c = c.replace("you tube", "youtube").replace("watsapp", "whatsapp")
                .replace("whatapp", "whatsapp").replace("insta", "instagram")
                .replace("fb", "facebook");
        List<ResolveInfo> apps = getLaunchableApps();
        String compact = normalize(c);
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo info : apps) {
            String label = info.loadLabel(getPackageManager()).toString();
            String labelNorm = normalize(label);
            String pkgNorm = normalize(info.activityInfo.packageName);
            int score = 0;
            if (labelNorm.equals(compact)) score = 110;
            else if (!compact.isEmpty() && pkgNorm.contains(compact)) score = 100;
            else if (!compact.isEmpty() && labelNorm.contains(compact)) score = 90;
            else if (!compact.isEmpty() && compact.contains(labelNorm) && labelNorm.length() >= 4) score = 75;
            if (compact.equals("wa") || compact.equals("watsapp")) {
                if (pkgNorm.contains("whatsapp")) score = 120;
            }
            if (compact.equals("yt") || compact.equals("youtub")) {
                if (pkgNorm.contains("youtube")) score = 120;
            }
            if (score > bestScore) { bestScore = score; best = info; }
        }
        if (best != null && bestScore >= 65) {
            String name = best.loadLabel(getPackageManager()).toString();
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(best.activityInfo.packageName);
                if (launch == null) {
                    launch = new Intent(Intent.ACTION_MAIN);
                    launch.addCategory(Intent.CATEGORY_LAUNCHER);
                    launch.setClassName(best.activityInfo.packageName, best.activityInfo.name);
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(launch);
                speak("جی، " + name + " کھول رہی ہوں۔");
            } catch (Exception e) {
                speak("معذرت، " + name + " نہیں کھل سکا۔");
            }
        } else {
            askSundriAI(command);
        }
    }


    private void askSundriAI(String question) {
        if (question == null || question.trim().isEmpty()) {
            speak("جی، اپنا سوال دوبارہ بتائیں۔");
            return;
        }
        speaking = true;
        handler.removeCallbacks(listenRunnable);
        destroyRecognizer();
        updateNotification("Sundri is thinking…");
        aiExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(AI_ENDPOINT);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(25000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                JSONObject payload = new JSONObject();
                payload.put("message", question);
                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }

                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                StringBuilder responseText = new StringBuilder();
                if (stream != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) responseText.append(line);
                    }
                }
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("AI server returned " + status);
                }
                JSONObject response = new JSONObject(responseText.toString());
                String answer = response.optString("reply", "").trim();
                if (answer.isEmpty()) throw new IllegalStateException("Empty AI reply");
                handler.post(() -> {
                    if (running) speak(answer);
                });
            } catch (Exception error) {
                handler.post(() -> {
                    if (running) speak("معذرت، ابھی انٹرنیٹ سے رابطہ نہیں ہو سکا۔ دوبارہ کوشش کریں۔");
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
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

    private void chooseFemaleVoice() {
        if (tts == null) return;
        try {
            if (tts.getVoices() == null) return;
            for (Voice voice : tts.getVoices()) {
                String n = voice.getName().toLowerCase(Locale.ROOT);
                if ((n.contains("female") || n.contains("woman")) && voice.getLocale().getLanguage().equals("ur")) {
                    tts.setVoice(voice);
                    return;
                }
            }
        } catch (Exception ignored) { }
    }

    private void speak(String text) {
        if (tts == null || !running) {
            scheduleListening(300);
            return;
        }
        speaking = true;
        handler.removeCallbacks(listenRunnable);
        destroyRecognizer();
        updateNotification("Sundri is replying…");
        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sundri_reply");
        if (result == TextToSpeech.ERROR) resumeAfterSpeech();
    }

    private void resumeAfterSpeech() {
        handler.post(() -> {
            speaking = false;
            if (running) {
                updateNotification(waitingForCommand ? "Listening for your command…" : "Say “Sundri” to wake up");
                scheduleListening(500);
            }
        });
    }

    public void stopAssistant() {
        running = false;
        waitingForCommand = false;
        speaking = false;
        handler.removeCallbacksAndMessages(null);
        destroyRecognizer();
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        destroyRecognizer();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        aiExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}