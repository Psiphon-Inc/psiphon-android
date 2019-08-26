package com.psiphon3.kin;

/**
 * Provides network details
 */
class Environment {
    // TODO: Get real app id
    static final String PSIPHON_APP_ID = "FAKE";

    // TODO: Get these values
    static final Environment PRODUCTION = new Environment(
            "",
            "",
            kin.sdk.Environment.PRODUCTION
    );

    static final Environment TEST = new Environment(
            "friendbot.developers.kinecosystem.com",
            "",
            kin.sdk.Environment.TEST
    );

    private final String mFriendBotServerUrl;
    private final String mPsiphonWalletAddress;
    private final kin.sdk.Environment mKinEnvironment;

    private Environment(String friendBotServerUrl, String psiphonWalletAddress, kin.sdk.Environment kinEnvironment) {
        mFriendBotServerUrl = friendBotServerUrl;
        mPsiphonWalletAddress = psiphonWalletAddress;
        mKinEnvironment = kinEnvironment;
    }

    final String getFriendBotServerUrl() {
        return mFriendBotServerUrl;
    }

    final String getPsiphonWalletAddress() {
        return mPsiphonWalletAddress;
    }

    final kin.sdk.Environment getKinEnvironment() {
        return mKinEnvironment;
    }
}