package com.fpink.capture.acceptance;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/** The OS grants the result URI from this test-APK UID to the target application's activity. */
public final class FixturePickerActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String id = getIntent().getStringExtra("id");
        String name = getIntent().getStringExtra("name");
        Uri uri = Uri.parse("content://" + getPackageName() + ".images/" + id + "/" + name);
        File file = FixtureImageProvider.fixtureFile(this, uri);
        try {
            if (getIntent().getBooleanExtra("remove", false)) {
                revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (file.exists() && !file.delete()) throw new IOException("Could not remove fixture");
                File directory = file.getParentFile();
                if (directory.exists() && !directory.delete()) throw new IOException("Could not remove fixture directory");
                setResult(RESULT_OK);
            } else {
                if (!file.getParentFile().mkdirs()) throw new IOException("Could not create fixture directory");
                byte[] bytes = getIntent().getByteArrayExtra("bytes");
                try (FileOutputStream output = new FileOutputStream(file)) {
                    if (bytes != null) output.write(bytes);
                }
                long length = getIntent().getLongExtra("length", -1);
                if (length >= 0) {
                    try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
                        output.setLength(length);
                    }
                }
                Intent result = new Intent().setData(uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra("fixtureUid", Process.myUid());
                result.setClipData(ClipData.newRawUri("Synthetic acceptance image", uri));
                setResult(RESULT_OK, result);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not prepare test-owned fixture", failure);
        }
        finish();
    }
}
