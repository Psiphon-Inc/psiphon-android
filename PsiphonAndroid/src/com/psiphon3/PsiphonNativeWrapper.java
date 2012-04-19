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

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import com.psiphon3.Utils.MyLog;

import android.content.Context;
import android.content.res.AssetManager;

public class PsiphonNativeWrapper
{
    // from: "When Runtime.exec() won't"
    // http://www.javaworld.com/javaworld/jw-12-2000/jw-1229-traps.html?page=4
    class StreamGobbler extends Thread
    {
        String name;
        InputStream stream;
        
        StreamGobbler(String name, InputStream stream)
        {
            this.name = name;
            this.stream = stream;
        }
        
        public void run()
        {
            try
            {
                BufferedReader reader =
                    new BufferedReader(
                        new InputStreamReader(stream));
                String line;
                while ((line = reader.readLine()) != null)
                {
                    MyLog.d(name + "> " + line);
                }
            }
            catch (IOException e) {}
        }
    }
    
    private java.lang.Process process;
    private StreamGobbler errorGobbler;
    private StreamGobbler outputGobbler;
    
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

        // Find and kill dangling process
        // The issue is, a force quit of the main app won't kill
        // native child processes, and a dangling process may be
        // holding a resource we require.

        // TODO: this is a temporary solution (hopefully); see
        // also the comment about a security concern with dangling
        // processes in PsiphonAndroidService.java.
        // Alternate solution: ensure the child process dies
        // when the parent dies by modifying the child to read
        // from stdin:
        // http://stackoverflow.com/questions/2954520/android-how-to-receive-process-signals-in-an-activity-to-kill-child-process

        String psCommand = "ps";
        Process ps = Runtime.getRuntime().exec(psCommand);
        try
        {
            BufferedReader reader =
                new BufferedReader(new InputStreamReader(ps.getInputStream()));
            String line;
            for (int i = 0; ((line = reader.readLine()) != null); i++)
            {
                if (i > 0)
                {
                    String[] fields = line.split("[ ]+");
                    if (0 == fileName.compareTo(fields[8]))
                    {
                        android.os.Process.killProcess(Integer.parseInt(fields[1]));
                    }
                }
            }
            ps.waitFor();
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
        }
        
        // Start the process

        String nativeCommand = fileName + " " + arguments;
        this.process = Runtime.getRuntime().exec(nativeCommand);

        this.errorGobbler = new StreamGobbler(
            fileName + " ERROR",
            this.process.getErrorStream());
        this.errorGobbler.start();

        this.outputGobbler = new StreamGobbler(
            fileName + "OUTPUT",
            this.process.getInputStream());
        this.outputGobbler.start();
    }
    
    public void stop()
    {
        if (this.process == null)
        {
            return;
        }

        // TODO: graceful shutdown?

        // As recommended in "Five Common java.lang.Process Pitfalls"
        // http://kylecartmell.com/?p=9
        try
        {
            this.process.getErrorStream().close();
        }
        catch (IOException e) {}
        try
        {
            this.process.getInputStream().close();
        }
        catch (IOException e) {}
        try
        {
            this.process.getOutputStream().close();
        }
        catch (IOException e) {}
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
