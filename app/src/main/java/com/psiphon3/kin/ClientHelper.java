package com.psiphon3.kin;

import com.psiphon3.psiphonlibrary.Utils;

import io.reactivex.Maybe;
import io.reactivex.Single;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;
import kin.sdk.exception.CorruptedDataException;
import kin.sdk.exception.CreateAccountException;
import kin.sdk.exception.CryptoException;
import kin.sdk.exception.DeleteAccountException;

class ClientHelper {
    private final static String EXPORT_KIN_ACCOUNT_PASSPHRASE = "correct-horse-battery-staple";

    private final KinClient kinClient;

    ClientHelper(KinClient kinClient) {
        this.kinClient = kinClient;
    }

    /**
     * Gets a Kin account for this device. Will try to use a saved account first, but if none are
     * found it will create a new account.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @return The account for this device.
     */
    Single<KinAccount> getAccount() {
        return accountMaybe()
                .switchIfEmpty(
                        Maybe.fromCallable(kinClient::addAccount)
                                .doOnSuccess(__ -> Utils.MyLog.g("KinManager: created new account")))
                .toSingle();
    }

    /**
     * Get a Kin account only if it exists wrapped in Maybe
     * @return account or nothing
     */
    Maybe<KinAccount> accountMaybe() {
        try {
            if (kinClient.hasAccount()) {
                return Maybe.just(kinClient.getAccount(0));
            }
            if (doesExportedAccountExist()) {
                return Maybe.just(importAccount(retrieveAccountFromDisk()));
            }
            return Maybe.empty();
        } catch (Exception e) {
            return Maybe.error(e);
        }
    }

    /**
     * Deletes the Kin account. Silently handles failures.
     */
    void deleteAccount() {
        Utils.MyLog.g("KinManager: deleting account");
        if (!kinClient.hasAccount()) {
            Utils.MyLog.g("KinManager: tried to delete an account when no account existed");
            return;
        }

        try {
            kinClient.deleteAccount(0);
        } catch (DeleteAccountException e) {
            Utils.MyLog.g("KinManager: delete account error: " + e);
        }
    }

    //
    // TODO: Actually add the code to save to disk
    //

    private String exportAccount(KinAccount account) throws CryptoException {
        return account.export(EXPORT_KIN_ACCOUNT_PASSPHRASE);
    }

    private KinAccount importAccount(String exportedJson) throws CorruptedDataException, CreateAccountException, CryptoException {
        return kinClient.importAccount(exportedJson, EXPORT_KIN_ACCOUNT_PASSPHRASE);
    }

    private boolean doesExportedAccountExist() {
        return false;
    }

    private void saveAccountToDisk(String exportedJson) {

    }

    private String retrieveAccountFromDisk() {
        return "";
    }
}
