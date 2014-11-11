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

package org.abstractj.kalium.crypto;

import java.util.Arrays;

public class Util {

    private static final int DEFAULT_SIZE = 32;

    public static byte[] prependZeros(int n, byte[] message) {
        byte[] result = new byte[n + message.length];
        Arrays.fill(result, (byte) 0);
        System.arraycopy(message, 0, result, n, message.length);
        return result;
    }
    
    // PSIPHON
    // Arrays.copyOfRange isn't in Android API 8, so we use this instead
    private static byte[] copyOfRange(byte[] original, int start, int end) {
        // Arrays.copyOfRange doc:
        // Copies elements from original into a new array, from indexes start (inclusive) to end (exclusive).
        // The original order of elements is preserved. If end is greater than original.length, the result is
        // padded with the value (byte) 0.
        int length = end - start;
        if (length < 0) {
            throw new IllegalArgumentException("length < 0");
        }
        if (length > original.length - start) {
            throw new IllegalArgumentException("length > original.length - start");
        }
        byte[] copy = new byte[length];
        System.arraycopy(original, start, copy, 0, length);
        return copy;
    }

    public static byte[] removeZeros(int n, byte[] message) {
        // PSIPHON
        //return Arrays.copyOfRange(message, n, message.length);
        return copyOfRange(message, n, message.length);
    }

    public static void checkLength(byte[] data, int size) {
        if (data == null || data.length != size)
            throw new RuntimeException("Invalid size: " + data.length);
    }

    public static byte[] zeros(int n) {
        return new byte[n];
    }

    public static boolean isValid(int status, String message) {
        if (status != 0)
            throw new RuntimeException(message);
        return true;
    }

    public static byte[] slice(byte[] buffer, int start, int end) {
        // PSIPHON
        //return Arrays.copyOfRange(buffer, start, end);
        return copyOfRange(buffer, start, end);
    }

    public static byte[] merge(byte[] signature, byte[] message) {
        byte[] result = new byte[signature.length + message.length];
        System.arraycopy(signature, 0, result, 0, signature.length);
        System.arraycopy(message, 0, result, signature.length, message.length);
        return result;
    }
}
