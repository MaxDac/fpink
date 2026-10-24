package com.fpink.capture.acceptance;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import java.io.InputStream;

public final class DiagnosticsReceiverActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        Intent result = new Intent().putExtra("uid", Process.myUid());
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            result.putExtra("readable", input != null && input.read() >= 0);
        } catch (Exception error) {
            result.putExtra("readable", false);
        }
        try {
            android.os.ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "rw");
            if (descriptor != null) descriptor.close();
            result.putExtra("writeDenied", false);
        } catch (SecurityException error) {
            result.putExtra("writeDenied", true);
        } catch (Exception error) {
            result.putExtra("writeDenied", false);
        }
        Uri sibling = Uri.parse(getIntent().getStringExtra("sibling"));
        try (InputStream ignored = getContentResolver().openInputStream(sibling)) {
            result.putExtra("siblingDenied", false);
        } catch (SecurityException error) {
            result.putExtra("siblingDenied", true);
        } catch (Exception error) {
            result.putExtra("siblingDenied", false);
        }
        setResult(RESULT_OK, result);
        finish();
    }
}
