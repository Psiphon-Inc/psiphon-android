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


import org.abstractj.kalium.crypto.Random;
import org.abstractj.kalium.crypto.Util;
import org.abstractj.kalium.encoders.Encoder;

import static org.abstractj.kalium.SodiumConstants.PUBLICKEY_BYTES;
import static org.abstractj.kalium.SodiumConstants.SECRETKEY_BYTES;
import static org.abstractj.kalium.SodiumConstants.SIGNATURE_BYTES;
import static org.abstractj.kalium.NaCl.sodium;
import static org.abstractj.kalium.crypto.Util.checkLength;
import static org.abstractj.kalium.crypto.Util.isValid;
import static org.abstractj.kalium.crypto.Util.slice;
import static org.abstractj.kalium.crypto.Util.zeros;
import static org.abstractj.kalium.encoders.Encoder.HEX;

public class SigningKey {

    private final byte[] seed;
    private final byte[] secretKey;

    public SigningKey(byte[] seed) {
        checkLength(seed, SECRETKEY_BYTES);
        this.seed = seed;
        this.secretKey = zeros(SECRETKEY_BYTES * 2);
        byte[] publicKey = zeros(PUBLICKEY_BYTES);
        isValid(sodium().crypto_sign_ed25519_ref_seed_keypair(publicKey, secretKey, seed),
                "Failed to generate a key pair");

        new VerifyKey(publicKey);
    }

    public SigningKey() {
        this(new Random().randomBytes(SECRETKEY_BYTES));
    }

    public SigningKey(String seed, Encoder encoder) {
        this(encoder.decode(seed));
    }

    public byte[] sign(byte[] message) {
        byte[] signature = Util.prependZeros(SIGNATURE_BYTES, message);
        int[] bufferLen = new int[1];
        sodium().crypto_sign_ed25519_ref(signature, bufferLen, message, message.length, secretKey);
        signature = slice(signature, 0, SIGNATURE_BYTES);
        return signature;
    }

    public String sign(String message, Encoder encoder) {
        byte[] signature = sign(encoder.decode(message));
        return encoder.encode(signature);
    }

    public byte[] toBytes() {
        return seed;
    }

    @Override
    public String toString() {
        return HEX.encode(seed);
    }
}
