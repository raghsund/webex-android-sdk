package com.ciscospark.core;


import android.util.Log;

import com.cisco.spark.android.core.Application;

public class SparkApplication extends Application {
    private static SparkApplication instance;
    private SparkApplicationDelegate delegate;

    private static final String TAG = "SparkApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        Log.d(TAG, "before daggerInit");
        delegate = new SparkApplicationDelegate(this);
        delegate.onCreate();
        Log.i(TAG, "onCreate: ->after daggerInit");
    }

    public static SparkApplication getInstance() {
        return instance;
    }

    public void inject(Object object) {
        delegate.inject(object);
    }
}