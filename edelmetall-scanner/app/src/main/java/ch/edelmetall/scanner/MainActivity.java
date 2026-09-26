package ch.edelmetall.scanner;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dünne Android-Hülle: oben die App-Oberfläche (assets/index.html), unten ein "Worker"-Browser,
 * der tutti.ch / Facebook wie ein normaler Handy-Browser lädt. Die Such- und Bewertungslogik läuft in JavaScript.
 */
public class MainActivity extends Activity {

    private WebView ui;
    private WebView worker;
    private LinearLayout.LayoutParams workerParams;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private SharedPreferences prefs;
    private String extractJs;

    // Aktueller Worker-Auftrag
    private String jobToken;
    private String jobMode;
    private int jobScrolls;
    private int jobSettleMs;
    private Runnable pendingInject;
    private Runnable pendingTimeout;
    private boolean workerVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("scanner", MODE_PRIVATE);
        extractJs = readAsset("extract.js");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        ui = new WebView(this);
        worker = new WebView(this);
        root.addView(ui, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        workerParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0f);
        root.addView(worker, workerParams);
        setContentView(root);

        WebSettings us = ui.getSettings();
        us.setJavaScriptEnabled(true);
        us.setDomStorageEnabled(true);
        ui.addJavascriptInterface(new UiBridge(), "Android");
        ui.setWebChromeClient(new WebChromeClient());
        ui.loadUrl("file:///android_asset/index.html");

        WebSettings ws = worker.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setMediaPlaybackRequiresUserGesture(true);
        // Normale Chrome-Kennung ohne "wv"-Markierung
        String ua = ws.getUserAgentString().replace("; wv", "").replaceAll("Version/\\d+\\.\\d+ ", "");
        ws.setUserAgentString(ua);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(worker, true);
        worker.addJavascriptInterface(new WorkerBridge(), "WorkerBridge");
        worker.setWebChromeClient(new WebChromeClient());
        worker.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                return true; // intent://, fb:// usw. nicht öffnen
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (pendingInject != null) main.removeCallbacks(pendingInject);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                scheduleInject();
            }
        });

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("scan", "Scanner", NotificationManager.IMPORTANCE_DEFAULT);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private void scheduleInject() {
        if (jobToken == null) return;
        if (pendingInject != null) main.removeCallbacks(pendingInject);
        final String token = jobToken;
        pendingInject = () -> inject(token);
        main.postDelayed(pendingInject, jobSettleMs);
    }

    private void inject(String token) {
        if (token == null || !token.equals(jobToken)) return;
        String js = extractJs
                .replace("__TOKEN__", token)
                .replace("__MODE__", jobMode)
                .replace("__SCROLLS__", String.valueOf(jobScrolls));
        worker.evaluateJavascript(js, null);
    }

    private void deliverWorker(String token, String json) {
        if (token == null || !token.equals(jobToken)) return;
        jobToken = null;
        if (pendingTimeout != null) main.removeCallbacks(pendingTimeout);
        if (pendingInject != null) main.removeCallbacks(pendingInject);
        ui.evaluateJavascript("App.onWorker(" + JSONObject.quote(token) + "," + JSONObject.quote(json) + ")", null);
    }

    private void setWorkerVisible(boolean v) {
        workerVisible = v;
        workerParams.weight = v ? 1.3f : 0f;
        worker.setLayoutParams(workerParams);
        worker.setVisibility(View.VISIBLE);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    @Override
    public void onBackPressed() {
        if (workerVisible && worker.canGoBack()) worker.goBack();
        else if (workerVisible) setWorkerVisible(false);
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    /** Vom Worker-Browser aus aufrufbar: liefert das Extraktionsergebnis. */
    private class WorkerBridge {
        @JavascriptInterface
        public void result(String token, String json) {
            main.post(() -> deliverWorker(token, json));
        }
    }

    /** Von der App-Oberfläche aus aufrufbar. */
    private class UiBridge {
        @JavascriptInterface
        public void loadInWorker(String token, String url, String mode, int scrolls, int settleMs) {
            main.post(() -> {
                jobToken = token;
                jobMode = mode;
                jobScrolls = scrolls;
                jobSettleMs = Math.max(500, settleMs);
                if (pendingTimeout != null) main.removeCallbacks(pendingTimeout);
                pendingTimeout = () -> deliverWorker(token, "{\"error\":\"timeout\"}");
                main.postDelayed(pendingTimeout, 45000 + scrolls * 2500L);
                if (url == null || url.isEmpty()) {
                    inject(token); // aktuelle Seite auswerten
                } else {
                    worker.stopLoading();
                    worker.loadUrl(url);
                }
            });
        }

        @JavascriptInterface
        public void openInWorker(String url) {
            main.post(() -> {
                jobToken = null;
                worker.loadUrl(url);
            });
        }

        @JavascriptInterface
        public void showWorker(boolean show) {
            main.post(() -> setWorkerVisible(show));
        }

        @JavascriptInterface
        public void toggleWorker() {
            main.post(() -> setWorkerVisible(!workerVisible));
        }

        @JavascriptInterface
        public void http(String token, String method, String url, String headersJson, String body) {
            io.execute(() -> {
                int code = 0;
                String resp;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod(method);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(120000);
                    c.setRequestProperty("User-Agent", "EdelmetallScanner/1.0");
                    JSONObject h = new JSONObject(headersJson == null || headersJson.isEmpty() ? "{}" : headersJson);
                    Iterator<String> keys = h.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        c.setRequestProperty(k, h.getString(k));
                    }
                    if (body != null && !body.isEmpty()) {
                        c.setDoOutput(true);
                        try (OutputStream os = c.getOutputStream()) {
                            os.write(body.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    code = c.getResponseCode();
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    resp = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
                    c.disconnect();
                } catch (Exception e) {
                    resp = String.valueOf(e.getMessage());
                }
                final int fc = code;
                final String fr = resp;
                main.post(() -> ui.evaluateJavascript("App.onHttp(" + JSONObject.quote(token) + "," + fc + "," + JSONObject.quote(fr) + ")", null));
            });
        }

        @JavascriptInterface
        public void openExternal(String url) {
            main.post(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Kann Link nicht öffnen", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void share(String text) {
            main.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, "Edelmetall-Treffer");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "Links teilen"));
            });
        }

        @JavascriptInterface
        public void save(String key, String value) {
            prefs.edit().putString(key, value).apply();
        }

        @JavascriptInterface
        public String load(String key) {
            return prefs.getString(key, null);
        }

        @JavascriptInterface
        public void keepAwake(boolean on) {
            main.post(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void notify(String title, String text) {
            main.post(() -> {
                Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show();
                try {
                    Notification.Builder b = Build.VERSION.SDK_INT >= 26
                            ? new Notification.Builder(MainActivity.this, "scan")
                            : new Notification.Builder(MainActivity.this);
                    b.setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.star_on).setAutoCancel(true);
                    getSystemService(NotificationManager.class).notify(1, b.build());
                } catch (Exception ignored) {
                    // Benachrichtigungen nicht erlaubt
                }
            });
        }
    }
}
