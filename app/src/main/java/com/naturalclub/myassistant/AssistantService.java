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
import android.os.IBinder;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import androidx.core.app.NotificationCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AssistantService extends Service {
    private static final String CHANNEL_ID = "sundri_assistant";
    private static final int NOTIFICATION_ID = 1001;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean running;
    private boolean waitingForCommand;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                chooseFemaleVoice();
            }
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        running = true;
        startListening();
        return START_STICKY;
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Sundri Assistant", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps Sundri ready for the wake word and voice commands.");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Sundri is listening")
                .setContentText("Say “Sundri” to wake your AI assistant")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void startListening() {
        if (!running) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf();
            return;
        }
        try {
            if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); }
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onError(int error) {\n                    // Keep the foreground listener alive; online recognition is enabled below.\n                    restartSoon();\n                }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty()) processSpeech(list.get(0));
                    else restartSoon();
                }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
            recognizer.startListening(i);
        } catch (Exception e) { restartSoon(); }
    }

    private void restartSoon() {
        if (!running) return;
        new android.os.Handler(getMainLooper()).postDelayed(this::startListening, 350);
    }

    private void processSpeech(String heard) {
        String text = heard == null ? "" : heard.toLowerCase(Locale.ROOT).trim();
        String clean = text.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        boolean wake = clean.equals("sundri") || clean.startsWith("sundri ") || clean.contains(" sundri ") || clean.equals("sundry") || clean.startsWith("sundry ") || clean.contains(" sundry ") || clean.equals("sundari") || clean.startsWith("sundari ") || clean.contains(" sundari ") || clean.equals("sun dry") || clean.startsWith("sun dry ");

        if (!waitingForCommand && !wake) {
            restartSoon();
            return;
        }

        if (wake) {
            String command = clean.replaceFirst("^(sundri|sundry|sundari|sun dry)\\s*", "").trim();
            if (command.isEmpty()) {
                waitingForCommand = true;
                speak("Ji, boliye.");
                restartSoon();
                return;
            }
            waitingForCommand = false;
            executeCommand(command);
            restartSoon();
            return;
        }

        if (waitingForCommand) {
            waitingForCommand = false;
            executeCommand(clean);
            restartSoon();
        }
    }

    private void executeCommand(String command) {
        String c = command.toLowerCase(Locale.ROOT).trim();
        c = c.replaceFirst("^(open|launch|start|use|go to|please)\\s+", "");
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
            speak("Opening " + name);
            try {
                Intent launch = new Intent(Intent.ACTION_MAIN);
                launch.addCategory(Intent.CATEGORY_LAUNCHER);
                launch.setPackage(best.activityInfo.packageName);
                launch.setClassName(best.activityInfo.packageName, best.activityInfo.name);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(launch);
            } catch (Exception e) { speak("I could not open " + name + "."); }
        } else {
            speak("Sorry, I could not find " + command + ".");
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

    private void chooseFemaleVoice() {
        if (tts == null) return;
        try {
            for (Voice voice : tts.getVoices()) {
                String n = voice.getName().toLowerCase(Locale.ROOT);
                if (n.contains("female") && voice.getLocale().getLanguage().equals("en")) {
                    tts.setVoice(voice);
                    return;
                }
            }
        } catch (Exception ignored) { }
    }

    private void speak(String text) {
        if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sundri");
    }

    public void stopAssistant() {
        running = false;
        waitingForCommand = false;
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; }
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        running = false;
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); }
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}