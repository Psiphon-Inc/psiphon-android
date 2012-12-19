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

package ch.ethz.ssh2;

import java.io.IOException;

import ch.ethz.ssh2.channel.ChannelManager;
import ch.ethz.ssh2.channel.TransparentProxyAcceptThread;

public class TransparentProxyPortForwarder
{
    private ChannelManager connectionManager;
    private TransparentProxyAcceptThread acceptThread;

    TransparentProxyPortForwarder(ChannelManager connectionManager, int localPort)
            throws IOException
    {
        this.connectionManager = connectionManager;
        this.acceptThread = new TransparentProxyAcceptThread(this.connectionManager, localPort);
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    public void close() throws IOException
    {
        this.acceptThread.stopWorking();
    }
}
