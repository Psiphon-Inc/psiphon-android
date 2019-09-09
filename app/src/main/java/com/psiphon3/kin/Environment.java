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
            "GAH3A3LKC4XOX6ZUKQWMW3KIMVB2J45NV3OPYBVWADS2RRCYXK7BU3XK",
            kin.sdk.Environment.PRODUCTION
    );

    static final Environment TEST = new Environment(
            "friendbot.developers.kinecosystem.com",
            "GCWUL5BFYTRVMM4WYHABI5C2Y5VZ7VB5N2BKPDMGG3OG7DYN3FBCTVDS",
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