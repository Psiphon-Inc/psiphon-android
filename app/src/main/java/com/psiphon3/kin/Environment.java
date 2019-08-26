package com.psiphon3.kin;

/**
 * Provides network details
 */
class Environment {

    // TODO: Get these values
    static final Environment PRODUCTION = new Environment(
            "",
            "",
            kin.sdk.Environment.PRODUCTION
    );

    static final Environment TEST = new Environment(
            "https://friendbot-testnet.developers.kinecosystem.com",
            "",
            kin.sdk.Environment.TEST
    );

    private final String mServerUrl;
    private final String mPsiphonWalletAddress;
    private final kin.sdk.Environment mKinEnvironment;

    private Environment(String serverUrl, String psiphonWalletAddress, kin.sdk.Environment kinEnvironment) {
        mServerUrl = serverUrl;
        mPsiphonWalletAddress = psiphonWalletAddress;
        mKinEnvironment = kinEnvironment;
    }

    final String getServerUrl() {
        return mServerUrl;
    }

    final String getPsiphonWalletAddress() {
        return mPsiphonWalletAddress;
    }

    final kin.sdk.Environment getKinEnvironment() {
        return mKinEnvironment;
    }
}