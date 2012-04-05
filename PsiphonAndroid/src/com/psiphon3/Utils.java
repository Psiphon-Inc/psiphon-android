package com.psiphon3;


public class Utils {

    // from:
    // http://stackoverflow.com/questions/140131/convert-a-string-representation-of-a-hex-dump-to-a-byte-array-using-java
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character
                    .digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    /***************************************************************
     * Copyright (c) 1998, 1999 Nate Sammons <nate@protomatter.com> This library
     * is free software; you can redistribute it and/or modify it under the
     * terms of the GNU Library General Public License as published by the Free
     * Software Foundation; either version 2 of the License, or (at your option)
     * any later version.
     * 
     * This library is distributed in the hope that it will be useful, but
     * WITHOUT ANY WARRANTY; without even the implied warranty of
     * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Library
     * General Public License for more details.
     * 
     * You should have received a copy of the GNU Library General Public License
     * along with this library; if not, write to the Free Software Foundation,
     * Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
     * 
     * Contact support@protomatter.com with your questions, comments, gripes,
     * praise, etc...
     ***************************************************************/

    /***************************************************************
     * - moved to the net.matuschek.util tree by Daniel Matuschek - replaced
     * deprecated getBytes() method in method decode - added String
     * encode(String) method to encode a String to base64
     ***************************************************************/

    /**
     * Base64 encoder/decoder. Does not stream, so be careful with using large
     * amounts of data
     * 
     * @author Nate Sammons
     * @author Daniel Matuschek
     * @version $Id: Base64.java,v 1.4 2001/04/17 10:09:27 matuschd Exp $
     */
    public static class Base64 {

        private Base64() {
            super();
        }

        /**
         * Encode some data and return a String.
         */
        public final static String encode(byte[] d) {
            if (d == null)
                return null;
            byte data[] = new byte[d.length + 2];
            System.arraycopy(d, 0, data, 0, d.length);
            byte dest[] = new byte[(data.length / 3) * 4];

            // 3-byte to 4-byte conversion
            for (int sidx = 0, didx = 0; sidx < d.length; sidx += 3, didx += 4) {
                dest[didx] = (byte) ((data[sidx] >>> 2) & 077);
                dest[didx + 1] = (byte) ((data[sidx + 1] >>> 4) & 017 | (data[sidx] << 4) & 077);
                dest[didx + 2] = (byte) ((data[sidx + 2] >>> 6) & 003 | (data[sidx + 1] << 2) & 077);
                dest[didx + 3] = (byte) (data[sidx + 2] & 077);
            }

            // 0-63 to ascii printable conversion
            for (int idx = 0; idx < dest.length; idx++) {
                if (dest[idx] < 26)
                    dest[idx] = (byte) (dest[idx] + 'A');
                else if (dest[idx] < 52)
                    dest[idx] = (byte) (dest[idx] + 'a' - 26);
                else if (dest[idx] < 62)
                    dest[idx] = (byte) (dest[idx] + '0' - 52);
                else if (dest[idx] < 63)
                    dest[idx] = (byte) '+';
                else
                    dest[idx] = (byte) '/';
            }

            // add padding
            for (int idx = dest.length - 1; idx > (d.length * 4) / 3; idx--) {
                dest[idx] = (byte) '=';
            }
            return new String(dest);
        }

        /**
         * Encode a String using Base64 using the default platform encoding
         **/
        public final static String encode(String s) {
            return encode(s.getBytes());
        }

        /**
         * Decode data and return bytes.
         */
        public final static byte[] decode(String str) {
            if (str == null)
                return null;
            byte data[] = str.getBytes();
            return decode(data);
        }

        /**
         * Decode data and return bytes. Assumes that the data passed in is
         * ASCII text.
         */
        public final static byte[] decode(byte[] data) {
            int tail = data.length;
            while (data[tail - 1] == '=')
                tail--;
            byte dest[] = new byte[tail - data.length / 4];

            // ascii printable to 0-63 conversion
            for (int idx = 0; idx < data.length; idx++) {
                if (data[idx] == '=')
                    data[idx] = 0;
                else if (data[idx] == '/')
                    data[idx] = 63;
                else if (data[idx] == '+')
                    data[idx] = 62;
                else if (data[idx] >= '0' && data[idx] <= '9')
                    data[idx] = (byte) (data[idx] - ('0' - 52));
                else if (data[idx] >= 'a' && data[idx] <= 'z')
                    data[idx] = (byte) (data[idx] - ('a' - 26));
                else if (data[idx] >= 'A' && data[idx] <= 'Z')
                    data[idx] = (byte) (data[idx] - 'A');
            }

            // 4-byte to 3-byte conversion
            int sidx, didx;
            for (sidx = 0, didx = 0; didx < dest.length - 2; sidx += 4, didx += 3) {
                dest[didx] = (byte) (((data[sidx] << 2) & 255) | ((data[sidx + 1] >>> 4) & 3));
                dest[didx + 1] = (byte) (((data[sidx + 1] << 4) & 255) | ((data[sidx + 2] >>> 2) & 017));
                dest[didx + 2] = (byte) (((data[sidx + 2] << 6) & 255) | (data[sidx + 3] & 077));
            }
            if (didx < dest.length) {
                dest[didx] = (byte) (((data[sidx] << 2) & 255) | ((data[sidx + 1] >>> 4) & 3));
            }
            if (++didx < dest.length) {
                dest[didx] = (byte) (((data[sidx + 1] << 4) & 255) | ((data[sidx + 2] >>> 2) & 017));
            }
            return dest;
        }
    }
}
