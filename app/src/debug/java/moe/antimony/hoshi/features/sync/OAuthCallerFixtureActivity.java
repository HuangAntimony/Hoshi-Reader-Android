package moe.antimony.hoshi.features.sync;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import java.util.concurrent.CountDownLatch;

public class OAuthCallerFixtureActivity extends Activity {
    public static CountDownLatch completed;
    public static Instrumentation.ActivityResult result;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            startActivityForResult(new Intent(this, GoogleDriveBrowserAuthActivity.class)
                .putExtra("authorizationUri", getIntent().getStringExtra("authorizationUri"))
                .setData(getIntent().getData()), 1);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        result = new Instrumentation.ActivityResult(resultCode, data);
        completed.countDown();
        finishAndRemoveTask();
    }
}
