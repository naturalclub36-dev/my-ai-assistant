package com.naturalclub.myassistant;
import android.accessibilityservice.AccessibilityService;import android.content.*;import android.view.accessibility.*;import java.util.*;
public class MyAccessibilityService extends AccessibilityService{
 public static MyAccessibilityService instance;
 public void onServiceConnected(){instance=this;} public void onDestroy(){instance=null;super.onDestroy();}
 public void onAccessibilityEvent(AccessibilityEvent e){} public void onInterrupt(){}
 public static void handleVoiceCommand(String cmd){if(instance!=null)instance.execute(cmd.toLowerCase(Locale.ROOT));}
 void execute(String cmd){if(cmd.contains("whatsapp"))open("com.whatsapp");else if(cmd.contains("youtube"))open("com.google.android.youtube");else if(cmd.contains("chrome"))open("com.android.chrome");}
 void open(String p){Intent i=getPackageManager().getLaunchIntentForPackage(p);if(i!=null){i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);}}
}
