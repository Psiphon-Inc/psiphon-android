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

import org.junit.Test;

import java.util.Arrays;

import static org.abstractj.kalium.encoders.Encoder.HEX;
import static org.abstractj.kalium.fixture.TestVectors.ALICE_MULT_BOB;
import static org.abstractj.kalium.fixture.TestVectors.ALICE_PRIVATE_KEY;
import static org.abstractj.kalium.fixture.TestVectors.ALICE_PUBLIC_KEY;
import static org.abstractj.kalium.fixture.TestVectors.BOB_PUBLIC_KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PointTest {

    @Test
    public void testMultipleIntegersWithBasePoint() throws Exception {
        Point point = new Point();
        String mult = point.mult(ALICE_PRIVATE_KEY, HEX).toString();
        assertEquals("Should return a serialized point", ALICE_PUBLIC_KEY, mult);
    }

    @Test
    public void testMultipleIntegersWithArbitraryPoints() throws Exception {
        Point point = new Point(BOB_PUBLIC_KEY, HEX);
        String mult = point.mult(ALICE_PRIVATE_KEY, HEX).toString();
        assertEquals("Should return a valid serialized point", ALICE_MULT_BOB, mult);
    }

    @Test
    public void testSerializeToBytes() throws Exception {
        Point point = new Point(BOB_PUBLIC_KEY, HEX);
        assertTrue("Should serialize to bytes", Arrays.equals(HEX.decode(BOB_PUBLIC_KEY), point.toBytes()));
    }

    @Test
    public void testSerializeToHex() throws Exception {
        Point point = new Point(BOB_PUBLIC_KEY, HEX);
        assertEquals("Should serialize to hex", BOB_PUBLIC_KEY, point.toString());
    }
}
