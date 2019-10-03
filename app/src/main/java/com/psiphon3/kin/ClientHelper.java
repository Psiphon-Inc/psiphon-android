package com.psiphon3.kin;

import com.psiphon3.psiphonlibrary.Utils;

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
    private final ServerCommunicator serverCommunicator;

    ClientHelper(KinClient kinClient, ServerCommunicator serverCommunicator) {
        this.kinClient = kinClient;
        this.serverCommunicator = serverCommunicator;
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

            if (doesExportedAccountExist()) {
                return Single.just(importAccount(retrieveAccountFromDisk()));
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

    private Single<KinAccount> createKinAccount() {
        try {
            KinAccount account = kinClient.addAccount();
            String address = account.getPublicAddress();
            if (address == null) {
                return Single.error(new Exception("failed to add a new KinAccount"));
            }

            return serverCommunicator.createAccount(address).toSingle(() -> account);
        } catch (CreateAccountException e) {
            return Single.error(e);
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
