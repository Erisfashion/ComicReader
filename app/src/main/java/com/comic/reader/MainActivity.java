package com.comic.reader;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private DatabaseHelper dbHelper;
    private OkHttpClient client;
    private ArrayList<String> searchResults;
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        dbHelper = new DatabaseHelper(this);
        client = new OkHttpClient();
        searchResults = new ArrayList<>();

        // 构建界面
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);

        // 顶部操作栏（导入图源按钮、清除图源按钮）
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);

        Button btnImport = new Button(this);
        btnImport.setText("导入图源(链接/文本)");
        btnImport.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClear = new Button(this);
        btnClear.setText("清空所有图源");
        btnClear.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        topBar.addView(btnImport);
        topBar.addView(btnClear);
        root.addView(topBar);

        // 搜索栏（输入框 + 搜索按钮）
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

        // 结果展示列表
        ListView listView = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, searchResults);
        listView.setAdapter(adapter);
        root.addView(listView);

        setContentView(root);

        // 事件绑定
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
                Toast.makeText(MainActivity.this, "已清空本地所有图源", Toast.LENGTH_SHORT).show();
            }
        });

        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String keyword = etKeyword.getText().toString().trim();
                if (TextUtils.isEmpty(keyword)) {
                    Toast.makeText(MainActivity.this, "请输入漫画名称", Toast.LENGTH_SHORT).show();
                    return;
                }
                searchComic(keyword);
            }
        });
    }

    // 弹出图源导入对话框（支持粘贴规则或输入订阅 URL）
    private void showImportDialog() {
        final EditText input = new EditText(this);
        input.setHint("在此粘贴图源 JSON 内容，或输入图源的 http/https 网络地址");
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
                        } else {
                            parseAndSaveSources(text);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 从网络地址拉取图源文本
    private void fetchAndSaveSourceFromUrl(final String url) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Request request = new Request.Builder().url(url).build();
                    Response response = client.newCall(request).execute();
                    if (response.body() != null) {
                        final String jsonContent = response.body().string();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                parseAndSaveSources(jsonContent);
                            }
                        });
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

    // 解析并保存图源到 SQLite
    private void parseAndSaveSources(String jsonContent) {
        try {
            int count = 0;
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            JsonElement element = new JsonParser().parse(jsonContent);

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
            db.close();
            Toast.makeText(this, "成功导入 " + count + " 个图源！", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "图源解析失败，格式有误", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveSourceToDb(SQLiteDatabase db, JsonObject obj) {
        String name = "未知图源";
        if (obj.has("bookSourceName")) {
            name = obj.get("bookSourceName").getAsString();
        } else if (obj.has("bookSourceNamer")) {
            name = obj.get("bookSourceNamer").getAsString();
        }
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("json_content", obj.toString());
        db.insert("sources", null, cv);
    }

    // 多源全网搜索漫画
    private void searchComic(final String keyword) {
        searchResults.clear();
        adapter.notifyDataSetChanged();
        Toast.makeText(this, "正在全网搜索，请稍候...", Toast.LENGTH_SHORT).show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = dbHelper.getReadableDatabase();
                Cursor cursor = db.rawQuery("SELECT name, json_content FROM sources", null);
                if (cursor.getCount() == 0) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "暂无图源，请先导入图源！", Toast.LENGTH_SHORT).show();
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

                        if (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(searchUrl)) continue;

                        String finalUrl;
                        String encodedKey = URLEncoder.encode(keyword, "UTF-8");
                        if (searchUrl.contains("{{key}}")) {
                            finalUrl = baseUrl + searchUrl.replace("{{key}}", encodedKey);
                        } else {
                            finalUrl = baseUrl + searchUrl + encodedKey;
                        }

                        Request request = new Request.Builder()
                                .url(finalUrl)
                                .header("User-Agent", "Mozilla/5.0 (Linux; Android 4.2.2)")
                                .build();

                        Response response = client.newCall(request).execute();
                        if (response.body() != null) {
                            Document doc = Jsoup.parse(response.body().string());
                            JsonObject ruleSearch = source.getAsJsonObject("ruleSearch");
                            if (ruleSearch != null) {
                                String listRule = ruleSearch.get("bookList").getAsString();
                                String nameRule = ruleSearch.has("bookName") ? ruleSearch.get("bookName").getAsString() : "";

                                Elements items = doc.select(listRule);
                                for (Element item : items) {
                                    String title = TextUtils.isEmpty(nameRule) ? item.text() : item.select(nameRule).text();
                                    if (!TextUtils.isEmpty(title)) {
                                        final String record = "【" + sourceName + "】 " + title;
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                searchResults.add(record);
                                                adapter.notifyDataSetChanged();
                                            }
                                        });
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                cursor.close();
                db.close();
            }
        }).start();
    }
}
