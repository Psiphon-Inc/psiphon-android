package com.psiphon3.kin;

import com.psiphon3.psiphonlibrary.Utils;

import io.reactivex.Single;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;
import kin.sdk.exception.DeleteAccountException;

class ClientHelper {
    private final KinClient kinClient;

    ClientHelper(KinClient kinClient) {
        this.kinClient = kinClient;
    }

    /**
     * Gets a Kin account for this device. Will try to use a saved account first, but if none are
     * found it will create a new account and register it with the server.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @return The account for this device.
     */
    Single<KinAccount> getAccount() {
        try {
            if (kinClient.hasAccount()) {
                return Single.just(kinClient.getAccount(0));
            }

            return Single.just(kinClient.addAccount());
        } catch (Exception e) {
            return Single.error(e);
        }
    }

    /**
     * Deletes the Kin account. Silently handles failures.
     */
    void deleteAccount() {
        if (!kinClient.hasAccount()) {
            Utils.MyLog.d("Tried to delete an account when no account existed");
            return;
        }

        try {
            kinClient.deleteAccount(0);
        } catch (DeleteAccountException e) {
            // TODO: Care?
        }
    }
}
