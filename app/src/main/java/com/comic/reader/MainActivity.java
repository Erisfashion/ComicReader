package com.comic.reader;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private DatabaseHelper dbHelper;
    private OkHttpClient client;
    private ArrayList<String> displayList;
    private ArrayAdapter<String> adapter;
    private TextView tvStatus;
    private ExecutorService searchExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        dbHelper = new DatabaseHelper(this);
        // 初始化专门针对 Android 4.2.2 修复 TLS 1.2 和老证书过期的 OkHttp 实例
        client = buildTls12Client();
        displayList = new ArrayList<>();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);

        Button btnImport = new Button(this);
        btnImport.setText("导入图源");
        btnImport.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClear = new Button(this);
        btnClear.setText("清空图源");
        btnClear.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        topBar.addView(btnImport);
        topBar.addView(btnClear);
        root.addView(topBar);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(14);
        tvStatus.setTextColor(Color.DKGRAY);
        tvStatus.setPadding(10, 15, 10, 15);
        root.addView(tvStatus);

        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);

        final EditText etKeyword = new EditText(this);
        etKeyword.setHint("输入漫画名全网搜索...");
        etKeyword.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btnSearch = new Button(this);
        btnSearch.setText("搜索");

        searchBar.addView(etKeyword);
        searchBar.addView(btnSearch);
        root.addView(searchBar);

        ListView listView = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, displayList);
        listView.setAdapter(adapter);
        root.addView(listView);

        setContentView(root);

        refreshSourceList();

        btnImport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showImportDialog();
            }
        });

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                db.delete("sources", null, null);
                db.close();
                Toast.makeText(MainActivity.this, "已清空所有图源！", Toast.LENGTH_SHORT).show();
                refreshSourceList();
            }
        });

        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String keyword = etKeyword.getText().toString().trim();
                if (TextUtils.isEmpty(keyword)) {
                    refreshSourceList();
                    return;
                }
                startConcurrentSearch(keyword);
            }
        });
    }

    // 解决 Android 4.2.2 访问现代 HTTPS 的 SSLHandshakeException 问题
    private OkHttpClient buildTls12Client() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[]{}; }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            return new OkHttpClient.Builder()
                    .sslSocketFactory(new Tls12SocketFactory(sslContext.getSocketFactory()), (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier(new HostnameVerifier() {
                        public boolean verify(String hostname, SSLSession session) { return true; }
                    })
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .build();
        } catch (Exception e) {
            return new OkHttpClient();
        }
    }

    private void refreshSourceList() {
        displayList.clear();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT name FROM sources", null);
        int total = cursor.getCount();
        tvStatus.setText("已导入图源: " + total + " 个 (下方为图源列表)");

        while (cursor.moveToNext()) {
            displayList.add("📌 " + cursor.getString(0));
        }
        cursor.close();
        db.close();
        adapter.notifyDataSetChanged();
    }

    private void showImportDialog() {
        final EditText input = new EditText(this);
        input.setHint("可粘贴图源密文(eNr...)、JSON，或输入路径(/sdcard/Download/322.json)");
        input.setMinLines(5);

        new AlertDialog.Builder(this)
                .setTitle("导入图源")
                .setView(input)
                .setPositiveButton("开始导入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String text = input.getText().toString().trim();
                        if (TextUtils.isEmpty(text)) return;

                        if (text.startsWith("http://") || text.startsWith("https://")) {
                            fetchAndSaveSourceFromUrl(text);
                        } else if (new File(text).exists()) {
                            loadFromFile(text);
                        } else {
                            parseAndSaveSources(text);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadFromFile(final String filePath) {
        Toast.makeText(this, "正在读取本地文件...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File file = new File(filePath);
                    FileInputStream fis = new FileInputStream(file);
                    BufferedReader br = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    br.close();
                    parseAndSaveSources(sb.toString());
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "读取失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void fetchAndSaveSourceFromUrl(final String url) {
        Toast.makeText(this, "正在从网络拉取...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Request request = new Request.Builder().url(url).build();
                    Response response = client.newCall(request).execute();
                    if (response.body() != null) {
                        parseAndSaveSources(response.body().string());
                    }
                } catch (final IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "拉取失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private String tryDecompress(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.startsWith("{") || text.startsWith("[")) {
            return text;
        }

        try {
            byte[] data = Base64.decode(text, Base64.DEFAULT);

            try {
                ByteArrayInputStream bais = new ByteArrayInputStream(data);
                InflaterInputStream iis = new InflaterInputStream(bais);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048];
                int len;
                while ((len = iis.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                iis.close();
                String decomp = baos.toString("UTF-8");
                if (decomp.trim().startsWith("{") || decomp.trim().startsWith("[")) {
                    return decomp;
                }
            } catch (Exception ignored) {}

            try {
                ByteArrayInputStream bais = new ByteArrayInputStream(data);
                GZIPInputStream gis = new GZIPInputStream(bais);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048];
                int len;
                while ((len = gis.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                gis.close();
                String decomp = baos.toString("UTF-8");
                if (decomp.trim().startsWith("{") || decomp.trim().startsWith("[")) {
                    return decomp;
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}

        return text;
    }

    private void parseAndSaveSources(final String rawContent) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String jsonContent = tryDecompress(rawContent);
                    JsonElement element = new JsonParser().parse(jsonContent);

                    int count = 0;
                    SQLiteDatabase db = dbHelper.getWritableDatabase();
                    db.beginTransaction();
                    try {
                        if (element.isJsonArray()) {
                            JsonArray array = element.getAsJsonArray();
                            for (JsonElement item : array) {
                                if (item.isJsonObject()) {
                                    saveSourceToDb(db, item.getAsJsonObject());
                                    count++;
                                }
                            }
                        } else if (element.isJsonObject()) {
                            saveSourceToDb(db, element.getAsJsonObject());
                            count++;
                        }
                        db.setTransactionSuccessful();
                    } finally {
                        db.endTransaction();
                        db.close();
                    }

                    final int total = count;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (total > 0) {
                                Toast.makeText(MainActivity.this, "成功导入 " + total + " 个图源！", Toast.LENGTH_SHORT).show();
                                refreshSourceList();
                            } else {
                                Toast.makeText(MainActivity.this, "未找到有效的图源规则", Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "解析失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void saveSourceToDb(SQLiteDatabase db, JsonObject obj) {
        String name = "未知图源";
        if (obj.has("bookSourceName")) {
            name = obj.get("bookSourceName").getAsString();
        } else if (obj.has("bookSourceNamer")) {
            name = obj.get("bookSourceNamer").getAsString();
        } else if (obj.has("name")) {
            name = obj.get("name").getAsString();
        }
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("json_content", obj.toString());
        db.insert("sources", null, cv);
    }

    // 异次元私有规则到 Jsoup CSS 选择器的转换器
    private String convertRuleToCss(String rule) {
        if (TextUtils.isEmpty(rule)) return "";
        String clean = rule;
        if (clean.contains("@text")) clean = clean.replace("@text", "");
        if (clean.contains("@href")) clean = clean.substring(0, clean.indexOf("@href"));
        if (clean.contains("@src")) clean = clean.substring(0, clean.indexOf("@src"));

        clean = clean.replace("class.", ".");
        clean = clean.replace("id.", "#");
        clean = clean.replace("tag.", "");
        clean = clean.replace("@", " ").trim();
        return clean;
    }

    // 10 线程并发搜索 + 结果实时瀑布流展示
    private void startConcurrentSearch(final String keyword) {
        if (searchExecutor != null && !searchExecutor.isShutdown()) {
            searchExecutor.shutdownNow();
        }
        searchExecutor = Executors.newFixedThreadPool(10);

        displayList.clear();
        adapter.notifyDataSetChanged();

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT name, json_content FROM sources", null);
        final int totalSources = cursor.getCount();

        if (totalSources == 0) {
            tvStatus.setText("当前无图源，请先导入！");
            cursor.close();
            db.close();
            return;
        }

        final ArrayList<String[]> sourceItems = new ArrayList<>();
        while (cursor.moveToNext()) {
            sourceItems.add(new String[]{cursor.getString(0), cursor.getString(1)});
        }
        cursor.close();
        db.close();

        final AtomicInteger finishedCounter = new AtomicInteger(0);
        tvStatus.setText("正在并发搜索中 (0/" + totalSources + ")... 找到结果将立即呈现");

        for (final String[] item : sourceItems) {
            final String sourceName = item[0];
            final String jsonStr = item[1];

            searchExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        JsonObject source = new JsonParser().parse(jsonStr).getAsJsonObject();
                        String baseUrl = source.has("bookSourceUrl") ? source.get("bookSourceUrl").getAsString() : "";
                        String searchUrl = source.has("searchUrl") ? source.get("searchUrl").getAsString() : "";

                        if (!TextUtils.isEmpty(searchUrl)) {
                            String encodedKey = URLEncoder.encode(keyword, "UTF-8");
                            String finalUrl;
                            if (searchUrl.startsWith("http://") || searchUrl.startsWith("https://")) {
                                finalUrl = searchUrl;
                            } else {
                                finalUrl = baseUrl + (searchUrl.startsWith("/") ? "" : "/") + searchUrl;
                            }

                            if (finalUrl.contains("{{key}}")) {
                                finalUrl = finalUrl.replace("{{key}}", encodedKey);
                            } else {
                                finalUrl = finalUrl + encodedKey;
                            }

                            Request request = new Request.Builder()
                                    .url(finalUrl)
                                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                    .build();

                            Response response = client.newCall(request).execute();
                            if (response.body() != null) {
                                String html = response.body().string();
                                Document doc = Jsoup.parse(html);
                                JsonObject ruleSearch = source.getAsJsonObject("ruleSearch");

                                if (ruleSearch != null && ruleSearch.has("bookList")) {
                                    String rawListRule = ruleSearch.get("bookList").getAsString();
                                    String rawNameRule = ruleSearch.has("bookName") ? ruleSearch.get("bookName").getAsString() : "";

                                    String cssList = convertRuleToCss(rawListRule);
                                    String cssName = convertRuleToCss(rawNameRule);

                                    Elements bookElements = doc.select(cssList);
                                    for (Element bookElem : bookElements) {
                                        String title = "";
                                        if (!TextUtils.isEmpty(cssName)) {
                                            Element nameElem = bookElem.select(cssName).first();
                                            if (nameElem != null) title = nameElem.text();
                                        }
                                        if (TextUtils.isEmpty(title)) {
                                            title = bookElem.text();
                                        }

                                        // 过滤掉包含无关杂质的超长文本，保证漫画标题准确性
                                        if (!TextUtils.isEmpty(title) && title.length() < 50) {
                                            final String record = "📖 " + title.trim() + "  【" + sourceName + "】";
                                            runOnUiThread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    displayList.add(record);
                                                    adapter.notifyDataSetChanged();
                                                }
                                            });
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        int finished = finishedCounter.incrementAndGet();
                        final String statusStr = "搜索进度: (" + finished + "/" + totalSources + ") | 已检索到 " + displayList.size() + " 条结果";
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                tvStatus.setText(statusStr);
                            }
                        });
                    }
                }
            });
        }
    }

    // TLS 1.2 Socket 套接字定制类，在旧版 Android 上启用高版本加密通道
    static class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        public Tls12SocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return patch(delegate.createSocket(s, host, port, autoClose));
        }
        public Socket createSocket(String host, int port) throws IOException {
            return patch(delegate.createSocket(host, port));
        }
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return patch(delegate.createSocket(host, port, localHost, localPort));
        }
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return patch(delegate.createSocket(host, port));
        }
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return patch(delegate.createSocket(address, port, localAddress, localPort));
        }

        private Socket patch(Socket s) {
            if (s instanceof SSLSocket) {
                ((SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.1", "TLSv1.2"});
            }
            return s;
        }
    }
}
