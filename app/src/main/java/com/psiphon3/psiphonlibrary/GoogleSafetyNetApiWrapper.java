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

import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class GoogleSafetyNetApiWrapper implements ConnectionCallbacks, OnConnectionFailedListener{
    private static final int API_REQUEST_OK = 0x00;
    private static final int API_REQUEST_FAILED = 0x01;
    private static final int API_CONNECT_FAILED = 0x02;

    private static GoogleSafetyNetApiWrapper mInstance;
    private AtomicBoolean mCheckInFlight;
    private GoogleApiClient mGoogleApiClient;
    private String mLastPayload;

    public Object clone() throws CloneNotSupportedException
    {
        throw new CloneNotSupportedException();
    }

    private  GoogleSafetyNetApiWrapper(Context context) {
        // Create the Google API Client.
        mGoogleApiClient = new GoogleApiClient.Builder(context)
                .addApi(SafetyNet.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
        mCheckInFlight = new AtomicBoolean(false);
    }

    public static synchronized  GoogleSafetyNetApiWrapper getInstance(Context context) {
        if(mInstance == null) {
            mInstance = new GoogleSafetyNetApiWrapper(context);
        }
        return mInstance;
    }

    public void connect() {
        if (!mCheckInFlight.compareAndSet(false, true)) {
            return;
        }

        if(!TextUtils.isEmpty(mLastPayload)) {
            setPayload(mLastPayload);
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
        byte[] nonce = new byte[32];
        rnd.nextBytes(nonce);

        SafetyNet.SafetyNetApi.attest(mGoogleApiClient, nonce)
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
                            onSafetyNetCheckNotify(API_REQUEST_FAILED, status.getStatusMessage());
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
        //try to reconnect
        connect();
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        onSafetyNetCheckNotify(API_CONNECT_FAILED, connectionResult.getErrorMessage());
    }

    private void onSafetyNetCheckNotify(int status, String payload) {
        JSONObject checkData = new JSONObject();
        try
        {
            checkData.put("status", status);
            checkData.put("payload", payload);
        }
        catch (JSONException e)
        {
            throw new RuntimeException(e);
        }
        setPayload(checkData.toString());
    }

    private void setPayload(String payload) {
        mLastPayload = payload;
        TunnelManager tunnelManager = PsiphonData.getPsiphonData().getCurrentTunnelManager();
        if(tunnelManager != null) {
            tunnelManager.setClientVerificationResult(payload);
        }
        mCheckInFlight.set(false);
    }
}
