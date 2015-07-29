package com.mopub.mobileads.util;

import org.apache.http.client.HttpClient;

public class HttpClients {
    public static void safeShutdown(final HttpClient httpClient) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (httpClient != null && httpClient.getConnectionManager() != null) {
                    httpClient.getConnectionManager().shutdown();
                }
            }
        }).start();
    }
}
