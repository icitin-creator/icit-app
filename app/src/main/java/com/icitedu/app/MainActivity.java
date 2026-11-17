package com.icitedu.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ICIT Dhampur app: shows the ICIT website (bundled in assets/www/index.html)
 * inside a WebView. The website itself talks to the same Firebase database,
 * so data entered on the website and in the app is the same.
 */
public class MainActivity extends Activity {

    /** Local, secure (https) address the bundled website is served from. */
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + ASSET_HOST + "/assets/www/index.html";

    private static final int REQ_FILE = 101;
    private static final int REQ_STORAGE = 102;

    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private ValueCallback<Uri[]> fileCallback;

    // A file waiting for the storage permission (Android 9 and older only)
    private String pendingName, pendingMime;
    private byte[] pendingBytes;

    /** Runs in every page: saves Excel/CSV downloads through the app and makes print work. */
    private static final String BRIDGE_JS =
            "(function(){if(window.__icitApp)return;window.__icitApp=1;" +
            "function b64(blob,cb){var r=new FileReader();r.onload=function(){var s=String(r.result);cb(s.substring(s.indexOf(',')+1))};r.readAsDataURL(blob)}" +
            "document.addEventListener('click',function(e){" +
            " var a=e.target&&e.target.closest?e.target.closest('a'):null;if(!a)return;" +
            " var h=a.getAttribute('href')||'';if(!/^(blob:|data:)/i.test(h))return;" +
            " e.preventDefault();e.stopPropagation();" +
            " var n=a.getAttribute('download')||'icit-file';" +
            " fetch(h).then(function(r){return r.blob()}).then(function(bl){b64(bl,function(d){AndroidBridge.saveFile(n,bl.type||'application/octet-stream',d)})})" +
            " .catch(function(){AndroidBridge.toast('File save nahi ho saki')});" +
            "},true);" +
            "window.print=function(){AndroidBridge.print()};" +
            "})();";

