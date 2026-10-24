package com.melodia.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.JsResult;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity implements MusicService.PlaybackListener {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQ_CODE = 101;
    private static final int DOWNLOAD_DIRECTORY_REQ_CODE = 102;

    private WebView webView;
    private MusicService musicService;
    private boolean isBound = false;
    private MelodiaBridge bridge;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            MusicService.LocalBinder localBinder = (MusicService.LocalBinder) binder;
            musicService = localBinder.getService();
            musicService.setListener(MainActivity.this);
            isBound = true;
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.evaluateJavascript("if (window.Player) window.Player.poll();", null);
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            musicService = null;
            isBound = false;
        }
    };

    public MusicService getMusicService() {
        return musicService;
    }

    public WebView getWebView() {
        return webView;
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Make window edge-to-edge dark
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        webView = new WebView(this);
        WebView.setWebContentsDebuggingEnabled(true);
        webView.setBackgroundColor(Color.parseColor("#08080A"));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        bridge = new MelodiaBridge(this);
        webView.addJavascriptInterface(bridge, "AndroidBridge");

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setNegativeButton("Annuler", (dialog, which) -> result.cancel())
                        .setPositiveButton("Supprimer", (dialog, which) -> result.confirm())
                        .setOnCancelListener(dialog -> result.cancel())
                        .show();
                return true;
            }
        });

        // Start and bind the background music service
        Intent serviceIntent = new Intent(this, MusicService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // Request runtime permissions if needed
        checkAndRequestPermissions();

        // Load the mobile optimized frontend from assets
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQ_CODE);
            }
        }
    }

    public void pushDownloadProgress(boolean active, int pct, String title, String artist, String status, String errorReason) {
        runOnUiThread(() -> {
            if (webView != null) {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("active", active);
                    obj.put("pct", pct);
                    obj.put("title", title);
                    obj.put("artist", artist);
                    obj.put("status", status != null ? status : "");
                    obj.put("errorReason", errorReason != null ? errorReason : "");
                    webView.evaluateJavascript("if (window.updateDownloadProgress) updateDownloadProgress(" + obj.toString() + ");", null);
                } catch (Exception ignored) {}
            }
        });
    }

    public void openDownloadDirectoryPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, DOWNLOAD_DIRECTORY_REQ_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DOWNLOAD_DIRECTORY_REQ_CODE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(data.getData(), flags);
                if (bridge != null) bridge.onDownloadDirectorySelected(data.getData().toString());
            } catch (Exception e) {
                pushDownloadProgress(false, 0, "", "", "Impossible d’utiliser ce dossier", e.getMessage());
            }
        }
    }

    @Override
    public void onPlaybackStateChanged() {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("if (window.Player) window.Player.poll();", null);
            }
        });
    }

    @Override
    public void onTrackChanged(MusicService.Track track) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("if (window.Player) window.Player.poll();", null);
            }
        });
    }

    @Override
    public void onLyricsUpdated(String lyrics) {
        runOnUiThread(() -> {
            if (webView != null) {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("lyrics", lyrics);
                    webView.evaluateJavascript("if (window.updateLyrics) updateLyrics(" + JSONObject.quote(lyrics) + ");", null);
                } catch (Exception ignored) {}
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            // Check if Now Playing sheet is open in JS
            webView.evaluateJavascript(
                    "(function() { " +
                    "  const np = document.getElementById('np-sheet'); " +
                    "  if (np && np.classList.contains('show')) { np.classList.remove('show'); return true; } " +
                    "  const modal = document.querySelector('.modal-backdrop.show'); " +
                    "  if (modal) { modal.classList.remove('show'); return true; } " +
                    "  return false; " +
                    "})()",
                    value -> {
                        if (!"true".equals(value)) {
                            // Minimize app to home screen without stopping audio
                            stopService(new Intent(MainActivity.this, MusicService.class));
                            finishAndRemoveTask();
                        }
                    }
            );
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}
