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

package com.psiphon3;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import android.content.Context;
import android.content.res.AssetManager;

public class PsiphonNativeWrapper
{
    private Process process;
    
    public PsiphonNativeWrapper(
            Context context,
            String executableName,
            String arguments)
        throws IOException
    {
        if (this.process != null)
        {
            stop();
        }

        String fileName = "/data/data/com.psiphon3/" + executableName;

        // Delete existing file
        
        String rmCommand = "rm " + fileName;
        Process rm = Runtime.getRuntime().exec(rmCommand);
        try
        {
            rm.waitFor();
        }
        catch (InterruptedException e)
        {
            // Allow following call to fail if interrupted
        }

        // Extract binary from asset
        
        AssetManager assetManager = context.getAssets();
        InputStream asset = assetManager.open(executableName);
        FileOutputStream file = new FileOutputStream(fileName);
        byte[] buffer = new byte[8192];
        int length;
        while ((length = asset.read(buffer)) != -1)
        {
            file.write(buffer, 0 , length);
        }
        file.close();
        asset.close();

        // Make executable
        
        String chmodCommand = "chmod 700 " + fileName;
        Process chmod = Runtime.getRuntime().exec(chmodCommand);
        try
        {
            chmod.waitFor();
        }
        catch (InterruptedException e)
        {
            // Allow following call to fail if interrupted
        }
        
        // Start the process
        
        String nativeCommand = fileName + " " + arguments;
        this.process = Runtime.getRuntime().exec(nativeCommand);
    }
    
    public void stop()
    {
        if (this.process == null)
        {
            return;
        }
        // TODO: graceful shutdown?
        this.process.destroy();
        this.process = null;
    }

    public boolean isRunning()
    {
        if (this.process != null)
        {
            try
            {
                this.process.exitValue();
            }
            catch (IllegalThreadStateException e)
            {
                // exitValue() throws this exception if the process has not terminated
                return true;
            }
        }
        
        return false;
    }
}
