/**
 * Copyright 2013 Bruno Oliveira, and individual contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.abstractj.kalium.keys;

import org.junit.Test;

import java.util.Arrays;

import static org.abstractj.kalium.encoders.Encoder.HEX;
import static org.abstractj.kalium.fixture.TestVectors.BOB_PRIVATE_KEY;
import static org.abstractj.kalium.fixture.TestVectors.BOB_PUBLIC_KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KeyPairTest {

    @Test
    public void testGenerateKeyPair() {
        try {
            KeyPair key = new KeyPair();
            assertTrue(key.getPrivateKey() != null);
            assertTrue(key.getPublicKey() != null);
        } catch (Exception e) {
            fail("Should return a valid key size");
        }
    }

    @Test
    public void testAcceptsValidKey() {
        try {
            byte[] rawKey = HEX.decode(BOB_PRIVATE_KEY);
            new KeyPair(rawKey);
        } catch (Exception e) {
            fail("Should not raise any exception");
        }
    }

    @Test
    public void testAcceptsHexEncodedKey() {
        try {
            new KeyPair(BOB_PRIVATE_KEY, HEX);
        } catch (Exception e) {
            fail("Should not raise any exception");
        }
    }

    @Test(expected = RuntimeException.class)
    public void testRejectNullKey() throws Exception {
        byte[] privateKey = null;
        new KeyPair(privateKey);
        fail("Should reject null keys");
    }

    @Test(expected = RuntimeException.class)
    public void testRejectShortKey() throws Exception {
        byte[] privateKey = "short".getBytes();
        new KeyPair(privateKey);
        fail("Should reject null keys");
    }

    @Test
    public void testGeneratePublicKey() throws Exception {
        try {
            byte[] pk = HEX.decode(BOB_PRIVATE_KEY);
            KeyPair key = new KeyPair(pk);
            assertTrue(key.getPublicKey() != null);
        } catch (Exception e) {
            fail("Should return a valid key size");
        }
    }

    @Test
    public void testPrivateKeyToString() throws Exception {
        try {
            KeyPair key = new KeyPair(BOB_PRIVATE_KEY, HEX);
            assertEquals("Correct private key expected", BOB_PRIVATE_KEY, key.getPrivateKey().toString());
        } catch (Exception e) {
            fail("Should return a valid key size");
        }
    }

    @Test
    public void testPrivateKeyToBytes() throws Exception {
        try {
            KeyPair key = new KeyPair(BOB_PRIVATE_KEY, HEX);
            assertTrue("Correct private key expected", Arrays.equals(HEX.decode(BOB_PUBLIC_KEY),
                    key.getPublicKey().toBytes()));
        } catch (Exception e) {
            fail("Should return a valid key size");
        }
    }

    @Test
    public void testPublicKeyToString() throws Exception {
        try {
            KeyPair key = new KeyPair(BOB_PRIVATE_KEY, HEX);
            assertEquals("Correct public key expected", BOB_PUBLIC_KEY, key.getPublicKey().toString());
        } catch (Exception e) {
            fail("Should return a valid key size");
        }
    }

    @Test
    public void testPublicKeyToBytes() throws Exception {
        try {
            KeyPair key = new KeyPair(BOB_PRIVATE_KEY, HEX);
            assertTrue("Correct public key expected", Arrays.equals(HEX.decode(BOB_PUBLIC_KEY),
                    key.getPublicKey().toBytes()));
        } catch (Exception e) {
            fail("Should return a valid key size");
        }
    }
}
