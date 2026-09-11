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
        client = buildTls12Client();
        displayList = new ArrayList<>();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);

        Button btnImport = new Button(this);
        btnImport.setText("导入通用书源");
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
        etKeyword.setHint("输入漫画名进行全网搜索...");
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
                Toast.makeText(MainActivity.this, "已清空所有书源！", Toast.LENGTH_SHORT).show();
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
                    .connectTimeout(6, TimeUnit.SECONDS)
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
        tvStatus.setText("已加载书源: " + total + " 个");

        while (cursor.moveToNext()) {
            displayList.add("📌 " + cursor.getString(0));
        }
        cursor.close();
        db.close();
        adapter.notifyDataSetChanged();
    }

    private void showImportDialog() {
        final EditText input = new EditText(this);
        input.setHint("粘贴书源密文(eNr...)、JSON，或输入路径(/sdcard/Download/322.json)");
        input.setMinLines(5);

        new AlertDialog.Builder(this)
                .setTitle("导入通用书源")
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
        Toast.makeText(this, "正在读取文件...", Toast.LENGTH_SHORT).show();
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
        Toast.makeText(this, "正在下载...", Toast.LENGTH_SHORT).show();
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
                            Toast.makeText(MainActivity.this, "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
                                Toast.makeText(MainActivity.this, "成功导入 " + total + " 个书源！", Toast.LENGTH_SHORT).show();
                                refreshSourceList();
                            } else {
                                Toast.makeText(MainActivity.this, "未检测到有效数据", Toast.LENGTH_LONG).show();
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
        String name = "未知书源";
        if (obj.has("bookSourceName")) {
            name = obj.get("bookSourceName").getAsString();
        } else if (obj.has("name")) {
            name = obj.get("name").getAsString();
        }
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("json_content", obj.toString());
        db.insert("sources", null, cv);
    }

    // 兼容 Legado / 异次元混合选择器清洗
    private String cleanSelector(String rule) {
        if (TextUtils.isEmpty(rule)) return "";
        String r = rule;
        // 去除尾部属性指令
        int atIdx = r.indexOf("@");
        if (atIdx != -1) {
            r = r.substring(0, atIdx);
        }
        r = r.replace("class.", ".");
        r = r.replace("id.", "#");
        r = r.replace("tag.", "");
        r = r.replace("&&", " ");
        return r.trim();
    }

    private void startConcurrentSearch(final String keyword) {
        if (searchExecutor != null && !searchExecutor.isShutdown()) {
            searchExecutor.shutdownNow();
        }
        searchExecutor = Executors.newFixedThreadPool(6);

        displayList.clear();
        adapter.notifyDataSetChanged();

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT name, json_content FROM sources", null);
        final int totalSources = cursor.getCount();

        if (totalSources == 0) {
            tvStatus.setText("当前无书源，请先导入！");
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
        tvStatus.setText("正在并发搜索【" + keyword + "】(0/" + totalSources + ")...");

        for (final String[] item : sourceItems) {
            final String sourceName = item[0];
            final String jsonStr = item[1];

            searchExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        JsonObject source = new JsonParser().parse(jsonStr).getAsJsonObject();
                        
                        // 兼容 Legado 的 bookSourceUrl 与 searchUrl 拼装规则
                        String baseUrl = "";
                        if (source.has("bookSourceUrl")) baseUrl = source.get("bookSourceUrl").getAsString();

                        String searchUrl = "";
                        if (source.has("searchUrl")) searchUrl = source.get("searchUrl").getAsString();

                        if (!TextUtils.isEmpty(searchUrl)) {
                            String encodedKey = URLEncoder.encode(keyword, "UTF-8");
                            String finalUrl = searchUrl;

                            // 处理 Legado 常见的 searchUrl 占位符格式 (如 /search?q={{key}} 或完整 URL)
                            if (finalUrl.contains("{{key}}")) {
                                finalUrl = finalUrl.replace("{{key}}", encodedKey);
                            } else if (finalUrl.contains("{key}")) {
                                finalUrl = finalUrl.replace("{key}", encodedKey);
                            } else {
                                finalUrl = finalUrl + encodedKey;
                            }

                            if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
                                if (!baseUrl.endsWith("/") && !finalUrl.startsWith("/")) {
                                    finalUrl = baseUrl + "/" + finalUrl;
                                } else {
                                    finalUrl = baseUrl + finalUrl;
                                }
                            }

                            if (finalUrl.startsWith("http://") || finalUrl.startsWith("https://")) {
                                Request request = new Request.Builder()
                                        .url(finalUrl)
                                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                        .build();

                                Response response = client.newCall(request).execute();
                                if (response.isSuccessful() && response.body() != null) {
                                    String html = response.body().string();
                                    Document doc = Jsoup.parse(html);

                                    // 兼容 Legado 的 bookList / bookName 解析层级
                                    String listRule = "";
                                    String nameRule = "";

                                    if (source.has("ruleSearch") && source.get("ruleSearch").isJsonObject()) {
                                        JsonObject rs = source.getAsJsonObject("ruleSearch");
                                        if (rs.has("bookList")) listRule = rs.get("bookList").getAsString();
                                        if (rs.has("bookName")) nameRule = rs.get("bookName").getAsString();
                                    }

                                    if (!TextUtils.isEmpty(listRule)) {
                                        String cssList = cleanSelector(listRule);
                                        String cssName = cleanSelector(nameRule);

                                        Elements elements = doc.select(cssList);
                                        for (Element elem : elements) {
                                            String title = "";
                                            if (!TextUtils.isEmpty(cssName)) {
                                                Element nameElem = elem.select(cssName).first();
                                                if (nameElem != null) title = nameElem.text();
                                            }
                                            if (TextUtils.isEmpty(title)) {
                                                title = elem.text();
                                            }

                                            if (!TextUtils.isEmpty(title)) {
                                                title = title.replaceAll("\\s+", " ").trim();
                                                if (title.length() > 0 && title.length() < 35) {
                                                    final String record = "📖 " + title + "  【" + sourceName + "】";
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
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        int finished = finishedCounter.incrementAndGet();
                        final String statusStr = "搜索中 (" + finished + "/" + totalSources + ") | 已找到 " + displayList.size() + " 条结果";
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
