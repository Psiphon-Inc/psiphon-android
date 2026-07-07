package com.psiphon3.psiphonlibrary;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class PersonalPairingHelperTest {
    private static final String EXPECTED_COMPARTMENT_ID = "jgr+fj3yz6Wpn/vV7qlP4Sh+hBkThZCDEe6+OVJEm2g";
    private static final String EXPECTED_ALIAS = "mattereaterlad's conduit";

    private static final String VALID_TOKEN_BASE64URL = "eyJ2IjoiMSIsImRhdGEiOnsiaWQiOiJqZ3IrZmozeXo2V3BuL3ZWN3FsUDRTaCtoQmtUaFpDREVlNitPVkpFbTJnIiwibmFtZSI6Im1hdHRlcmVhdGVybGFkJ3MgY29uZHVpdCJ9fQ";
    private static final String VALID_TOKEN_BASE64 = "eyJ2IjoiMSIsImRhdGEiOnsiaWQiOiJqZ3IrZmozeXo2V3BuL3ZWN3FsUDRTaCtoQmtUaFpDREVlNitPVkpFbTJnIiwibmFtZSI6Im1hdHRlcmVhdGVybGFkJ3MgY29uZHVpdCJ9fQ==";
    private static final String UNSUPPORTED_VERSION_TOKEN = "eyJ2IjoiMiIsImRhdGEiOnsiaWQiOiJqZ3IrZmozeXo2V3BuL3ZWN3FsUDRTaCtoQmtUaFpDREVlNitPVkpFbTJnIiwibmFtZSI6Im1hdHRlcmVhdGVybGFkJ3MgY29uZHVpdCJ9fQ";
    private static final String URLSAFE_COMPARTMENT_ID_TOKEN = "eyJ2IjoiMSIsImRhdGEiOnsiaWQiOiJqZ3ItZmozeXo2V3BuX3ZWN3FsUDRTaC1oQmtUaFpDREVlNi1PVkpFbTJnIiwibmFtZSI6Im1hdHRlcmVhdGVybGFkJ3MgY29uZHVpdCJ9fQ";
    // spec/fixtures/pairing/token.malformed.txt
    private static final String MALFORMED_TOKEN = "not-base64-token!!";

    private static final String LIGHT_PROXY_ENTRY = "aGVsbG8gbGlnaHQgcHJveHk=";

    private static final String VALID_DEEP_LINK = "psiphon://pair/" + VALID_TOKEN_BASE64URL;
    private static final String VALID_WRAPPER_URL = "https://example.net/pair/" + VALID_TOKEN_BASE64URL;

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
    public void extractPersonalPairingData_rejectsUrlSafeCompartmentId() {
        assertValidationError(
                URLSAFE_COMPARTMENT_ID_TOKEN,
                PersonalPairingHelper.ImportValidationError.MALFORMED_TOKEN);
    }

    @Test
    public void extractPersonalPairingData_rejectsNonExactPairPath() {
        assertValidationError(
                "psiphon://pair/" + VALID_TOKEN_BASE64URL + "/extra",
                PersonalPairingHelper.ImportValidationError.INVALID_INPUT_FORMAT);
        assertValidationError(
                "https://example.net/pair/" + VALID_TOKEN_BASE64URL + "/extra",
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

    @Test
    public void extractPersonalPairingData_acceptsNewCombinedLightAndIdToken() {
        // Combined token: light entry under "light" and inproxy compartment ID under
        // "id". Both connection methods must be extracted and kept.
        String token = encodeToken("{\"v\":\"1\",\"data\":{\"name\":\"" + EXPECTED_ALIAS
                + "\",\"light\":\"" + LIGHT_PROXY_ENTRY
                + "\",\"id\":\"" + EXPECTED_COMPARTMENT_ID + "\"}}", true);
        PersonalPairingHelper.PersonalPairingData data =
                PersonalPairingHelper.extractPersonalPairingData(token);
        Assert.assertTrue(data.hasInproxyPairing());
        Assert.assertTrue(data.hasLightProxy());
        Assert.assertEquals(EXPECTED_COMPARTMENT_ID, data.compartmentId);
        Assert.assertEquals(EXPECTED_ALIAS, data.alias);
        Assert.assertEquals(LIGHT_PROXY_ENTRY, data.lightProxyEntry);
    }

    @Test
    public void extractPersonalPairingData_acceptsNewLightOnlyToken() {
        String token = encodeToken("{\"v\":\"1\",\"data\":{\"light\":\"" + LIGHT_PROXY_ENTRY + "\"}}", true);
        PersonalPairingHelper.PersonalPairingData data =
                PersonalPairingHelper.extractPersonalPairingData(token);
        Assert.assertTrue(data.hasLightProxy());
        Assert.assertFalse(data.hasInproxyPairing());
        Assert.assertEquals(LIGHT_PROXY_ENTRY, data.lightProxyEntry);
        Assert.assertEquals("", data.compartmentId);
    }

    @Test
    public void extractPersonalPairingData_rejectsTokenWithNoConnectionMethod() {
        String token = encodeToken("{\"v\":\"1\",\"data\":{\"name\":\"" + EXPECTED_ALIAS + "\"}}", true);
        assertValidationError(token, PersonalPairingHelper.ImportValidationError.MALFORMED_TOKEN);
    }

    @Test
    public void extractPersonalPairingData_rejectsUnknownDataField() {
        String token = encodeToken("{\"v\":\"1\",\"data\":{\"id\":\"" + EXPECTED_COMPARTMENT_ID
                + "\",\"name\":\"" + EXPECTED_ALIAS + "\",\"bogus\":\"x\"}}", true);
        assertValidationError(token, PersonalPairingHelper.ImportValidationError.MALFORMED_TOKEN);
    }

    @Test
    public void extractPersonalPairingData_rejectsMalformedLightProxyEntry() {
        String token = encodeToken("{\"v\":\"1\",\"data\":{\"light\":\"!!!not-base64\"}}", true);
        assertValidationError(token, PersonalPairingHelper.ImportValidationError.MALFORMED_TOKEN);
    }

    @Test
    public void personalPairingData_hasAnyPairing() {
        Assert.assertFalse(new PersonalPairingHelper.PersonalPairingData("", "").hasAnyPairing());
        Assert.assertTrue(new PersonalPairingHelper.PersonalPairingData(EXPECTED_COMPARTMENT_ID, "").hasAnyPairing());
        Assert.assertTrue(new PersonalPairingHelper.PersonalPairingData("", "", LIGHT_PROXY_ENTRY).hasAnyPairing());
    }

    @Test
    public void personalPairingData_displayReference() {
        // Inproxy only -> compartment ID
        Assert.assertEquals(EXPECTED_COMPARTMENT_ID,
                new PersonalPairingHelper.PersonalPairingData(EXPECTED_COMPARTMENT_ID, "").displayReference());
        // Light with a name -> the name
        Assert.assertEquals(EXPECTED_ALIAS,
                new PersonalPairingHelper.PersonalPairingData("", EXPECTED_ALIAS, LIGHT_PROXY_ENTRY).displayReference());
        // Light without a name -> raw entry
        Assert.assertEquals(LIGHT_PROXY_ENTRY,
                new PersonalPairingHelper.PersonalPairingData("", "", LIGHT_PROXY_ENTRY).displayReference());
        // Combined -> name preferred (light is preferred over the compartment ID)
        Assert.assertEquals(EXPECTED_ALIAS,
                new PersonalPairingHelper.PersonalPairingData(EXPECTED_COMPARTMENT_ID, EXPECTED_ALIAS, LIGHT_PROXY_ENTRY).displayReference());
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
