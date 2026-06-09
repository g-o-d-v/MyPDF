package com.nless.mypdf;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class PdfDbHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "mypdf.db";
    private static final int DATABASE_VERSION = 3;

    public PdfDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE items (uri TEXT PRIMARY KEY, name TEXT, path TEXT, time TEXT, isFolder INTEGER, lastViewed INTEGER DEFAULT 0, isHidden INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE items ADD COLUMN lastViewed INTEGER DEFAULT 0");
        if (oldVersion < 3) db.execSQL("ALTER TABLE items ADD COLUMN isHidden INTEGER DEFAULT 0");
    }

    public void insertItem(PdfItem item) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("uri", item.uri.toString());
        values.put("name", item.name);
        values.put("path", item.path);
        values.put("time", item.time);
        values.put("isFolder", item.isFolder ? 1 : 0);
        values.put("isHidden", 0);
        db.insertWithOnConflict("items", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void recordViewHistory(PdfItem item) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("lastViewed", System.currentTimeMillis());

        // 🌟 修复核心：不再用 URI 字符串判断唯一性，改用清洗后的 path 和 name 联合查询
        Cursor cursor = db.query("items", new String[]{"uri"}, "path=? AND name=?",
                new String[]{item.path, item.name}, null, null, null);
        boolean exists = (cursor.getCount() > 0);

        if (exists) {
            if (cursor.moveToFirst()) {
                String existingUri = cursor.getString(cursor.getColumnIndexOrThrow("uri"));
                // 路径和名字一致，直接更新已有记录的时间戳，将其合并为一个文件处理
                db.update("items", values, "uri=?", new String[]{existingUri});
            }
            cursor.close();
        } else {
            cursor.close();
            // 如果是全新的文件，则作为隐式历史记录插入
            values.put("uri", item.uri.toString());
            values.put("name", item.name);
            values.put("path", item.path);
            values.put("time", item.time);
            values.put("isFolder", 0);
            values.put("isHidden", 1); // 1表示隐藏层，不在首页直接展示
            db.insert("items", null, values);
        }
    }

    public void deleteItem(String uriString) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete("items", "uri=?", new String[]{uriString});
    }

    public boolean exists(String uriString) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query("items", new String[]{"uri"}, "uri=?", new String[]{uriString}, null, null, null);
        boolean exists = (cursor.getCount() > 0);
        cursor.close();
        return exists;
    }

    private List<PdfItem> parseCursor(Cursor cursor, boolean isRecentMode) {
        List<PdfItem> list = new ArrayList<>();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());
        if (cursor.moveToFirst()) {
            do {
                Uri uri = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow("uri")));
                String name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                String path = cursor.getString(cursor.getColumnIndexOrThrow("path"));
                String time = cursor.getString(cursor.getColumnIndexOrThrow("time"));

                // 🌟 核心修改：如果是最近查看模式，将时间替换为最后阅读时间
                if (isRecentMode) {
                    long lastViewedMs = cursor.getLong(cursor.getColumnIndexOrThrow("lastViewed"));
                    time = dateFormat.format(new Date(lastViewedMs));
                }

                boolean isFolder = cursor.getInt(cursor.getColumnIndexOrThrow("isFolder")) == 1;
                list.add(new PdfItem(uri, name, path, time, isFolder));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return list;
    }

    public List<PdfItem> getAllItems() {
        Cursor cursor = this.getReadableDatabase().rawQuery("SELECT * FROM items WHERE isHidden = 0", null);
        return parseCursor(cursor, false);
    }

    // 🌟 核心修改：获取最近查看数据记录
    public List<PdfItem> getRecentItems() {
        SQLiteDatabase db = this.getReadableDatabase();
        // 🌟 优化核心：通过 GROUP BY path, name 进行强力去重，并用 MAX(lastViewed) 确保拿到的是最新的阅读时间
        Cursor cursor = db.rawQuery(
                "SELECT *, MAX(lastViewed) as max_viewed FROM items " +
                        "WHERE lastViewed > 0 AND isFolder = 0 " +
                        "GROUP BY path, name " +
                        "ORDER BY max_viewed DESC " +
                        "LIMIT 10", null);
        return parseCursor(cursor, true);
    }
}