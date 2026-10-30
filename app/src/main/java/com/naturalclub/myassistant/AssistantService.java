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
import androidx.core.app.NotificationCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AssistantService extends Service {
    private static final String CHANNEL_ID = "sundri_assistant";
    private static final int NOTIFICATION_ID = 1001;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean running;
    private boolean waitingForCommand;
    private boolean speaking;
    private int retryDelay = 700;

    private final Runnable listenRunnable = this::startListening;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                int language = tts.setLanguage(new Locale("en", "IN"));
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
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
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN");
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
                speak("Ji, boliye.");
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

    private void executeCommand(String command) {
        String c = command.toLowerCase(Locale.ROOT).trim();
        c = c.replaceFirst("^(open|launch|start|use|go to|please)\\s+", "");
        c = c.replaceFirst("\\s+(khol|kholo|open)$", "").trim();
        c = c.replace("whats app", "whatsapp").replace("what's app", "whatsapp");
        c = c.replace("insta gram", "instagram").replace("face book", "facebook");
        c = c.replace("google chrome", "chrome").replace("chrome browser", "chrome");
        c = c.replace("you tube", "youtube");
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
            else if (!compact.isEmpty() && labelNorm.contains(compact)) score = 85;
            else if (!compact.isEmpty() && compact.contains(labelNorm)) score = 75;
            else if (!compact.isEmpty() && pkgNorm.contains(compact)) score = 65;
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
                speak("Ji, " + name + " khol rahi hoon.");
            } catch (Exception e) {
                speak("Maazrat, " + name + " nahi khul saka.");
            }
        } else {
            speak("Maazrat, " + command + " app nahi mili. App ka naam dobara boliye.");
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
            if (tts.getVoices() == null) return;
            for (Voice voice : tts.getVoices()) {
                String n = voice.getName().toLowerCase(Locale.ROOT);
                if ((n.contains("female") || n.contains("woman")) && voice.getLocale().getLanguage().equals("en")) {
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
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}