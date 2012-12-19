
package ch.ethz.ssh2.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.util.StringEncoder;

/**
 * ClientServerHello.
 * 
 * @author Christian Plattner
 * @version 2.50, 03/15/10
 */
public class ClientServerHello
{
	String server_line;
	String client_line;

	String server_versioncomment;

	public final static int readLineRN(InputStream is, byte[] buffer) throws IOException
	{
		int pos = 0;
		boolean need10 = false;
		int len = 0;
		while (true)
		{
			int c = is.read();
			if (c == -1)
				throw new IOException("Premature connection close");

			buffer[pos++] = (byte) c;

			if (c == 13)
			{
				need10 = true;
				continue;
			}

			if (c == 10)
				break;

			if (need10 == true)
				throw new IOException("Malformed line sent by the server, the line does not end correctly.");

			len++;
			if (pos >= buffer.length)
				throw new IOException("The server sent a too long line.");
		}

		return len;
	}

	public ClientServerHello(InputStream bi, OutputStream bo) throws IOException
	{
		client_line = "SSH-2.0-" + Connection.identification;

		bo.write(StringEncoder.GetBytes(client_line + "\r\n"));
		bo.flush();

		byte[] serverVersion = new byte[512];

		for (int i = 0; i < 50; i++)
		{
			int len = readLineRN(bi, serverVersion);

			server_line = StringEncoder.GetString(serverVersion, 0, len);

			if (server_line.startsWith("SSH-"))
				break;
		}

		if (server_line.startsWith("SSH-") == false)
			throw new IOException(
					"Malformed server identification string. There was no line starting with 'SSH-' amongst the first 50 lines.");

		if (server_line.startsWith("SSH-1.99-"))
			server_versioncomment = server_line.substring(9);
		else if (server_line.startsWith("SSH-2.0-"))
			server_versioncomment = server_line.substring(8);
		else
			throw new IOException("Server uses incompatible protocol, it is not SSH-2 compatible.");
	}

	/**
	 * @return Returns the client_versioncomment.
	 */
	public byte[] getClientString()
	{
		return StringEncoder.GetBytes(client_line);
	}

	/**
	 * @return Returns the server_versioncomment.
	 */
	public byte[] getServerString()
	{
		return StringEncoder.GetBytes(server_line);
	}
}
