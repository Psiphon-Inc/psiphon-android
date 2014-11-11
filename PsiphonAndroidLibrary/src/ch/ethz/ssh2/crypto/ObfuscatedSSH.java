
package ch.ethz.ssh2.crypto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

import com.psiphon3.psiphonlibrary.PsiphonData;

import ch.ethz.ssh2.crypto.cipher.RC4Engine;
import ch.ethz.ssh2.crypto.digest.SHA1;

/**
 * Obfuscated SSH implementation (client side only)
 * Based on https://github.com/brl/obfuscated-openssh 
 * 
 * Copyright (c) 2012, Psiphon Inc.
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
public class ObfuscatedSSH
{
    static final int OBFUSCATE_KEY_LENGTH = 16;
    static final int OBFUSCATE_SEED_LENGTH = 16;
    static final int OBFUSCATE_HASH_ITERATIONS = 6000;
    public static final int OBFUSCATE_MAX_PADDING = 8192;
    static final int OBFUSCATE_MAGIC_VALUE = 0x0BF5CA7E;
    static final byte[] CLIENT_TO_SERVER_IV = "client_to_server".getBytes();
    static final byte[] SERVER_TO_CLIENT_IV = "server_to_client".getBytes();
	
    private int obfuscateMaxPadding = OBFUSCATE_MAX_PADDING;
    private String obfuscateKeyword;
    private RC4Engine rc4input = new RC4Engine();
    private RC4Engine rc4output = new RC4Engine();
    
    public ObfuscatedSSH(String keyword)
    {
        this.obfuscateKeyword = keyword;
    }

    public ObfuscatedSSH(String keyword, int maxPadding)
    {
        this.obfuscateKeyword = keyword;
        this.obfuscateMaxPadding = maxPadding;
    }

    public byte[] getSeedMessage() throws IOException
    {
        SecureRandom random = new SecureRandom();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        
        byte[] seed = new byte[OBFUSCATE_SEED_LENGTH];
        random.nextBytes(seed);        
        
        buffer.write(ByteBuffer.allocate(4).putInt(OBFUSCATE_MAGIC_VALUE).array());
        
        int paddingLength = random.nextInt(this.obfuscateMaxPadding);
        byte[] padding = new byte[paddingLength];
        random.nextBytes(padding);
        buffer.write(ByteBuffer.allocate(4).putInt(paddingLength).array());
        buffer.write(padding);
        
        initializeKeys(seed);

        byte[] obfuscatedMessage = buffer.toByteArray();

        obfuscateOutput(obfuscatedMessage);

        buffer.reset();

        buffer.write(seed);
        buffer.write(obfuscatedMessage);
        
        return buffer.toByteArray();
    }

    public byte obfuscateInput(byte b)
    {
        return this.rc4input.returnByte(b);
    }

    public void obfuscateInput(byte[] bytes)
    {
        this.rc4input.processBytes(bytes, 0, bytes.length, bytes, 0);
    }

    public void obfuscateInput(byte[] bytes, int off, int len)
    {
        this.rc4input.processBytes(bytes, off, len, bytes, off);
    }

    public byte obfuscateOutput(byte b)
    {
        return this.rc4output.returnByte(b);
    }

    public void obfuscateOutput(byte[] bytes)
    {
        this.rc4output.processBytes(bytes, 0, bytes.length, bytes, 0);
    }

    private void initializeKeys(byte[] seed) throws IOException
    {
        this.rc4input.init(true, generateKey(seed, SERVER_TO_CLIENT_IV));
        this.rc4output.init(true, generateKey(seed, CLIENT_TO_SERVER_IV));
    }

    private byte[] generateKey(byte[] seed, byte[] iv) throws IOException
    {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(seed);
        buffer.write(this.obfuscateKeyword.getBytes());
        buffer.write(iv);
        
        SHA1 sha = new SHA1();
        byte[] digest = new byte[sha.getDigestLength()];
        
        sha.update(buffer.toByteArray());
        sha.digest(digest);
        
        for (int i = 0; i < OBFUSCATE_HASH_ITERATIONS; i++)
        {
            sha.update(digest);
            sha.digest(digest);
        }
        
        assert(sha.getDigestLength() >= OBFUSCATE_KEY_LENGTH);
        
        byte[] key = new byte[OBFUSCATE_KEY_LENGTH];
        System.arraycopy(digest, 0, key, 0, OBFUSCATE_KEY_LENGTH);
        return key;
    }

    public class ObfuscatedInputStream extends InputStream
    {
        private boolean obfuscate = true;
        private ObfuscatedSSH ossh;
        private InputStream is;
        
        public ObfuscatedInputStream( ObfuscatedSSH obfuscatedSSH, InputStream inputStream)
        {
            ossh = obfuscatedSSH;
            is = inputStream;
        }
        
        @Override
        public int available() throws IOException
        {
            return is.available();
        }

        @Override
        public boolean markSupported()
        {
            return false;
        }        
        
        @Override
        public void close() throws IOException
        {
            is.close();
        }

        @Override
        public int read() throws IOException
        {
            if (obfuscate)
            {
                return ossh.obfuscateInput((byte) is.read());
            }
            else
            {
                return (byte) is.read();
            }
        }
        
        @Override
        public int read(byte[] b) throws IOException
        {
            int count = is.read(b);
            if (obfuscate)
            {
                ossh.obfuscateInput(b);
            }
            return count;
        }
        
        @Override
        public int read(byte[] b, int off, int len) throws IOException
        {
            int count = is.read(b, off, len);
            if (obfuscate)
            {
                ossh.obfuscateInput(b, off, count);
            }
            return count;            
        }
        
        @Override
        public long skip(long n) throws IOException
        {
            return is.skip(n);
        }

        public void enableObfuscation()
        {
            obfuscate = true;
        }

        public void disableObfuscation()
        {
            obfuscate = false;
        }
    }

    public ObfuscatedInputStream MakeObfuscatedInputStream(InputStream inputStream)
    {
        return new ObfuscatedInputStream(this, inputStream);
    }


    public class ObfuscatedOutputStream extends OutputStream
    {
        private boolean obfuscate = true;
        private ObfuscatedSSH ossh;
        private OutputStream os;
        
        public ObfuscatedOutputStream( ObfuscatedSSH obfuscatedSSH, OutputStream outputStream)
        {
            ossh = obfuscatedSSH;
            os = outputStream;
        }
                
        @Override
        public void close() throws IOException
        {
            os.close();
        }

        @Override
        public void flush() throws IOException
        {
            os.flush();
        }

        @Override
        public void write(byte[] b) throws IOException
        {
            if (obfuscate)
            {
                byte[] copy = b.clone();
                ossh.obfuscateOutput(copy);
                os.write(copy);
            }
            else
            {
                os.write(b);
            }
        }
        
        @Override
        public void write(byte[] b, int off, int len) throws IOException
        {
            if (obfuscate)
            {
                byte[] copy = new byte[len]; 
                System.arraycopy(b, off, copy, 0, len);
                ossh.obfuscateOutput(copy);
                os.write(copy);
            }
            else
            {
                os.write(b, off, len);
            }
        }
        
        @Override
        public void write(int b) throws IOException
        {
            if (obfuscate)
            {
                os.write(ossh.obfuscateOutput((byte) b));
            }
            else
            {
                os.write((byte) b);
            }
        }
        
        public boolean isObfuscating()
        {
            return obfuscate;
        }

        public void enableObfuscation()
        {
            obfuscate = true;
        }

        public void disableObfuscation()
        {
            obfuscate = false;
        }
    }

    public ObfuscatedOutputStream MakeObfuscatedOutputStream(OutputStream outputStream)
    {
        return new ObfuscatedOutputStream(this, outputStream);
    }
}
