package com.psiphon3.kin;

/**
 * Provides network details
 */
public class Environment {
    public static final Environment PRODUCTION = new Environment(
            "kin.psiphon.io",
            "GAH3A3LKC4XOX6ZUKQWMW3KIMVB2J45NV3OPYBVWADS2RRCYXK7BU3XK",
            new kin.sdk.Environment(
                    "https://horizon.psiphon.io",
                    "Kin Mainnet ; December 2018"
            )
    );
    public static final Environment TEST = new Environment(
            "kin-testnet.psiphon.io",
            "GCWUL5BFYTRVMM4WYHABI5C2Y5VZ7VB5N2BKPDMGG3OG7DYN3FBCTVDS",
            new kin.sdk.Environment(
                    "https://horizon-testnet.psiphon.io/",
                    "Kin Testnet ; December 2018"
            )
    );

    // TODO: Get real app id
    static final String PSIPHON_APP_ID = "FAKE";

    private final String kinApplicationServerUrl;
    private final String psiphonWalletAddress;
    private final kin.sdk.Environment kinEnvironment;

    private Environment(String kinApplicationServerUrl, String psiphonWalletAddress, kin.sdk.Environment kinEnvironment) {
        this.kinApplicationServerUrl = kinApplicationServerUrl;
        this.psiphonWalletAddress = psiphonWalletAddress;
        this.kinEnvironment = kinEnvironment;
    }

    final String getKinApplicationServerUrl() {
        return kinApplicationServerUrl;
    }

    final String getPsiphonWalletAddress() {
        return psiphonWalletAddress;
    }

    final kin.sdk.Environment getKinEnvironment() {
        return kinEnvironment;
    }
}