    /** Back button: close an open popup/menu first; warn during an online exam. */
    private static final String BACK_JS =
            "(function(){" +
            "if(document.querySelector('#modalRoot .ov')||document.querySelector('.pop-ov.show')){" +
            " document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));return 'closed'}" +
            "var l=document.getElementById('links');if(l&&l.classList.contains('open')){var b=document.getElementById('burger');if(b)b.click();return 'closed'}" +
            "if(document.getElementById('examRoot'))return 'exam';" +
            "if(location.hash==='#admin'){location.hash='home';return 'closed'}" +
            "return 'none'})()";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        setupSystemBars(root);

        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(ASSET_HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100); // keep the website layout same even if phone font size is large
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " ICITApp/1.0");

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setWebViewClient(new AppClient());
        webView.setWebChromeClient(new AppChrome());
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            if (url != null && url.startsWith("http")) openOutside(Uri.parse(url));
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }
    }

    // ---------------------------------------------------------------- system bars

    private void setupSystemBars(View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            // Draw behind the bars and pad the content ourselves (also handles the keyboard)
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            });
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light, light);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    // ---------------------------------------------------------------- links

    /** Returns true when the link was opened outside the app (WhatsApp, Maps, phone...). */
    private boolean routeUrl(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("http") || scheme.equals("https")) {
            if (ASSET_HOST.equalsIgnoreCase(uri.getHost())) return false; // our own pages
            openOutside(uri);
            return true;
        }
        if (scheme.equals("about") || scheme.equals("data") || scheme.equals("blob")
                || scheme.equals("javascript")) {
            return false;
        }
        if (scheme.equals("intent")) {
            try {
                Intent i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    String fb = i.getStringExtra("browser_fallback_url");
                    if (fb != null) openOutside(Uri.parse(fb));
                }
            } catch (URISyntaxException ignored) {
            }
            return true;
        }
        openOutside(uri); // tel:, mailto:, whatsapp:, tg: ...
        return true;
    }

    private void openOutside(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            toast("Is link ko kholne ke liye koi app nahi mili");
        }
    }

    private class AppClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return assetLoader.shouldInterceptRequest(request.getUrl());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false; // e.g. Google Maps box inside the page
            return routeUrl(request.getUrl());
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            view.evaluateJavascript(BRIDGE_JS, null);
        }
    }

    // ---------------------------------------------------------------- file chooser, new windows

    private class AppChrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;

            boolean imagesOnly = true;
            Set<String> types = new LinkedHashSet<>();
            for (String a : params.getAcceptTypes()) {
                if (a == null) continue;
                for (String part : a.split(",")) {
                    String p = part.trim().toLowerCase(Locale.ROOT);
                    if (p.isEmpty()) continue;
                    types.add(p);
                    if (!p.startsWith("image/")) imagesOnly = false;
                }
            }
            if (types.isEmpty()) imagesOnly = false;

            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            // Excel/CSV/JSON files often have odd types on phones, so allow any file and let the site check it
            i.setType(imagesOnly ? "image/*" : "*/*");
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            try {
                startActivityForResult(Intent.createChooser(i, "File chunein"), REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCallback.onReceiveValue(null);
                fileCallback = null;
                toast("File chunne ke liye koi app nahi mili");
            }
            return true;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            // Links that open in a new tab (WhatsApp, Telegram, Maps, Tally...) go to the right app
            WebView temp = new WebView(MainActivity.this);
            temp.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    Uri u = r.getUrl();
                    if (u != null && ASSET_HOST.equalsIgnoreCase(u.getHost())) {
                        webView.loadUrl(u.toString());
                    } else {
                        routeUrl(u);
                    }
                    v.post(v::destroy);
                    return true;
                }
            });
            WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
            t.setWebView(temp);
            resultMsg.sendToTarget();
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null && clip.getItemCount() > 0) {
                    List<Uri> list = new ArrayList<>();
                    for (int k = 0; k < clip.getItemCount(); k++) {
                        Uri u = clip.getItemAt(k).getUri();
                        if (u != null) list.add(u);
                    }
                    result = list.toArray(new Uri[0]);
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            if (fileCallback != null) fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ---------------------------------------------------------------- JS bridge

    private class Bridge {
        @JavascriptInterface
        public void saveFile(String name, String mime, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                runOnUiThread(() -> saveToDownloads(cleanName(name), mime, bytes));
            } catch (IllegalArgumentException e) {
                toast("File save nahi ho saki");
            }
        }

        @JavascriptInterface
        public void print() {
            runOnUiThread(() -> {
                PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                if (pm == null) return;
                PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter("ICIT Dhampur");
                pm.print("ICIT Dhampur", adapter, new PrintAttributes.Builder().build());
            });
        }

        @JavascriptInterface
        public void toast(String msg) {
            MainActivity.this.toast(msg);
        }
    }

    private static String cleanName(String n) {
        String s = n == null ? "" : n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "icit-file" : s;
    }

    private void saveToDownloads(String name, String mime, byte[] bytes) {
        if (mime == null || mime.isEmpty()) {
            String ext = MimeTypeMap.getFileExtensionFromUrl(name);
            String guess = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            mime = guess != null ? guess : "application/octet-stream";
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                ContentResolver cr = getContentResolver();
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IllegalStateException("no uri");
                try (OutputStream os = cr.openOutputStream(uri)) {
                    if (os == null) throw new IllegalStateException("no stream");
                    os.write(bytes);
                }
            } else {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    pendingName = name;
                    pendingMime = mime;
                    pendingBytes = bytes;
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                    return;
                }
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                int n = 1;
                String base = name, ext = "";
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    base = name.substring(0, dot);
                    ext = name.substring(dot);
                }
                while (f.exists()) f = new File(dir, base + " (" + (n++) + ")" + ext);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(bytes);
                }
            }
            toast("Downloads folder me save ho gayi: " + name);
        } catch (Exception e) {
            toast("File save nahi ho saki");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_STORAGE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && pendingBytes != null) {
                saveToDownloads(pendingName, pendingMime, pendingBytes);
            } else {
                toast("Storage permission ke bina file save nahi ho sakti");
            }
            pendingName = pendingMime = null;
            pendingBytes = null;
        }
    }

    // ---------------------------------------------------------------- back, lifecycle

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        webView.evaluateJavascript(BACK_JS, value -> {
            String v = value == null ? "" : value.replace("\"", "");
            if (v.equals("closed")) return;
            if (v.equals("exam")) {
                toast("Exam chal raha hai. Pehle exam submit karein.");
                return;
            }
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                finish();
            }
        });
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
