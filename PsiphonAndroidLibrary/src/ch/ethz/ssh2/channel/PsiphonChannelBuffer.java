/*
 * Copyright (c) 2014, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package ch.ethz.ssh2.channel;

import java.io.IOException;

/*
 * This dynamic buffer replaces the static byte[] array that Ganymed used to fully allocate
 * as soon as the channel is created. Using a dynamic buffer allows us to support much larger
 * channel windows (e.g., better performance for high throughput channels such as port forwarded
 * video streaming or file transfer) without statically allocating huge buffers for every channel.
 * 
 * NOTE: in an attempt to keep this change isolated, we're simply dropping in the dynamic buffer
 * in place of the static byte[] and not changing the Ganymed channel window size logic.
 */
public class PsiphonChannelBuffer {
    static final int INIT_SIZE = 35000; // SSH max packet size (http://tools.ietf.org/html/rfc4253#section-6.1)
    static final int MAX_SIZE = Channel.MAX_CHANNEL_BUFFER_SIZE;
    private byte[] mBuffer = new byte[INIT_SIZE];
    
    public synchronized void read(int sourceOffset, byte[] destination, int destinationOffset, int length) {
        System.arraycopy(mBuffer, sourceOffset, destination, destinationOffset, length);
    }
    
    public synchronized void write(int destinationOffset, byte[] source, int sourceOffset, int length) throws IOException {
        if (destinationOffset + length >= mBuffer.length) {
            resize();
        }
        System.arraycopy(source, sourceOffset, mBuffer, destinationOffset, length);
    }

    public synchronized void move(int sourceOffset, int destinationOffset, int length) throws IOException {
        // Assumes destinationOffset + length within currently allocated buffer
        System.arraycopy(mBuffer, sourceOffset, mBuffer, destinationOffset, length);
    }

    private void resize() throws IOException {
        try {
            byte[] temp = new byte[2*mBuffer.length];
            System.arraycopy(mBuffer, 0, temp, 0, mBuffer.length);
            mBuffer = temp;
        } catch (OutOfMemoryError e) {
            // If we're out of memory, cause the channel to close -- not the app to stop 
            throw new IOException();
        }
    }
}
