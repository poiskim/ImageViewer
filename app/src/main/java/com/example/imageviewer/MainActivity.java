package com.example.imageviewer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Patterns;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;

public class MainActivity extends Activity {

    private static final int MENU_FORWARD = 1;
    private static final int MENU_RELOAD = 2;
    private static final int MENU_FROM_START = 3;
    private static final int MENU_DEEP = 4;
    private static final int MENU_SMALL = 5;

    private WebView browser;
    private WebView viewer;
    private EditText urlBar;
    private ProgressBar progress;

    private SharedPreferences prefs;
    private String extractJs;
    private String viewerHtml;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean extracting = false;
    private boolean viewerOpen = false;
    private volatile int viewerIndex = 0;

    private final Runnable extractTimeout = new Runnable() {
        @Override
        public void run() {
            if (extracting) {
                extracting = false;
                toast("이미지를 가져오지 못했어요. 페이지가 다 열린 뒤 다시 눌러 보세요.");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().setStatusBarColor(0xFF1E1E1E);
        getWindow().setNavigationBarColor(0xFF121212);

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE);
        extractJs = readAsset("extract.js");
        viewerHtml = readAsset("viewer.html");

        browser = findViewById(R.id.browser);
        viewer = findViewById(R.id.viewer);
        urlBar = findViewById(R.id.urlBar);
        progress = findViewById(R.id.progress);

        setupBrowser();
        setupViewer();
        setupToolbar();

        String incoming = urlFromIntent(getIntent());
        if (incoming != null) {
            load(incoming);
        } else {
            String last = prefs.getString("last_url", null);
            if (last != null) load(last);
            else urlBar.requestFocus();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String url = urlFromIntent(intent);
        if (url != null) {
            closeViewer();
            load(url);
        }
    }

    // ------------------------------------------------------------------ 브라우저

    @SuppressLint("SetJavaScriptEnabled")
    private void setupBrowser() {
        WebSettings s = browser.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(browser, true);

        browser.addJavascriptInterface(new PageBridge(), "ImageViewerBridge");

        browser.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleOtherSchemes(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!urlBar.hasFocus()) urlBar.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!urlBar.hasFocus()) urlBar.setText(url);
                if (url != null && url.startsWith("http")) {
                    prefs.edit().putString("last_url", url).apply();
                }
            }
        });

        browser.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress < 100 ? View.VISIBLE : View.INVISIBLE);
            }
        });

        browser.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                openExternally(Uri.parse(url));
            }
        });

        // 페이지의 이미지를 길게 누르면 그 이미지부터 뷰어로 보기
        browser.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                WebView.HitTestResult hit = browser.getHitTestResult();
                int type = hit.getType();
                if ((type == WebView.HitTestResult.IMAGE_TYPE
                        || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && hit.getExtra() != null) {
                    startExtract(false, false, hit.getExtra());
                    return true;
                }
                return false;
            }
        });
    }

    /** http/https 가 아닌 링크(intent://, tel:, market: 등)는 다른 앱으로 넘깁니다. */
    private boolean handleOtherSchemes(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        switch (scheme) {
            case "http":
            case "https":
            case "about":
            case "data":
            case "blob":
            case "javascript":
            case "file":
                return false;
            case "intent":
                try {
                    Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                    try {
                        startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        String fallback = intent.getStringExtra("browser_fallback_url");
                        if (fallback != null) browser.loadUrl(fallback);
                    }
                } catch (Exception ignored) {
                }
                return true;
            default:
                openExternally(uri);
                return true;
        }
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            toast("이 링크를 열 수 있는 앱이 없어요");
        }
    }

    private void load(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return;
        String url;
        if (s.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
            url = s;
        } else if (!s.contains(" ") && (s.contains(".") || s.startsWith("localhost"))) {
            url = "https://" + s;
        } else {
            url = "https://www.google.com/search?q=" + Uri.encode(s);
        }
        urlBar.setText(url);
        browser.loadUrl(url);
    }

    private String urlFromIntent(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text == null) return null;
            Matcher m = Patterns.WEB_URL.matcher(text);
            return m.find() ? m.group() : null;
        }
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            return intent.getData().toString();
        }
        return null;
    }

    // ------------------------------------------------------------------ 툴바

    private void setupToolbar() {
        ImageButton back = findViewById(R.id.btnBack);
        ImageButton view = findViewById(R.id.btnView);
        ImageButton more = findViewById(R.id.btnMore);

        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (browser.canGoBack()) browser.goBack();
            }
        });

        // 짧게 누르기: 지금 보이는 이미지부터 / 길게 누르기: 끝까지 불러온 뒤 보기
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startExtract(false, false, null);
            }
        });
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                startExtract(true, false, null);
                return true;
            }
        });

        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });

        urlBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN;
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                    load(urlBar.getText().toString());
                    hideKeyboard();
                    urlBar.clearFocus();
                    browser.requestFocus();
                    return true;
                }
                return false;
            }
        });

        urlBar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus && browser.getUrl() != null) urlBar.setText(browser.getUrl());
            }
        });
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, MENU_FORWARD, 0, "앞으로").setEnabled(browser.canGoForward());
        m.add(0, MENU_RELOAD, 1, "새로고침");
        m.add(0, MENU_FROM_START, 2, "첫 이미지부터 보기");
        m.add(0, MENU_DEEP, 3, "끝까지 불러온 뒤 보기");
        m.add(0, MENU_SMALL, 4, "작은 이미지도 포함").setCheckable(true)
                .setChecked(prefs.getBoolean("include_small", false));
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                return onMenuItem(item);
            }
        });
        pm.show();
    }

    private boolean onMenuItem(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_FORWARD:
                if (browser.canGoForward()) browser.goForward();
                return true;
            case MENU_RELOAD:
                browser.reload();
                return true;
            case MENU_FROM_START:
                startExtract(false, true, null);
                return true;
            case MENU_DEEP:
                startExtract(true, false, null);
                return true;
            case MENU_SMALL:
                boolean on = !prefs.getBoolean("include_small", false);
                prefs.edit().putBoolean("include_small", on).apply();
                toast(on ? "작은 이미지도 포함해요" : "작은 이미지(아이콘 등)는 빼요");
                return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 이미지 수집

    private void startExtract(boolean deep, boolean fromStart, String startUrl) {
        if (extracting) return;
        if (browser.getUrl() == null) {
            toast("먼저 사이트 주소를 입력하세요");
            urlBar.requestFocus();
            return;
        }
        extracting = true;
        if (deep) toast("페이지를 끝까지 내리면서 이미지를 모으는 중…");

        JSONObject opts = new JSONObject();
        try {
            opts.put("deep", deep);
            opts.put("fromStart", fromStart);
            opts.put("small", prefs.getBoolean("include_small", false));
            if (startUrl != null) opts.put("startUrl", startUrl);
        } catch (JSONException ignored) {
        }
        browser.evaluateJavascript(extractJs + "\n;window.__ivExtract(" + opts + ");", null);
        handler.removeCallbacks(extractTimeout);
        handler.postDelayed(extractTimeout, deep ? 120000 : 20000);
    }

    private void onImagesFound(String json) {
        if (!extracting) return; // 앱이 요청한 결과만 받음
        extracting = false;
        handler.removeCallbacks(extractTimeout);
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.has("error")) {
                toast("이미지를 찾는 중 문제가 생겼어요: " + obj.optString("error"));
                return;
            }
            JSONArray images = obj.optJSONArray("images");
            if (images == null || images.length() == 0) {
                toast("이 페이지에서 이미지를 찾지 못했어요. 메뉴의 '끝까지 불러온 뒤 보기'를 써 보세요.");
                return;
            }
            openViewer(obj);
        } catch (JSONException e) {
            toast("이미지 목록을 읽지 못했어요");
        }
    }

    // ------------------------------------------------------------------ 뷰어

    @SuppressLint("SetJavaScriptEnabled")
    private void setupViewer() {
        viewer.setBackgroundColor(Color.BLACK);
        WebSettings s = viewer.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(browser.getSettings().getUserAgentString());
        CookieManager.getInstance().setAcceptThirdPartyCookies(viewer, true);

        viewer.addJavascriptInterface(new ViewerBridge(), "ViewerBridge");
        viewer.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // 뷰어 안에서는 다른 페이지로 이동하지 않음
            }
        });
    }

    private void openViewer(JSONObject data) throws JSONException {
        JSONObject p = new JSONObject();
        p.put("fit", prefs.getString("viewer_fit", "auto"));
        p.put("rtl", "true".equals(prefs.getString("viewer_rtl", "false")));
        data.put("prefs", p);

        String payload = data.toString().replace("</", "<\\/");
        String html = viewerHtml.replace("/*__DATA__*/null", payload);

        // 원래 페이지 주소를 기준 주소로 써서, 사이트가 Referer 를 확인해도 이미지가 열리게 함
        String base = data.optString("page", browser.getUrl());
        viewerIndex = data.optInt("start", 0);
        viewer.loadDataWithBaseURL(base, html, "text/html", "utf-8", null);
        viewer.setVisibility(View.VISIBLE);
        viewer.requestFocus();
        viewerOpen = true;
        hideKeyboard();
        setFullscreen(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void closeViewer() {
        if (!viewerOpen) return;
        viewerOpen = false;
        viewer.setVisibility(View.GONE);
        viewer.loadUrl("about:blank");
        setFullscreen(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 마지막으로 본 이미지 위치로 브라우저 스크롤
        browser.evaluateJavascript("window.__ivScrollTo&&window.__ivScrollTo(" + viewerIndex + ")", null);
        browser.requestFocus();
    }

    @SuppressWarnings("deprecation")
    private void setFullscreen(boolean on) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            if (on) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        } else {
            View d = getWindow().getDecorView();
            d.setSystemUiVisibility(on
                    ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    : 0);
        }
    }

    // ------------------------------------------------------------------ 키 / 뒤로가기

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 뷰어에서 볼륨 키로도 넘기기: 볼륨 아래 = 다음, 볼륨 위 = 이전
        if (viewerOpen) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int dir = code == KeyEvent.KEYCODE_VOLUME_DOWN ? 1 : -1;
                    viewer.evaluateJavascript("window.ivNav&&window.ivNav(" + dir + ")", null);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (viewerOpen) {
            closeViewer();
        } else if (urlBar.hasFocus()) {
            urlBar.clearFocus();
            hideKeyboard();
            browser.requestFocus();
        } else if (browser.canGoBack()) {
            browser.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && viewerOpen) setFullscreen(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        browser.onPause();
        viewer.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        browser.onResume();
        viewer.onResume();
        if (viewerOpen) setFullscreen(true);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        browser.destroy();
        viewer.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 도우미

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("asset 읽기 실패: " + name, e);
        }
    }

    // ------------------------------------------------------------------ JS ↔ 앱 연결

    /** 웹 페이지(브라우저) 쪽에서 호출 */
    private class PageBridge {
        @JavascriptInterface
        public void onImages(final String json) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    onImagesFound(json);
                }
            });
        }
    }

    /** 뷰어 화면 쪽에서 호출 */
    private class ViewerBridge {
        @JavascriptInterface
        public void close() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    closeViewer();
                }
            });
        }

        @JavascriptInterface
        public void onIndex(int i) {
            viewerIndex = i;
        }

        @JavascriptInterface
        public void setPref(String key, String value) {
            if ("fit".equals(key) || "rtl".equals(key)) {
                prefs.edit().putString("viewer_" + key, value).apply();
            }
        }
    }
}
