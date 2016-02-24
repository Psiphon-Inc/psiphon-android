package com.mopub.common;

import android.app.Activity;
import android.support.annotation.NonNull;

/**
 * This is a uniform interface to 3rd party SDKs that need to know when Activity lifecycle events
 * occur.
 */
public interface LifecycleListener {

    public void onCreate(@NonNull Activity activity);
    public void onStart(@NonNull Activity activity);
    public void onPause(@NonNull Activity activity);
    public void onResume(@NonNull Activity activity);

    public void onRestart(@NonNull Activity activity);
    public void onStop(@NonNull Activity activity);
    public void onDestroy(@NonNull Activity activity);
    public void onBackPressed(@NonNull Activity activity);
}
