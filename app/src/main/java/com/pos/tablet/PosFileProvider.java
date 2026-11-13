package com.pos.tablet;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 輕量級 APK 安裝專用 FileProvider（無需 AndroidX 依賴，支援 Android 8~14 OTA 線上更新）
 */
public class PosFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.pos.tablet.fileprovider";

    public static Uri getApkUri(Context ctx, String fileName) {
        return new Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath("ota")
            .appendPath(fileName)
            .build();
    }

    public static File getOtaDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "ota");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (getContext() == null || uri == null) throw new FileNotFoundException("Invalid context/uri");
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("..") || name.contains("/")) {
            throw new FileNotFoundException("Invalid file name");
        }
        File target = new File(getOtaDir(getContext()), name);
        if (!target.exists()) throw new FileNotFoundException("APK not found: " + name);
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String name = uri != null ? uri.getLastPathSegment() : "update.apk";
        long size = 0;
        if (getContext() != null && name != null) {
            File f = new File(getOtaDir(getContext()), name);
            if (f.exists()) size = f.length();
        }
        MatrixCursor mc = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        mc.addRow(new Object[]{name, size});
        return mc;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
