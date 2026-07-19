package com.nless.mypdf.data;

import android.net.Uri;

public class PdfItem {
    public Uri uri;         // 文件的系统URI
    public String name;     // 文件名
    public String path;     // 绝对路径（展示用）
    public String time;     // 修改时间
    public boolean isFolder;// 是否是文件夹

    public PdfItem(Uri uri, String name, String path, String time, boolean isFolder) {
        this.uri = uri;
        this.name = name;
        this.path = path;
        this.time = time;
        this.isFolder = isFolder;
    }
}