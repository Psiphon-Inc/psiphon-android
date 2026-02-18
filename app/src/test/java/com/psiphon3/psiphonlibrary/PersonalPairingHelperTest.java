package com.psiphon3.psiphonlibrary;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class PersonalPairingHelperTest {
    private static final String EXPECTED_COMPARTMENT_ID = "jgr-fj3yz6Wpn_vV7qlP4Sh-hBkThZCDEe6-OVJEm2g";
    private static final String EXPECTED_ALIAS = "mattereaterlad's conduit";

    // spec/fixtures/pairing/token-v1.valid.base64url.txt
    private static final String VALID_TOKEN_BASE64URL = "eyJ2IjoiMSIsImRhdGEiOnsiaWQiOiJqZ3ItZmozeXo2V3BuX3ZWN3FsUDRTaC1oQmtUaFpDREVlNi1PVkpFbTJnIiwibmFtZSI6Im1hdHRlcmVhdGVybGFkJ3MgY29uZHVpdCJ9fQ";
    // spec/fixtures/pairing/token-v1.valid.base64.txt
    private static final String VALID_TOKEN_BASE64 = "eyJ2IjoiMSIsImRhdGEiOnsiaWQiOiJqZ3ItZmozeXo2V3BuX3ZWN3FsUDRTaC1oQmtUaFpDREVlNi1PVkpFbTJnIiwibmFtZSI6Im1hdHRlcmVhdGVybGFkJ3MgY29uZHVpdCJ9fQ==";
    // spec/fixtures/pairing/token-v2.unsupported.base64url.txt
    private static final String UNSUPPORTED_VERSION_TOKEN = "eyJ2IjoiMiIsImRhdGEiOnsiaWQiOiJqZ3ItZmozeXo2V3BuX3ZWN3FsUDRTaC1oQmtUaFpDREVlNi1PVkpFbTJnIiwibmFtZSI6Im1hdHRlcmVhdGVybGFkJ3MgY29uZHVpdCJ9fQ";
    // spec/fixtures/pairing/token.malformed.txt
    private static final String MALFORMED_TOKEN = "not-base64-token!!";

    private static final String VALID_DEEP_LINK = "psiphon://pair/" + VALID_TOKEN_BASE64URL;
    private static final String VALID_WRAPPER_URL = "https://hextempulant.net/pair/" + VALID_TOKEN_BASE64URL;

    @Test
    public void extractPersonalPairingData_acceptsRawToken() {
        assertExpectedData(PersonalPairingHelper.extractPersonalPairingData(VALID_TOKEN_BASE64URL));
    }

    @Test
    public void extractPersonalPairingData_acceptsLegacyBase64RawToken() {
        assertExpectedData(PersonalPairingHelper.extractPersonalPairingData(VALID_TOKEN_BASE64));
    }

    @Test
    public void extractPersonalPairingData_acceptsDeepLink() {
        assertExpectedData(PersonalPairingHelper.extractPersonalPairingData(VALID_DEEP_LINK));
    }

    @Test
    public void extractPersonalPairingData_acceptsWrapperUrl() {
        assertExpectedData(PersonalPairingHelper.extractPersonalPairingData(VALID_WRAPPER_URL));
    }

    @Test
    public void extractPersonalPairingData_rejectsMalformedTokenFixture() {
        assertValidationError(
                MALFORMED_TOKEN,
                PersonalPairingHelper.ImportValidationError.MALFORMED_TOKEN);
    }

    @Test
    public void extractPersonalPairingData_rejectsUnsupportedVersionFixture() {
        assertValidationError(
                UNSUPPORTED_VERSION_TOKEN,
                PersonalPairingHelper.ImportValidationError.UNSUPPORTED_VERSION);
    }

    @Test
    public void extractPersonalPairingData_acceptsAnyHttpsPairHost() {
        assertExpectedData(PersonalPairingHelper.extractPersonalPairingData(
                "https://example.net/pair/" + VALID_TOKEN_BASE64URL));
    }

    @Test
    public void extractPersonalPairingData_acceptsAnyHttpPairHost() {
        assertExpectedData(PersonalPairingHelper.extractPersonalPairingData(
                "http://localhost:8080/pair/" + VALID_TOKEN_BASE64URL));
    }

    @Test
    public void extractPersonalPairingData_acceptsNestedPairPath() {
        assertExpectedData(PersonalPairingHelper.extractPersonalPairingData(
                "https://dynamic.example.com/api/v2/pair/" + VALID_TOKEN_BASE64URL + "?utm_source=test#fragment"));
    }

    @Test
    public void extractPersonalPairingData_rejectsNonExactPairPath() {
        assertValidationError(
                "psiphon://pair/" + VALID_TOKEN_BASE64URL + "/extra",
                PersonalPairingHelper.ImportValidationError.INVALID_INPUT_FORMAT);
        assertValidationError(
                "https://hextempulant.net/pair/" + VALID_TOKEN_BASE64URL + "/extra",
                PersonalPairingHelper.ImportValidationError.INVALID_INPUT_FORMAT);
    }

    @Test
    public void extractPersonalPairingData_rejectsMalformedSchema() {
        String malformedSchemaToken = encodeToken("{\"v\":\"1\",\"data\":{\"id\":\"abc\"}}",
                true);
        assertValidationError(
                malformedSchemaToken,
                PersonalPairingHelper.ImportValidationError.MALFORMED_TOKEN);
    }

    private static String encodeToken(String json, boolean urlSafe) {
        byte[] input = json.getBytes(StandardCharsets.UTF_8);
        if (urlSafe) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(input);
        }
        return Base64.getEncoder().encodeToString(input);
    }

    private static void assertExpectedData(PersonalPairingHelper.PersonalPairingData data) {
        Assert.assertEquals(EXPECTED_COMPARTMENT_ID, data.compartmentId);
        Assert.assertEquals(EXPECTED_ALIAS, data.alias);
    }

    private static void assertValidationError(String input, PersonalPairingHelper.ImportValidationError expected) {
        try {
            PersonalPairingHelper.extractPersonalPairingData(input);
            Assert.fail("Expected import exception");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals(expected, PersonalPairingHelper.validationErrorFromException(e));
        }
    }
}
