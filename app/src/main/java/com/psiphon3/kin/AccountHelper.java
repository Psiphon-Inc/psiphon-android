package com.psiphon3.kin;

import android.util.Log;

import kin.sdk.KinAccount;
import kin.sdk.KinClient;
import kin.sdk.exception.CorruptedDataException;
import kin.sdk.exception.CreateAccountException;
import kin.sdk.exception.CryptoException;

class AccountHelper {
    private final static String EXPORT_KIN_ACCOUNT_PASSPHRASE = "correct-horse-battery-staple";
    private final static Double CREATE_ACCOUNT_FUND_AMOUNT = 1000d;

    /**
     * Gets a Kin account for this device. Will try to use a saved account first, but if none are
     * found it will create a new account and register it with the server.
     *
     * @param kinClient the KinClient to be used for the account
     * @param serverCommunicator the communicator for creating the account if needed
     * @return The account for this device.
     */
    static KinAccount getAccount(KinClient kinClient, ServerCommunicator serverCommunicator) {
        try {
            if (kinClient.hasAccount()) {
                return kinClient.getAccount(0);
            }

            if (doesExportedAccountExist()) {
                return importAccount(kinClient, retrieveAccountFromDisk());
            }

            return createKinAccount(kinClient, serverCommunicator);
        } catch (CreateAccountException e) {
            Log.e("kin", "getAccount", e);
        } catch (CorruptedDataException e) {
            Log.e("kin", "getAccount", e);
        } catch (CryptoException e) {
            Log.e("kin", "getAccount", e);
        }

        return null;
    }

    private static KinAccount createKinAccount(KinClient kinClient, ServerCommunicator serverCommunicator) throws CreateAccountException {
        KinAccount account = kinClient.addAccount();
        serverCommunicator.createAccount(account.getPublicAddress(), CREATE_ACCOUNT_FUND_AMOUNT, new Callbacks() {
            @Override
            public void onSuccess() {
                // TODO: Should do something
            }

            @Override
            public void onFailure(Exception e) {
                // TODO: Should do something

            }
        });
        return account;
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
