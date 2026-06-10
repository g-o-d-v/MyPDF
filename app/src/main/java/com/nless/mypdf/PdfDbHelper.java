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
    // 🌟 升级版本号至 6，引入 exclusion 机制
    private static final int DATABASE_VERSION = 6;

    public static final int DISPLAY_MODE_HOME = 0;
    public static final int DISPLAY_MODE_RECENT = 1;
    public static final int DISPLAY_MODE_FAVORITE = 2;

    public PdfDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE items (" +
                "uri TEXT PRIMARY KEY, " +
                "name TEXT, " +
                "path TEXT, " +
                "time TEXT, " +
                "isFolder INTEGER, " +
                "createTime INTEGER DEFAULT 0, " +
                "lastModified INTEGER DEFAULT 0, " +
                "inHome INTEGER DEFAULT 0, " +
                "lastViewed INTEGER DEFAULT 0, " +
                "inRecent INTEGER DEFAULT 0, " +
                "favoriteTime INTEGER DEFAULT 0, " +
                "inFavorite INTEGER DEFAULT 0, " +
                "isHidden INTEGER DEFAULT 0, " +
                "isExcluded INTEGER DEFAULT 0" +  // 🌟 新增：专门用于标记从首页或文件夹内被显式“移除”的文件
                ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS items");
        onCreate(db);
    }

    private String getExistingUri(String path, String name) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query("items", new String[]{"uri"}, "path=? AND name=?",
                new String[]{path, name}, null, null, null);
        String uriStr = null;
        if (cursor.moveToFirst()) {
            uriStr = cursor.getString(cursor.getColumnIndexOrThrow("uri"));
        }
        cursor.close();
        return uriStr;
    }

    public void insertOrUpdateHomeItem(PdfItem item) {
        SQLiteDatabase db = this.getWritableDatabase();
        String targetUri = getExistingUri(item.path, item.name);

        ContentValues values = new ContentValues();
        values.put("name", item.name);
        values.put("path", item.path);
        values.put("isFolder", item.isFolder ? 1 : 0);
        values.put("inHome", 1);
        values.put("isExcluded", 0); // 重新添加时，恢复排除状态

        if (targetUri != null) {
            db.update("items", values, "uri=?", new String[]{targetUri});
        } else {
            values.put("uri", item.uri.toString());
            values.put("time", item.time);
            values.put("createTime", System.currentTimeMillis());
            values.put("lastModified", 0);
            db.insertWithOnConflict("items", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    // 🌟 核心重构：移除方法接收完整的 PdfItem 对象，完美兼顾首页列表和文件夹深处文件的虚拟移除
    public void removeHomeItem(PdfItem item) {
        SQLiteDatabase db = this.getWritableDatabase();
        String targetUri = getExistingUri(item.path, item.name);

        ContentValues values = new ContentValues();
        values.put("inHome", 0);
        values.put("isExcluded", 1); // 🌟 核心：统一打上排除印记，使其在文件夹视图和搜索中不可见

        if (targetUri != null) {
            db.update("items", values, "uri=?", new String[]{targetUri});
        } else {
            // 如果是文件夹内部从未点开过的纯净文件，在移除时插入一条专属的隐式排除快照
            values.put("uri", item.uri.toString());
            values.put("name", item.name);
            values.put("path", item.path);
            values.put("time", item.time);
            values.put("isFolder", 0);
            values.put("createTime", System.currentTimeMillis());
            values.put("lastModified", 0);
            db.insert("items", null, values);
        }
        clearOrphanRecords(db);
    }

    public void recordViewHistory(PdfItem item) {
        SQLiteDatabase db = this.getWritableDatabase();
        String targetUri = getExistingUri(item.path, item.name);

        ContentValues values = new ContentValues();
        values.put("lastViewed", System.currentTimeMillis());
        values.put("inRecent", 1);

        if (targetUri != null) {
            db.update("items", values, "uri=?", new String[]{targetUri});
        } else {
            values.put("uri", item.uri.toString());
            values.put("name", item.name);
            values.put("path", item.path);
            values.put("time", item.time);
            values.put("isFolder", 0);
            values.put("createTime", System.currentTimeMillis());
            values.put("lastModified", 0);
            values.put("inHome", 0);
            values.put("isHidden", 1);
            db.insert("items", null, values);
        }
    }

    public void removeRecentItem(String uriString) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("inRecent", 0);
        values.put("lastViewed", 0);
        db.update("items", values, "uri=?", new String[]{uriString});
        clearOrphanRecords(db);
    }

    // 🌟 新增：供外部遍历文件夹时，快速鉴别某一相对物理路径的文件是否已被执行过虚拟移除
    public boolean isFileExcluded(String path, String name) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query("items", new String[]{"uri"}, "path=? AND name=? AND isExcluded=1",
                new String[]{path, name}, null, null, null);
        boolean excluded = (cursor.getCount() > 0);
        cursor.close();
        return excluded;
    }

    public void updateLastModifiedTime(String uriString) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("lastModified", System.currentTimeMillis());
        db.update("items", values, "uri=?", new String[]{uriString});
    }

    public void deleteItem(String uriString) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete("items", "uri=?", new String[]{uriString});
    }

    private void clearOrphanRecords(SQLiteDatabase db) {
        // 🌟 核心保护：只有当文件在任何视图下都没状态，并且也没有被排除时，才可以安全硬删除
        db.delete("items", "inHome = 0 AND inRecent = 0 AND inFavorite = 0 AND isExcluded = 0", null);
    }

    public boolean existsInHome(String uriString) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query("items", new String[]{"uri"}, "uri=? AND inHome=1", null, null, null, null);
        boolean exists = (cursor.getCount() > 0);
        cursor.close();
        return exists;
    }

    private List<PdfItem> parseCursor(Cursor cursor, int displayMode) {
        List<PdfItem> list = new ArrayList<>();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());
        if (cursor.moveToFirst()) {
            do {
                Uri uri = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow("uri")));
                String name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                String path = cursor.getString(cursor.getColumnIndexOrThrow("path"));

                String displayTime = "";
                if (displayMode == DISPLAY_MODE_RECENT) {
                    long lastViewedMs = cursor.getLong(cursor.getColumnIndexOrThrow("lastViewed"));
                    displayTime = dateFormat.format(new Date(lastViewedMs));
                } else if (displayMode == DISPLAY_MODE_FAVORITE) {
                    long favMs = cursor.getLong(cursor.getColumnIndexOrThrow("favoriteTime"));
                    if(favMs > 0) displayTime = dateFormat.format(new Date(favMs));
                } else {
                    long modifiedMs = cursor.getLong(cursor.getColumnIndexOrThrow("lastModified"));
                    if (modifiedMs > 0) {
                        displayTime = dateFormat.format(new Date(modifiedMs));
                    } else {
                        displayTime = cursor.getString(cursor.getColumnIndexOrThrow("time"));
                    }
                }

                boolean isFolder = cursor.getInt(cursor.getColumnIndexOrThrow("isFolder")) == 1;
                list.add(new PdfItem(uri, name, path, displayTime, isFolder));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return list;
    }

    public List<PdfItem> getAllHomeItems() {
        Cursor cursor = this.getReadableDatabase().rawQuery("SELECT * FROM items WHERE inHome = 1 ORDER BY createTime DESC", null);
        return parseCursor(cursor, DISPLAY_MODE_HOME);
    }

    public List<PdfItem> getRecentItems() {
        Cursor cursor = this.getReadableDatabase().rawQuery(
                "SELECT *, MAX(lastViewed) as max_viewed FROM items " +
                        "WHERE inRecent = 1 AND isFolder = 0 " +
                        "GROUP BY path, name " +
                        "ORDER BY max_viewed DESC LIMIT 10", null);
        return parseCursor(cursor, DISPLAY_MODE_RECENT);
    }

    // 🌟 1. 检查某个文件是否已被收藏（基于路径和文件名联合查询）
    public boolean isFavorite(String path, String name) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query("items", new String[]{"uri"}, "path=? AND name=? AND inFavorite=1",
                new String[]{path, name}, null, null, null);
        boolean isFav = (cursor.getCount() > 0);
        cursor.close();
        return isFav;
    }

    // 🌟 2. 切换收藏状态（开启/取消）
    // 如果是从文件夹内部直接点开的未添加文件，点击收藏时会自动生成一条隐式记录
    public boolean toggleFavorite(String path, String name, String uriStr, String originalTime) {
        SQLiteDatabase db = this.getWritableDatabase();
        String targetUri = getExistingUri(path, name);

        boolean nowFavorite = !isFavorite(path, name);

        ContentValues values = new ContentValues();
        values.put("inFavorite", nowFavorite ? 1 : 0);
        values.put("favoriteTime", nowFavorite ? System.currentTimeMillis() : 0);

        if (targetUri != null) {
            db.update("items", values, "uri=?", new String[]{targetUri});
        } else {
            // 隐式插入从未记录过的深层文件
            values.put("uri", uriStr);
            values.put("name", name);
            values.put("path", path);
            values.put("time", originalTime);
            values.put("isFolder", 0);
            values.put("createTime", System.currentTimeMillis());
            db.insert("items", null, values);
        }
        clearOrphanRecords(db);
        return nowFavorite; // 返回当前最新的收藏状态
    }

    // 🌟 3. 从收藏列表中移除（供长按菜单或取消收藏时软删除调用）
    public void removeFavoriteItem(String uriString) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("inFavorite", 0);
        values.put("favoriteTime", 0);
        db.update("items", values, "uri=?", new String[]{uriString});
        clearOrphanRecords(db);
    }

    // 🌟 4. 获取收藏列表数据（按收藏时间降序排列，强制使用收藏时间显示）
    public List<PdfItem> getFavoriteItems() {
        Cursor cursor = this.getReadableDatabase().rawQuery(
                "SELECT * FROM items WHERE inFavorite = 1 ORDER BY favoriteTime DESC", null);
        return parseCursor(cursor, DISPLAY_MODE_FAVORITE);
    }
}