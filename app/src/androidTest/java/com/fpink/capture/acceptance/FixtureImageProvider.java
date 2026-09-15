package com.fpink.capture.acceptance;

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
import java.util.List;
import java.util.UUID;

/**
 * This separate-UID fixture uses only framework/JDK APIs: AGP omits the target app's
 * shared Kotlin/AndroidX dependencies from the instrumentation APK's standalone process.
 */
public final class FixtureImageProvider extends ContentProvider {
    @Override public boolean onCreate() {
        return true;
    }

    @Override public String getType(Uri uri) {
        if ("image.png".equals(uri.getLastPathSegment())) return "image/png";
        if ("image.jpg".equals(uri.getLastPathSegment())) return "image/jpeg";
        return "application/octet-stream";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only fixture");
        return ParcelFileDescriptor.open(fixtureFile(getContext(), uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(
            Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = fixtureFile(getContext(), uri);
        String[] columns = projection != null ? projection
                : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(file.getName());
            else if (OpenableColumns.SIZE.equals(column)) row.add(file.length());
            else row.add(null);
        }
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only fixture");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only fixture");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only fixture");
    }

    static File fixtureFile(Context context, Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || !UUID.fromString(segments.get(0)).toString().equals(segments.get(0))) {
            throw new IllegalArgumentException("Invalid fixture identifier");
        }
        String name = segments.get(1);
        if (!name.equals("image.png") && !name.equals("image.jpg") && !name.equals("image.bin")) {
            throw new IllegalArgumentException("Invalid fixture filename");
        }
        return new File(context.getCacheDir(), "acceptance-provider/" + segments.get(0) + "/" + name);
    }
}
