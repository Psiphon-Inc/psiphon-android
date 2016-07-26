/*
 * Copyright (c) 2015, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psiphonlibrary;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.safetynet.SafetyNet;
import com.google.android.gms.safetynet.SafetyNetApi;
import com.psiphon3.psiphonlibrary.obfuscation.AESObfuscator;
import com.psiphon3.psiphonlibrary.obfuscation.Base64;
import com.psiphon3.psiphonlibrary.obfuscation.Base64DecoderException;
import com.psiphon3.psiphonlibrary.obfuscation.Obfuscator;
import com.psiphon3.psiphonlibrary.obfuscation.ValidationException;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class GoogleSafetyNetApiWrapper implements ConnectionCallbacks, OnConnectionFailedListener{
    private static final int API_REQUEST_OK = 0x00;
    private static final int API_REQUEST_FAILED = 0x01;
    private static final int API_CONNECT_FAILED = 0x02;
    private static final String ATTESTATION_RESULT_CACHE_FILE = "attestationResultCacheFile";
    private static final String KEY_ATTESTATION_RESULT = "keyAttestationResult";
    private static final byte[] SALT = {18, 43, -35, 57, -14, 121, 127, -59, 58, -29, 11, -108, 103, 87, 72, -17, 104, -121, -111, 53};
    private static final int MAX_CACHED_ENTRIES = 20;

    private static GoogleSafetyNetApiWrapper mInstance;
    private AtomicBoolean mCheckInFlight;
    private GoogleApiClient mGoogleApiClient;
    private WeakReference<TunnelManager> mTunnelManager;
    private CacheMap<String, CacheEntry> mCacheMap;
    private String mLastServerNonce;
    private long mLastTtlSeconds;
    private Obfuscator mObfuscator;

    public Object clone() throws CloneNotSupportedException
    {
        throw new CloneNotSupportedException();
    }

    private GoogleSafetyNetApiWrapper(Context context) {
        // Create the Google API Client.
        mGoogleApiClient = new GoogleApiClient.Builder(context)
                .addApi(SafetyNet.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
        mCheckInFlight = new AtomicBoolean(false);
        mCacheMap = new CacheMap<>();

        String deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        mObfuscator = new AESObfuscator(SALT, context.getPackageName(), deviceId);

        loadSavedCache(context);
    }

    public static synchronized GoogleSafetyNetApiWrapper getInstance(Context context) {
        if(mInstance == null) {
            mInstance = new GoogleSafetyNetApiWrapper(context);
        }
        return mInstance;
    }

    // Limited size LinkedHashMap where size <= MAX_CACHED_ENTRIES
    private static class CacheMap<K,V> extends LinkedHashMap<K,V> {
        @Override
        protected boolean removeEldestEntry(Entry eldest) {
            return size() > MAX_CACHED_ENTRIES;
        }
    }

    private static class CacheEntry implements Serializable{
        private static final long serialVersionUID = 1L;
        private String payload;
        private long expirationTimestamp;


        CacheEntry(String payload, long expirationTimestamp) {
            this.payload = payload;
            this.expirationTimestamp = expirationTimestamp;
        }
    }


    private void loadSavedCache(Context context) {
        FileInputStream fis;
        try {
            fis = context.openFileInput(ATTESTATION_RESULT_CACHE_FILE);
            ObjectInputStream ois = new ObjectInputStream(fis);
            mCacheMap = (CacheMap<String, CacheEntry>) ois.readObject();
            ois.close();
            fis.close();
        } catch (IOException | ClassNotFoundException e) {
            //TODO: handle this
        }

    }

    protected void saveCache(Context context) {
        if (mCacheMap != null && mCacheMap.size() > 0) {
            FileOutputStream fos;
            try {
                fos = context.openFileOutput(ATTESTATION_RESULT_CACHE_FILE, Context.MODE_PRIVATE);
                ObjectOutputStream oos = new ObjectOutputStream(fos);
                oos.writeObject(mCacheMap);
                oos.close();
                fos.flush();
                fos.close();
            } catch (IOException e) {
                //TODO: handle this
            }
        }
    }

    private boolean setPayloadFromCache() {
        CacheEntry entry = mCacheMap.get(mLastServerNonce);

        if(entry == null) {
            return false;
        }

        if(SystemClock.elapsedRealtime() > entry.expirationTimestamp) {
            mCacheMap.remove(mLastServerNonce);
            return false;
        }

        try {
            String unobfuscatedPayload = mObfuscator.unobfuscate(entry.payload, KEY_ATTESTATION_RESULT);
            setPayload(unobfuscatedPayload, false);
            return true;
        } catch (ValidationException e) {
        }
        return false;
    }

    public void verify(TunnelManager manager, String serverNonce, int ttlSeconds, boolean resetCache) {
        mTunnelManager = new WeakReference<>(manager);

        if (!mCheckInFlight.compareAndSet(false, true)) {
            return;
        }

        if (resetCache) {
            mCacheMap.remove(serverNonce);
        }
        
        mLastServerNonce = serverNonce;
        mLastTtlSeconds = (long) ttlSeconds;

        if(setPayloadFromCache()) {
            return;
        }

        if (!mGoogleApiClient.isConnecting() && !mGoogleApiClient.isConnected()) {
            mGoogleApiClient.connect();
        }
    }

    public void disconnect() {
        if (mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
        mCheckInFlight.set(false);
    }

    private void  doSafetyNetCheck() {
        SecureRandom rnd = new SecureRandom();
        byte[] clientNonce = new byte[32];
        rnd.nextBytes(clientNonce);

        byte[] serverNonce = null;
        if(!TextUtils.isEmpty(mLastServerNonce)) {
            serverNonce = Utils.Base64.decode(mLastServerNonce);
        }

        final byte[] attestationNonce;

        //              Attestation nonce:
        //
        //      client nonce               server nonce
        // [<32 bytes of random data> + <any size byte array>]

        if (serverNonce != null) {
            attestationNonce = new byte[32 + serverNonce.length];
            System.arraycopy(clientNonce, 0, attestationNonce, 0, clientNonce.length);
            System.arraycopy(serverNonce, 0, attestationNonce, clientNonce.length, serverNonce.length);
        } else {
            attestationNonce = clientNonce;
        }

        SafetyNet.SafetyNetApi.attest(mGoogleApiClient, attestationNonce)
                .setResultCallback(new ResultCallback<SafetyNetApi.AttestationResult>() {
                    @Override
                    public void onResult(final SafetyNetApi.AttestationResult result) {
                        Status status = result.getStatus();

                        //JSON Web Signature format
                        final String jwsResult = result.getJwsResult();
                        if (status.isSuccess() && !TextUtils.isEmpty(jwsResult)) {
                            onSafetyNetCheckNotify(API_REQUEST_OK, jwsResult);
                        } else {
                            // An error occurred while communicating with the SafetyNet Api
                            onSafetyNetCheckNotify(API_REQUEST_FAILED, status.toString());
                        }
                    }
                });
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        doSafetyNetCheck();
    }

    @Override
    public void onConnectionSuspended(int i) {
        // according to a Google engineer we shouldn't try to reconnect
        // in this case: http://stackoverflow.com/a/26147518
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        onSafetyNetCheckNotify(API_CONNECT_FAILED, connectionResult.toString());
    }

    private void onSafetyNetCheckNotify(int status, String attestationResult) {
        JSONObject checkData = new JSONObject();
        try
        {
            checkData.put("status", status);
            checkData.put("payload", attestationResult);
        }
        catch (JSONException e)
        {
            throw new RuntimeException(e);
        }

        // cache payload only if attestation request has completed
        // and result passes basic JWT validation
        setPayload(checkData.toString(),
                (status == API_REQUEST_OK) && isValidJWTResult(attestationResult));
    }

    private boolean isValidJWTResult(String jwtResult) {
        // perform basic validation:
        // JWT must be 3 base64 encoded strings separated by '.'
        // first(header) and second(payload) parts must be valid JSON objects
        final String[] jwtParts = jwtResult.split("\\.");

        if (jwtParts.length != 3) {
            return false;
        }
        for (int i = 0; i < jwtParts.length; i++) {
            byte[] decoded;
            try {
                decoded = Base64.decodeWebSafe(jwtParts[i]);
            } catch (Base64DecoderException e) {
                return false;
            }
            if (i < 2) {
                String jsonString;
                try {
                    jsonString = new String(decoded, "UTF-8");
                    new JSONObject(jsonString);
                } catch (UnsupportedEncodingException | JSONException e) {
                    return false;
                }
            }
        }
        return true;
    }

    private void setPayload(String payload, boolean shouldCache) {
        if(shouldCache) {
            String obfuscatedPayload = mObfuscator.obfuscate(payload, KEY_ATTESTATION_RESULT);
            CacheEntry entry = new CacheEntry(obfuscatedPayload, SystemClock.elapsedRealtime() + mLastTtlSeconds * 1000);
            mCacheMap.put(mLastServerNonce, entry);
        }

        TunnelManager tunnelManager = mTunnelManager.get();
        if (tunnelManager != null) {
            tunnelManager.setClientVerificationResult(payload);
        }
        mCheckInFlight.set(false);
    }
}