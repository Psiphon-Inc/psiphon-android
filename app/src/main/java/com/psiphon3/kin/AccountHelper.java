package com.psiphon3.kin;

import io.reactivex.Single;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;
import kin.sdk.exception.CorruptedDataException;
import kin.sdk.exception.CreateAccountException;
import kin.sdk.exception.CryptoException;

class AccountHelper {
    final static Double CREATE_ACCOUNT_FUND_AMOUNT = 1000d;
    private final static String EXPORT_KIN_ACCOUNT_PASSPHRASE = "correct-horse-battery-staple";

    /**
     * Gets a Kin account for this device. Will try to use a saved account first, but if none are
     * found it will create a new account and register it with the server.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param kinClient          the KinClient to be used for the account
     * @param serverCommunicator the communicator for creating the account if needed
     * @return The account for this device.
     */
    static Single<KinAccount> getAccount(KinClient kinClient, ServerCommunicator serverCommunicator) {
        try {
            if (kinClient.hasAccount()) {
                return Single.just(kinClient.getAccount(0));
            }

            if (doesExportedAccountExist()) {
                return Single.just(importAccount(kinClient, retrieveAccountFromDisk()));
            }

            return createKinAccount(kinClient, serverCommunicator);
        } catch (Exception e) {
            return Single.error(e);
        }
    }

    private static Single<KinAccount> createKinAccount(KinClient kinClient, ServerCommunicator serverCommunicator) {
        try {
            KinAccount account = kinClient.addAccount();
            String address = account.getPublicAddress();
            if (address == null) {
                return Single.error(new Exception("failed to add a new KinAccount"));
            }

            return serverCommunicator.createAccount(address, CREATE_ACCOUNT_FUND_AMOUNT).toSingle(() -> account);
        } catch (CreateAccountException e) {
            return Single.error(e);
        }
    }

    //
    // TODO: Actually add the code to save to disk
    //

    private static String exportAccount(KinAccount account) throws CryptoException {
        return account.export(EXPORT_KIN_ACCOUNT_PASSPHRASE);
    }

    private static KinAccount importAccount(KinClient kinClient, String exportedJson) throws CorruptedDataException, CreateAccountException, CryptoException {
        return kinClient.importAccount(exportedJson, EXPORT_KIN_ACCOUNT_PASSPHRASE);
    }

    private static boolean doesExportedAccountExist() {
        return false;
    }

    private static void saveAccountToDisk(String exportedJson) {

    }

    private static String retrieveAccountFromDisk() {
        return "";
    }
}
