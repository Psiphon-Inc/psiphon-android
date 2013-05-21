/*
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

package com.psiphon3.psiphonlibrary;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

import org.json.JSONException;
import org.json.JSONObject;

import com.google.gson.stream.JsonReader;
import com.psiphon3.psiphonlibrary.R;
import com.psiphon3.psiphonlibrary.Utils.Base64;
import com.psiphon3.psiphonlibrary.Utils.MyLog;


public class AuthenticatedDataPackage
{
    public static class AuthenticatedDataPackageException extends Exception
    {
        private static final long serialVersionUID = 1L;
        
        public AuthenticatedDataPackageException()
        {
            super();
        }
        
        public AuthenticatedDataPackageException(String message)
        {
            super(message);
        }

        public AuthenticatedDataPackageException(String message, Throwable cause)
        {
            super(message, cause);
        }

        public AuthenticatedDataPackageException(Throwable cause)
        {
            super(cause);
        }
    }
    
    static public String validateAndExtractData(
            String signaturePublicKey,
            String dataPackage)
        throws AuthenticatedDataPackageException
    {
        try
        {
            return validateAndExtractData(
                    signaturePublicKey,
                    new ByteArrayInputStream(dataPackage.getBytes("UTF-8")));
        }
        catch (UnsupportedEncodingException e)
        {
            MyLog.w(R.string.AuthenticatedDataPackage_InvalidEncoding, MyLog.Sensitivity.NOT_SENSITIVE);
            throw new AuthenticatedDataPackageException();
        }
    }

    static public String validateAndExtractData(
            String signaturePublicKey,
            InputStream dataPackage)
        throws AuthenticatedDataPackageException
    {
        // Authenticate remote server list as per scheme described in
        // Psiphon/Automation/psi_ops_server_entry_auth.py
        
        // NOTE: this function always closes the dataPackage input stream
        
        JsonReader jsonReader = null;
        
        try
        {
            jsonReader = new JsonReader(new InputStreamReader(dataPackage, "UTF-8"));

            String data = null;
            String signature = null;
            String signingPublicKeyDigest = null;
                        
            jsonReader.beginObject();
            while (jsonReader.hasNext())
            {
                String name = jsonReader.nextName();
                if (name.equals("data"))
                {
                    data = jsonReader.nextString();
                }
                else if (name.equals("signature"))
                {
                    signature = jsonReader.nextString();
                }
                else if (name.equals("signingPublicKeyDigest"))
                {
                    signingPublicKeyDigest = jsonReader.nextString();
                }
                else
                {
                    jsonReader.skipValue();
                }
            }
            jsonReader.endObject();

            if (data == null || signature == null || signingPublicKeyDigest == null)
            {
                MyLog.w(R.string.AuthenticatedDataPackage_MissingValue, MyLog.Sensitivity.NOT_SENSITIVE);
                throw new AuthenticatedDataPackageException();                
            }
            
            MessageDigest sha2;
            sha2 = MessageDigest.getInstance("SHA256");
            String publicKeyDigest = Base64.encode(sha2.digest(signaturePublicKey.getBytes()));

            if (0 != publicKeyDigest.compareTo(signingPublicKeyDigest))
            {
                // The entry is signed with a different public key than our embedded value
                MyLog.w(R.string.AuthenticatedDataPackage_WrongPublicKey, MyLog.Sensitivity.NOT_SENSITIVE);
                throw new AuthenticatedDataPackageException();
            }

            byte[] publicKeyBytes = Base64.decode(signaturePublicKey);
            java.security.spec.X509EncodedKeySpec spec = new X509EncodedKeySpec(publicKeyBytes);
            java.security.KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = keyFactory.generatePublic(spec);
    
            Signature verifier = java.security.Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(data.getBytes());
            if (!verifier.verify(Base64.decode(signature)))
            {            
                MyLog.w(R.string.AuthenticatedDataPackage_InvalidSignature, MyLog.Sensitivity.NOT_SENSITIVE);
                throw new AuthenticatedDataPackageException();
            }
            
            return data;
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new AuthenticatedDataPackageException(e);
        }
        catch (InvalidKeySpecException e)
        {
            throw new AuthenticatedDataPackageException(e);
        }
        catch (InvalidKeyException e)
        {
            throw new AuthenticatedDataPackageException(e);
        }
        catch (SignatureException e)
        {
            throw new AuthenticatedDataPackageException(e);
        }
        catch (UnsupportedEncodingException e)
        {
            throw new AuthenticatedDataPackageException(e);
        }
        catch (IOException e)
        {
            throw new AuthenticatedDataPackageException(e);
        }
        finally
        {
            if (jsonReader != null)
            {
                try { jsonReader.close(); } catch (IOException e) {}
            }            
            if (dataPackage != null)
            {
                try { dataPackage.close(); } catch (IOException e) {}
            }            
        }
    }
}
