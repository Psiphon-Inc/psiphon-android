
package ch.ethz.ssh2.channel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.http.Header;
import org.apache.http.RequestLine;
import org.apache.http.StatusLine;
import org.apache.http.message.BasicLineParser;

import android.util.Log;

import com.psiphon3.PsiphonAndroidStats;

/**
 * A StreamForwarder forwards data between two given streams. 
 * If two StreamForwarder threads are used (one for each direction)
 * then one can be configured to shutdown the underlying channel/socket
 * if both threads have finished forwarding (EOF).
 * 
 * @author Christian Plattner
 * @version 2.50, 03/15/10
 */
public class StreamForwarder extends Thread
{
	OutputStream os;
	InputStream is;
	byte[] buffer = new byte[Channel.CHANNEL_BUFFER_SIZE];
	Channel c;
	StreamForwarder sibling;
	Socket s;
	String mode;
	
	// Psiphon Stats
	String destHost = null;
    int MAX_HTTP_HEADERS_LENGTH = 16384;
	StringBuilder httpProtocolBuffer = new StringBuilder();
	boolean isHTTP = true;
	boolean isHttpRequester = true;
	int skipLength = 0;
	List<String> pendingRequestStack = Collections.synchronizedList(new ArrayList<String>());

    StreamForwarder(Channel c, StreamForwarder sibling, Socket s, InputStream is, OutputStream os, String mode)
            throws IOException
    {
        this.is = is;
        this.os = os;
        this.mode = mode;
        this.c = c;
        this.sibling = sibling;
        this.s = s;
    }

    StreamForwarder(Channel c, StreamForwarder sibling, Socket s, InputStream is, OutputStream os, String mode, String destHost)
            throws IOException
    {
        this(c, sibling, s, is, os, mode);
        this.destHost = destHost;
        this.isHttpRequester = (mode == "LocalToRemote");
    }

	public void run()
	{
		try
		{
			while (true)
			{
				int len = is.read(buffer);
				if (len <= 0)
					break;
				os.write(buffer, 0, len);
				os.flush();

				// Psiphon Stats
                if (this.destHost != null)
                {
                    doStats(len);
                }
			}
		}
		catch (IOException ignore)
		{
			try
			{
				c.cm.closeChannel(c, "Closed due to exception in StreamForwarder (" + mode + "): "
						+ ignore.getMessage(), true);
			}
			catch (IOException e)
			{
			}
		}
		finally
		{
			try
			{
				os.close();
			}
			catch (IOException e1)
			{
			}
			try
			{
				is.close();
			}
			catch (IOException e2)
			{
			}

			if (sibling != null)
			{
				while (sibling.isAlive())
				{
					try
					{
						sibling.join();
					}
					catch (InterruptedException e)
					{
					}
				}

				try
				{
					c.cm.closeChannel(c, "StreamForwarder (" + mode + ") is cleaning up the connection", true);
				}
				catch (IOException e3)
				{
				}

				try
				{
					if (s != null)
						s.close();
				}
				catch (IOException e1)
				{
				}
			}
		}
	}
	
