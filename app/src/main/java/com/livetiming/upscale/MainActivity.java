package com.livetiming.upscale;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Map<String, String> proxyMap = new HashMap<>();
    private final AtomicBoolean ytdlpReady = new AtomicBoolean(false);
    private final Object ytdlpLock = new Object();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        io.execute(() -> {
            try {
                ensureYtdlpInitialized();
                runOnUiThread(() -> toast("yt-dlp ready — YouTube enabled"));
            } catch (Exception e) {
                runOnUiThread(() -> toast("yt-dlp init: " + e.getMessage()));
            }
        });

        webView = findViewById(R.id.webview);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " LiveTimingUpscale/2.1");
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        webView.addJavascriptInterface(new WebAppBridge(this), "AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                handleShareIntent(getIntent());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (u.contains("livetiming.local/proxy/")) {
                    String key = u.substring(u.indexOf("/proxy/") + "/proxy/".length());
                    String real;
                    synchronized (proxyMap) {
                        real = proxyMap.get(key);
                    }
                    if (real != null) {
                        WebResourceResponse r = fetchProxied(real, request);
                        if (r != null) return r;
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    void ensureYtdlpInitialized() throws YoutubeDLException {
        synchronized (ytdlpLock) {
            if (ytdlpReady.get()) return;
            YoutubeDL.getInstance().init(this);
            try {
                FFmpeg.getInstance().init(this);
            } catch (Exception ignored) {}
            try {
                YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel._STABLE);
            } catch (Exception ignored) {}
            ytdlpReady.set(true);
        }
    }

    boolean isYtdlpReady() {
        return ytdlpReady.get();
    }

    WebResourceResponse fetchProxied(String realUrl, WebResourceRequest request) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(realUrl).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Referer", "https://www.youtube.com/");
            conn.setRequestProperty("Origin", "https://www.youtube.com");
            String range = request.getRequestHeaders().get("Range");
            if (range != null) conn.setRequestProperty("Range", range);
            conn.connect();
            String mime = conn.getContentType();
            if (mime == null || mime.isEmpty()) mime = "video/mp4";
            if (mime.contains(";")) mime = mime.substring(0, mime.indexOf(';')).trim();

            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges");
            copyHdr(conn, headers, "Content-Length");
            copyHdr(conn, headers, "Content-Range");
            copyHdr(conn, headers, "Accept-Ranges");

            InputStream in = conn.getInputStream();
            int code = conn.getResponseCode();
            String reason = conn.getResponseMessage() != null ? conn.getResponseMessage() : "OK";
            return new WebResourceResponse(mime, "UTF-8", code, reason, headers, in);
        } catch (Exception e) {
            if (conn != null) conn.disconnect();
            return null;
        }
    }

    private static void copyHdr(HttpURLConnection conn, Map<String, String> headers, String name) {
        String v = conn.getHeaderField(name);
        if (v != null) headers.put(name, v);
    }

    String registerProxy(String realUrl) {
        String key = Integer.toHexString(realUrl.hashCode()) + "_" +
                Integer.toHexString((int) (System.currentTimeMillis() & 0xffff)) + ".mp4";
        synchronized (proxyMap) {
            proxyMap.put(key, realUrl);
        }
        return "http://livetiming.local/proxy/" + key;
    }

    void evalJs(String js) {
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    ExecutorService io() {
        return io;
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        String url = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            url = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            url = intent.getData().toString();
        }
        if (url != null && (url.contains("youtube.com") || url.contains("youtu.be"))) {
            final String u = url.trim().split("\\s+")[0];
            evalJs("window.__androidLoadYoutube && window.__androidLoadYoutube(" + jsonStr(u) + ")");
        }
    }

    static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
