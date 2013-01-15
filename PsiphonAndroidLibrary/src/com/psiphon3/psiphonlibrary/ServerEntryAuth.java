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

import com.psiphon3.psiphonlibrary.R;
import com.psiphon3.psiphonlibrary.Utils.Base64;
import com.psiphon3.psiphonlibrary.Utils.MyLog;


public class ServerEntryAuth
{
    public static class ServerEntryAuthException extends Exception
    {
        private static final long serialVersionUID = 1L;
        
        public ServerEntryAuthException()
        {
            super();
        }
        
        public ServerEntryAuthException(String message)
        {
            super(message);
        }

        public ServerEntryAuthException(String message, Throwable cause)
        {
            super(message, cause);
        }

        public ServerEntryAuthException(Throwable cause)
        {
            super(cause);
        }
    }

    static public String validateAndExtractServerList(String remoteServerList)
            throws ServerEntryAuthException
    {
        // Authenticate remote server list as per scheme described in
        // Psiphon/Automation/psi_ops_server_entry_auth.py
        
        try
        {
            JSONObject obj = new JSONObject(remoteServerList);
            String data = obj.getString("data");
            String signature = obj.getString("signature");
            String signingPublicKeyDigest = obj.getString("signingPublicKeyDigest");
            
            MessageDigest sha2;
            sha2 = MessageDigest.getInstance("SHA256");
            byte[] publicKeyDigest = sha2.digest(
                EmbeddedValues.REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY.getBytes());

            if (0 != Base64.encode(publicKeyDigest).compareTo(signingPublicKeyDigest))
            {
                // The entry is signed with a different public key than our embedded value
                MyLog.w(R.string.ServerEntryAuth_WrongPublicKey, MyLog.Sensitivity.NOT_SENSITIVE);
                throw new ServerEntryAuthException();
            }

            byte[] publicKeyBytes = Base64.decode(EmbeddedValues.REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY);
            java.security.spec.X509EncodedKeySpec spec = new X509EncodedKeySpec(publicKeyBytes);
            java.security.KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = keyFactory.generatePublic(spec);
    
            Signature verifier = java.security.Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(data.getBytes());
            if (!verifier.verify(Base64.decode(signature)))
            {            
                MyLog.w(R.string.ServerEntryAuth_InvalidSignature, MyLog.Sensitivity.NOT_SENSITIVE);
                throw new ServerEntryAuthException();
            }
            
            return data;
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new ServerEntryAuthException(e);
        }
        catch (InvalidKeySpecException e)
        {
            throw new ServerEntryAuthException(e);
        }
        catch (InvalidKeyException e)
        {
            throw new ServerEntryAuthException(e);
        }
        catch (SignatureException e)
        {
            throw new ServerEntryAuthException(e);
        }
        catch (JSONException e)
        {
            MyLog.w(R.string.ServerEntryAuth_FailedToParseRemoteServerEntry, MyLog.Sensitivity.NOT_SENSITIVE, e);
            throw new ServerEntryAuthException(e);
        }
    }
}
