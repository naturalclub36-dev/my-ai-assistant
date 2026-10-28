package com.naturalclub.myassistant;

import android.Manifest;import android.app.*;import android.content.*;import android.content.pm.PackageManager;import android.os.*;import android.provider.Settings;import android.webkit.*;import android.view.*;

public class MainActivity extends Activity{
 WebView web;
 @Override public void onCreate(Bundle b){super.onCreate(b);web=new WebView(this);web.setJavaScriptEnabled(true);web.addJavascriptInterface(new Bridge(this),"Android");web.loadUrl("file:///android_asset/index.html");setContentView(web);if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS},10);else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10);}
 void startWake(){Intent i=new Intent(this,WakeWordService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
 void accessibility(){startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
 public static class Bridge{MainActivity a;Bridge(MainActivity x){a=x;}@JavascriptInterface public void startWakeWord(){a.startWake();}@JavascriptInterface public void openAccessibility(){a.accessibility();}@JavascriptInterface public boolean accessibilityEnabled(){return MyAccessibilityService.instance!=null;}}
}
