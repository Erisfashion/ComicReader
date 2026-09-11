package com.comic.reader;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private DatabaseHelper dbHelper;
    private OkHttpClient client;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Button btn = new Button(this);
        btn.setText("点击导入测试异次元图源并测试网络解析");
        setContentView(btn);

        dbHelper = new DatabaseHelper(this);
        client = new OkHttpClient();

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importAndTestSampleSource();
            }
        });
    }

    private void importAndTestSampleSource() {
        final String sampleJson = "{\n" +
                "  \"bookSourceNamer\": \"示例漫画源\",\n" +
                "  \"bookSourceUrl\": \"https://httpbin.org\",\n" +
                "  \"searchUrl\": \"/html\",\n" +
                "  \"ruleSearch\": {\n" +
                "    \"bookList\": \"div\",\n" +
                "    \"bookName\": \"h1\",\n" +
                "    \"bookUrl\": \"a@href\"\n" +
                "  }\n" +
                "}";

        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("name", "示例漫画源");
            cv.put("json_content", sampleJson);
            db.insert("sources", null, cv);
            db.close();
            Toast.makeText(this, "图源成功导入本地数据库！", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JsonObject jsonObject = new JsonParser().parse(sampleJson).getAsJsonObject();
                    String targetUrl = jsonObject.get("bookSourceUrl").getAsString() + jsonObject.get("searchUrl").getAsString();
                    
                    Request request = new Request.Builder().url(targetUrl).header("User-Agent", "Mozilla/5.0").build();
                    Response response = client.newCall(request).execute();
                    if (response.body() != null) {
                        String html = response.body().string();
                        Document doc = Jsoup.parse(html);
                        final Elements elements = doc.select(jsonObject.getAsJsonObject("ruleSearch").get("bookList").getAsString());
                        
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "网络请求与解析成功！元素数量: " + elements.size(), Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                } catch (final IOException e) {
                    e.printStackTrace();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "网络测试失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }
}
