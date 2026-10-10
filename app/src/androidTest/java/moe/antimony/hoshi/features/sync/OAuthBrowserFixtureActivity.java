package moe.antimony.hoshi.features.sync;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class OAuthBrowserFixtureActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        if ("redirect".equals(intent.getAction())) {
            startActivity(new Intent()
                .setClassName(intent.getStringExtra("targetPackage"), "moe.antimony.hoshi.features.sync.GoogleDriveOAuthRedirectActivity")
                .setData(intent.getData()));
            finish();
        } else if ("cancel".equals(intent.getAction())) {
            finish();
        }
    }
}
