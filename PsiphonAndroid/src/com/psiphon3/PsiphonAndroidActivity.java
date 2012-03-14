package com.psiphon3;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import ch.ethz.ssh2.*;
import java.io.IOException;

public class PsiphonAndroidActivity extends Activity 
{
    private Thread tunnelThread;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        tunnelThread = new Thread(new Runnable()
        {
            public void run()
            {
                testTunnel();
            }
        });

        tunnelThread.start();    
    }

    public void testTunnel()
    {
        String hostname = "...";
        String username = "...";
        String password = "...";

        try
        {
            Connection conn = new Connection(hostname);
            conn.connect();
            Log.d("Psiphon", "SSH connected");

            boolean isAuthenticated = conn.authenticateWithPassword(username, password);
            if (isAuthenticated == false)
            {
                Log.e("Psiphon", "can't authenticate");
                return;
            }
            Log.d("Psiphon", "SSH authenticated");

            DynamicPortForwarder socks = conn.createDynamicPortForwarder(1080);
            Log.d("Psiphon", "SOCKS running");

            try
            {
                Thread.sleep(60000);
            }
            catch (InterruptedException e)
            {
            }            

            socks.close();
            conn.close();
            Log.d("Psiphon", "SSH/SOCKS closed");
        }
        catch (IOException e)
        {
            Log.e("Psiphon", "IOException", e);
            return;
        }
    }
}
