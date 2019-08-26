package com.psiphon3.kin;

/**
 * Provides network details
 */
public class Environment {

    // TODO: Get these values
    public static final Environment PRODUCTION = new Environment(
            "",
            "",
            kin.sdk.Environment.PRODUCTION
    );

    public static final Environment TEST = new Environment(
            "https://friendbot-testnet.developers.kinecosystem.com",
            "",
            kin.sdk.Environment.TEST
    );

    private final String mServerUrl;
    private final String mPsiphonWalletAddress;
    private final kin.sdk.Environment mKinEnvironment;

    public Environment(String serverUrl, String psiphonWalletAddress, kin.sdk.Environment kinEnvironment) {
        mServerUrl = serverUrl;
        mPsiphonWalletAddress = psiphonWalletAddress;
        mKinEnvironment = kinEnvironment;
    }

    final public String getServerUrl() {
        return mServerUrl;
    }

    public String getPsiphonWalletAddress() {
        return mPsiphonWalletAddress;
    }

    final public kin.sdk.Environment getKinEnvironment() {
        return mKinEnvironment;
    }
}