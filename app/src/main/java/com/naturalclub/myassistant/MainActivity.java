package com.naturalclub.myassistant;
import android.Manifest;import android.app.*;import android.content.*;import android.os.*;import android.provider.Settings;import android.webkit.*;
public class MainActivity extends Activity{
 WebView web;
 @Override public void onCreate(Bundle b){super.onCreate(b);web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.addJavascriptInterface(new Bridge(this),"Android");web.loadUrl("file:///android_asset/index.html");setContentView(web);requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10);}
 void accessibility(){startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
 public static class Bridge{MainActivity a;Bridge(MainActivity x){a=x;}@JavascriptInterface public void openAccessibility(){a.accessibility();}@JavascriptInterface public boolean accessibilityEnabled(){return MyAccessibilityService.instance!=null;}}
}
