package com.comic.reader;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "comic_reader.db";
    private static final int DATABASE_VERSION = 1;

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sources (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, json_content TEXT);");
        db.execSQL("CREATE TABLE bookshelf (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, url TEXT, cover TEXT, progress TEXT);");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS sources");
        db.execSQL("DROP TABLE IF EXISTS bookshelf");
        onCreate(db);
    }
}
