package com.nless.mypdf;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

public class PdfDbHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "mypdf.db";
    private static final int DATABASE_VERSION = 1;

    public PdfDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // 创建表，使用 uri 作为主键防止绝对重复
        db.execSQL("CREATE TABLE items (uri TEXT PRIMARY KEY, name TEXT, path TEXT, time TEXT, isFolder INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS items");
        onCreate(db);
    }

    // 插入数据
    public void insertItem(PdfItem item) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("uri", item.uri.toString());
        values.put("name", item.name);
        values.put("path", item.path);
        values.put("time", item.time);
        values.put("isFolder", item.isFolder ? 1 : 0);
        // 如果存在冲突（即相同的 URI），则忽略，这在底层做了一次查重兜底
        db.insertWithOnConflict("items", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    // 删除数据
    public void deleteItem(String uriString) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete("items", "uri=?", new String[]{uriString});
    }

    // 检查是否已存在
    public boolean exists(String uriString) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query("items", new String[]{"uri"}, "uri=?", new String[]{uriString}, null, null, null);
        boolean exists = (cursor.getCount() > 0);
        cursor.close();
        return exists;
    }

    // 获取所有保存的数据
    public List<PdfItem> getAllItems() {
        List<PdfItem> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM items", null);
        if (cursor.moveToFirst()) {
            do {
                Uri uri = Uri.parse(cursor.getString(0));
                String name = cursor.getString(1);
                String path = cursor.getString(2);
                String time = cursor.getString(3);
                boolean isFolder = cursor.getInt(4) == 1;
                list.add(new PdfItem(uri, name, path, time, isFolder));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return list;
    }
}