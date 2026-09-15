package com.termux.boot;

import android.os.Bundle;
import android.webkit.WebView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Explains what {@code ~/.termux/boot/} does.
 *
 * <p>In upstream termux-boot this was the add-on's whole launcher activity, and starting it once
 * was what taught Android the app was allowed to run at boot. Mayonaka is a single app that is
 * launched anyway, so there is nothing to bootstrap here: this is now a plain in-app screen,
 * reached from Settings, with no launcher entry of its own.
 */
public class BootActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView webView = new WebView(this);
        // The page paints its own midnight background; without this the WebView flashes white
        // while it loads.
        webView.setBackgroundColor(getResources().getColor(com.termux.R.color.mayonaka_background));
        webView.loadUrl("file:///android_asset/boot/overview.html");
        setContentView(webView);
    }
}
