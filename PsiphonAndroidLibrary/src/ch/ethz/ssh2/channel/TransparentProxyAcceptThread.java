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

package ch.ethz.ssh2.channel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;


// PSIPHON
// Based on LocalAcceptThread

public class TransparentProxyAcceptThread extends Thread implements IChannelWorkerThread
{
    private ChannelManager channelManager;
    private ServerSocket serverSocket;

    public TransparentProxyAcceptThread(ChannelManager channelManager, int localPort)
            throws IOException
    {
        this.channelManager = channelManager;

        try
        {
            this.serverSocket = new ServerSocket();
            this.serverSocket.setReuseAddress(true);
            this.serverSocket.bind(new InetSocketAddress(localPort));
        }
        catch (IOException e)
        {
            if (this.serverSocket != null)
            {
                this.serverSocket.close();
            }
            this.serverSocket = null;
            throw e;
        }
    }

    public void run()
    {
        try
        {
            this.channelManager.registerThread(this);
        }
        catch (IOException e)
        {
            stopWorking();
            return;
        }

        while (true)
        {
            Socket socket = null;

            try
            {
                socket = this.serverSocket.accept();
            }
            catch (IOException e)
            {
                stopWorking();
                return;
            }

            Channel channel = null;
            PsiphonStreamForwarder r2l = null;
            PsiphonStreamForwarder l2r = null;
            
            // Determine original destination IP address and port

            String originalDest = getOriginalDest(socket);
            
            String[] tokens = originalDest.split(":");
            if (tokens.length != 2) continue;
            String destIP = tokens[0];
            int destPort = Integer.parseInt(tokens[1]);

            try
            {
                /* This may fail, e.g., if the remote port is closed (in optimistic terms: not open yet) */

                channel = this.channelManager.openDirectTCPIPChannel(
                                destIP,
                                destPort,
                                socket.getInetAddress().getHostAddress(),
                                socket.getPort());

            }
            catch (IOException e)
            {
                /* Simply close the local socket and wait for the next incoming connection */

                try
                {
                    socket.close();
                }
                catch (IOException ignore)
                {
                }

                continue;
            }

            try
            {
                l2r = new PsiphonStreamForwarder(channel, null, socket.getInputStream(), channel.stdinStream, "LocalToRemote", null);
                r2l = new PsiphonStreamForwarder(channel, l2r, channel.stdoutStream, socket.getOutputStream(), "RemoteToLocal", null);
            }
            catch (IOException e)
            {
                try
                {
                    /* This message is only visible during debugging, since we discard the channel immediately */

                    channel.cm.closeChannel(channel, "Weird error during creation of StreamForwarder (" + e.getMessage() + ")", true);
                }
                catch (IOException ignore)
                {
                }

                continue;
            }

            r2l.setDaemon(true);
            l2r.setDaemon(true);
            r2l.start();
            l2r.start();
        }
    }

    public void stopWorking()
    {
        try
        {
            /* This will lead to an IOException in the ss.accept() call */
            this.serverSocket.close();
        }
        catch (IOException e)
        {
        }
    }

    private native String getOriginalDest(Socket socket);
    
    static
    {
        System.loadLibrary("OriginalDest");
    }
}
