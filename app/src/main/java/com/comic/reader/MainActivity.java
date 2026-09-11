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
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private DatabaseHelper dbHelper;
    private OkHttpClient client;
    private ArrayList<String> displayList;
    private ArrayAdapter<String> adapter;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        dbHelper = new DatabaseHelper(this);
        client = new OkHttpClient();
        displayList = new ArrayList<>();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);

        // 顶部操作栏
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

        // 图源状态提示栏（导入后立刻发生视觉变化）
        tvStatus = new TextView(this);
        tvStatus.setTextSize(15);
        tvStatus.setTextColor(Color.DKGRAY);
        tvStatus.setPadding(10, 15, 10, 15);
        root.addView(tvStatus);

        // 搜索栏
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

        // 内容展示列表
        ListView listView = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, displayList);
        listView.setAdapter(adapter);
        root.addView(listView);

        setContentView(root);

        // 初始化载入图源数据
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
                searchComic(keyword);
            }
        });
    }

    // 刷新显示本地所有已导入的图源
    private void refreshSourceList() {
        displayList.clear();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT name FROM sources", null);
        int total = cursor.getCount();
        tvStatus.setText("当前已导入图源: " + total + " 个 (下方为图源列表)");

        while (cursor.moveToNext()) {
            displayList.add("📌 [图源] " + cursor.getString(0));
        }
        cursor.close();
        db.close();
        adapter.notifyDataSetChanged();
    }

    private void showImportDialog() {
        final EditText input = new EditText(this);
        input.setHint("粘贴图源文本/密文，或输入文件路径(如 /sdcard/Download/322.json)");
        input.setMinLines(5);

        new AlertDialog.Builder(this)
                .setTitle("导入异次元图源")
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
                            Toast.makeText(MainActivity.this, "文件读取失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void fetchAndSaveSourceFromUrl(final String url) {
        Toast.makeText(this, "正在拉取网络规则...", Toast.LENGTH_SHORT).show();
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
                            Toast.makeText(MainActivity.this, "网络拉取失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
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

            // Zlib/Deflate 解压缩
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

            // GZIP 解压缩
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
                                Toast.makeText(MainActivity.this, "导入成功！共写入 " + total + " 个图源", Toast.LENGTH_SHORT).show();
                                refreshSourceList();
                            } else {
                                Toast.makeText(MainActivity.this, "未检测到图源数据", Toast.LENGTH_LONG).show();
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

    private void searchComic(final String keyword) {
        displayList.clear();
        adapter.notifyDataSetChanged();
        tvStatus.setText("正在搜索【" + keyword + "】中...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = dbHelper.getReadableDatabase();
                Cursor cursor = db.rawQuery("SELECT name, json_content FROM sources", null);
                if (cursor.getCount() == 0) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tvStatus.setText("暂无图源，请先导入！");
                        }
                    });
                    cursor.close();
                    db.close();
                    return;
                }

                while (cursor.moveToNext()) {
                    String sourceName = cursor.getString(0);
                    String jsonStr = cursor.getString(1);

                    try {
                        JsonObject source = new JsonParser().parse(jsonStr).getAsJsonObject();
                        String baseUrl = source.has("bookSourceUrl") ? source.get("bookSourceUrl").getAsString() : "";
                        String searchUrl = source.has("searchUrl") ? source.get("searchUrl").getAsString() : "";

                        if (TextUtils.isEmpty(searchUrl)) continue;

                        String finalUrl;
                        String encodedKey = URLEncoder.encode(keyword, "UTF-8");
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
                                .header("User-Agent", "Mozilla/5.0 (Linux; Android 4.2.2)")
                                .build();

                        Response response = client.newCall(request).execute();
                        if (response.body() != null) {
                            Document doc = Jsoup.parse(response.body().string());
                            JsonObject ruleSearch = source.getAsJsonObject("ruleSearch");
                            if (ruleSearch != null && ruleSearch.has("bookList")) {
                                String listRule = ruleSearch.get("bookList").getAsString();
                                String nameRule = ruleSearch.has("bookName") ? ruleSearch.get("bookName").getAsString() : "";

                                Elements items = doc.select(listRule);
                                for (Element item : items) {
                                    String title = TextUtils.isEmpty(nameRule) ? item.text() : item.select(nameRule).text();
                                    if (!TextUtils.isEmpty(title)) {
                                        final String record = "📖 " + title + "  (" + sourceName + ")";
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
                    } catch (Exception ignored) {}
                }
                cursor.close();
                db.close();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvStatus.setText("搜索完毕！共找到 " + displayList.size() + " 条结果 (清空输入框点击搜索可返回图源列表)");
                    }
                });
            }
        }).start();
    }
}
