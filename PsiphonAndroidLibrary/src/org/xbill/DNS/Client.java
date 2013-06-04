// Copyright (c) 2005 Brian Wellington (bwelling@xbill.org)

package org.xbill.DNS;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import org.xbill.DNS.utils.hexdump;

import com.psiphon3.psiphonlibrary.ServerInterface;
import com.psiphon3.psiphonlibrary.Tun2Socks;

class Client {

protected long endTime;
protected SelectionKey key;

protected
Client(SelectableChannel channel, long endTime) throws IOException {
	boolean done = false;
	Selector selector = null;
	this.endTime = endTime;
	try {
	    Tun2Socks.IProtectSocket protectSocket = PsiphonState.getPsiphonState().getProtectSocket();
	    if (protectSocket != null)
	    {
	        if (channel instanceof DatagramChannel)
	        {
	            protectSocket.doVpnProtect(((DatagramChannel)channel).socket());
	        }
	        else if (channel instanceof SocketChannel)
	        {
                protectSocket.doVpnProtect(((SocketChannel)channel).socket());	            
	        }
	    }
	    
		selector = Selector.open();
		channel.configureBlocking(false);
		key = channel.register(selector, SelectionKey.OP_READ);
		done = true;
	}
	finally {
		if (!done && selector != null)
			selector.close();
		if (!done)
			channel.close();
	}
}

static protected void
blockUntil(SelectionKey key, long endTime) throws IOException {
	long timeout = endTime - System.currentTimeMillis();
	int nkeys = 0;
	if (timeout > 0)
	{
		// PSIPHON -- interrupt when tunnel stop commanded
	    // original: nkeys = key.selector().select(timeout);
	    final int POLL_PERIOD_MILLISECONDS = 100;
	    for (int i = 0; i <= timeout && nkeys == 0; i += POLL_PERIOD_MILLISECONDS)
	    {
	        ServerInterface serverInterface = PsiphonState.getPsiphonState().getServerInterface();
	        if (serverInterface != null && serverInterface.isStopped())
	        {
	            break;
	        }
	        nkeys = key.selector().select(POLL_PERIOD_MILLISECONDS);
	    }
	}
    // PSIPHON
    // original: else if (timeout == 0)
	else if (timeout <= 0)
		nkeys = key.selector().selectNow();
	if (nkeys == 0)
		throw new SocketTimeoutException();
}

static protected void
verboseLog(String prefix, byte [] data) {
	if (Options.check("verbosemsg"))
		System.err.println(hexdump.dump(prefix, data));
}

void
cleanup() throws IOException {
	key.selector().close();
	key.channel().close();
}

}