	private void doStats(int bytes_read)
	{
	    // Psiphon Stats

	    // Bytes transfered stats

        if (this.isHttpRequester)
        {
            PsiphonAndroidStats stats = PsiphonAndroidStats.getStats();
            stats.addBytesSent(this.destHost, bytes_read);
        }
        else
        {
            PsiphonAndroidStats stats = PsiphonAndroidStats.getStats();
            stats.addBytesReceived(this.destHost, bytes_read);
        }

        // Page views stats

        // - Identify HTTP traffic by test parsing up until a maximum number of
        //   bytes have been transferred.
        // - Parse HTTP request/response stream and extract corresponding
        //   request URL and response content-type to count page views.
        // - Handles multiple HTTP messages in a single socket stream
        //   (keep-alive) by skipping POST/response content.

        if (this.isHTTP)
        {
            int offset = 0;
            int length = bytes_read;

            // When skipping content, don't add to buffer
            if (this.skipLength > 0)
            {
                if (this.skipLength >= bytes_read)
                {
                    Log.d("test", "case 1: " + Integer.toString(this.skipLength) + " " + Integer.toString(bytes_read));
                    length = 0;
                    this.skipLength -= bytes_read;
                }
                else
                {
                    Log.d("test", "case 2: " + Integer.toString(this.skipLength) + " " + Integer.toString(bytes_read));
                    offset = this.skipLength;
                    length = bytes_read - this.skipLength;
                    this.skipLength = 0;
                }
            }

            this.httpProtocolBuffer.append(new String(buffer, offset, length));
            
            // TODO: check for first request/response line as earlier HTTP check 

            // May be multiple HTTP messages in one socket read
            while (true)
            {
                // Check if we have complete HTTP headers                            
                int headersEnd = this.httpProtocolBuffer.indexOf("\r\n\r\n");
                if (headersEnd == -1 && this.httpProtocolBuffer.length() >= MAX_HTTP_HEADERS_LENGTH)
                {
                    // This isn't HTTP, so stop buffering forever
                    this.httpProtocolBuffer = null;
                    this.isHTTP = false;
                    break;
                }
                else if (headersEnd == -1)
                {
                    // Incomplete header, read more data
                    break;
                }
                else if (headersEnd != -1)
                {
                    String headers = this.httpProtocolBuffer.substring(0, headersEnd);
                    this.httpProtocolBuffer.delete(0, headersEnd);
                    this.httpProtocolBuffer.delete(0, "\r\n\r\n".length());

                    String lines[] = headers.split("\r\n");
                    BasicLineParser parser = new BasicLineParser();

                    if (this.isHttpRequester)
                    {
                        RequestLine requestLine = BasicLineParser.parseRequestLine(lines[0], parser); // TODO: errors?
                        
                        // Push URI on stack to be consumed by peer on response
                        // (Assumes HTTP responses return in request order)
                        String uri = requestLine.getUri();
                        Log.d("StreamForwarder", "HTTP request: " + this.destHost + " " + uri);
                        this.pendingRequestStack.add(0, uri);
                    }
                    else
                    {
                        // Get status line
                        StatusLine statusLine = BasicLineParser.parseStatusLine(lines[0], parser); // TODO: catch org.apache.http.ParseException
                        // TODO: handle status codes
                        
                        // More response case below...
                    }
                        
                    // Parse content headers
                    String contentType = null;
                    int contentLength = 0;

                    for (int i = 1; i < lines.length; i++)
                    {
                        Header header = BasicLineParser.parseHeader(lines[i], parser);
                        if (header.getName().compareToIgnoreCase("Content-Type") == 0)
                        {
                            contentType = header.getValue();
                        }
                        else if (header.getName().compareToIgnoreCase("Content-Length") == 0)
                        {
                            String value = header.getValue();
                            if (value != null)
                            {
                                contentLength = Integer.parseInt(value); // TODO: catch NumberFormatException
                            }
                        }
                    }

                    if (!this.isHttpRequester)
                    {
                        // Continue response case now that headers are parsed...
                        
                        // Pop request URI from peer's stack
                        // (Assumes HTTP responses return in response order)
                        try
                        {
                            String requestURI = this.sibling.pendingRequestStack.remove(0); // TODO: catch InvalidIndexException
                            Log.d("StreamForwarder", "HTTP response: " + this.destHost + " " + requestURI + " " + contentType);
                            // TODO: increment stats (based on MIME type, regex etc.)
                        }
                        catch (IndexOutOfBoundsException e)
                        {
                            Log.d("test", "stack underflow");
                        }
                    }

                    // Skip content
                    // TODO: handle HTTP chunking, "Transfer-Encoding: chunked"
                    this.skipLength = contentLength;
                    if (this.skipLength > 0)
                    {
                        // Skip content already in the buffer
                        // See above for skip case before data is in the buffer
                        if (this.httpProtocolBuffer.length() >= this.skipLength)
                        {
                            Log.d("test", "case 3: " + Integer.toString(this.httpProtocolBuffer.length()) + " " + Integer.toString(this.skipLength));
                            this.httpProtocolBuffer.delete(0, this.skipLength);
                            this.skipLength = 0;
                        }
                        else
                        {
                            Log.d("test", "case 4: " + Integer.toString(this.httpProtocolBuffer.length()) + " " + Integer.toString(this.skipLength));
                            this.skipLength -= this.httpProtocolBuffer.length();                                        
                            this.httpProtocolBuffer.delete(0, this.httpProtocolBuffer.length());
                        }
                    }
                }
            }
        }
	}
}
