
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
import com.psiphon3.PsiphonConstants;

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
    private String destHost = null;
    private final int MAX_HTTP_HEADERS_LENGTH = 16384;
    private ByteBuffer httpProtocolBuffer = new ByteBuffer();
    private boolean isHttp = true;
    private boolean isHttpRequester = true;
    private long skipLength = 0;
    private boolean expectingChunkHeader = false;
    private List<String> pendingRequestStack = Collections.synchronizedList(new ArrayList<String>());

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

                // Psiphon Stats
                // NOTE: need to process stats before write to ensure
                // request stack is in correct state before response arrives
                // to sibling thread
                if (this.destHost != null)
                {
                    doStats(len);
                }

                os.write(buffer, 0, len);
                os.flush();
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
    
    // TODO: remove Log.d("test", ...)
    
    // NOTE: lots of opportunity for optimization
    class ByteBuffer
    {
        private final int ALLOCATE_SIZE = 8192;
        private byte[] buffer = new byte[ALLOCATE_SIZE];
        private int position = 0;
        
        public void append(byte[] buffer, int offset, int length)
        {
            Log.d("test", Thread.currentThread().toString() + "append: " + Integer.toString(this.buffer.length) + " " + Integer.toString(this.position) + " " + Integer.toString(length));
            if (this.position + length > this.buffer.length)
            {
                int newSize = ALLOCATE_SIZE*((this.position + length)/ALLOCATE_SIZE + 1);
                Log.d("test", Thread.currentThread().toString() + "newSize: " + Integer.toString(newSize));
                byte[] temp = new byte[newSize];
                System.arraycopy(this.buffer, 0, temp, 0, this.buffer.length);
                this.buffer = temp;
                Log.d("test", Thread.currentThread().toString() + "actual new size: " + Integer.toString(this.buffer.length));
            }
            System.arraycopy(buffer, offset, this.buffer, this.position, length);
            this.position += length;
        }

        public int length()
        {
            return this.position;
        }

        public void deleteFromStart(int length) throws Exception
        {
            Log.d("test", Thread.currentThread().toString() + "deleteFromStart: " + Integer.toString(this.buffer.length) + " " + Integer.toString(this.position) + " " + Integer.toString(length));
            if (length > this.position)
            {
                throw new Exception("deleteFromStart length too long");
            }
            System.arraycopy(this.buffer, length, this.buffer, 0, this.position - length);
            this.position -= length;
        }
        
        public String getStringAtStart(int length) throws Exception
        {
            Log.d("test", Thread.currentThread().toString() + "getStringAtStart: " + Integer.toString(this.buffer.length) + " " + Integer.toString(this.position) + " " + Integer.toString(length));
            if (length > this.position)
            {
                throw new Exception("deleteFromStart length too long");
            }
            byte[] substring = new byte[length];
            System.arraycopy(this.buffer, 0, substring, 0, length);
            return new String(substring);
        }
        
        public int indexOf(String string)
        {
            byte[] target = string.getBytes();
            for (int i = 0; i <= this.position - target.length; i++)
            {
                boolean match = true;
                for (int j = 0; j < target.length; j++)
                {
                    if (this.buffer[i + j] != target[j])
                    {
                        match = false;
                        break;
                    }
                }
                if (match)
                {
                    Log.d("test", Thread.currentThread().toString() + "found indexOf: " + Integer.toString(this.buffer.length) + " " + Integer.toString(this.position) + " " + Integer.toString(string.length()) + " " + Integer.toString(i));
                    return i;
                }
            }
            
            Log.d("test", Thread.currentThread().toString() + "not found indexOf: " + Integer.toString(this.buffer.length) + " " + Integer.toString(this.position) + " " + Integer.toString(string.length()));
            return -1;
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
        
        // NOTE:
        // This parsing code is now quite complicated and could benefit from
        // a rewrite (ex., state machine) if it needs to handle any more cases.
        // Alternatively, use a 3rd part HTTP stream parser. Some candidates:
        // - Netty library; see Snoop example here: http://docs.jboss.org/netty/3.2/xref/org/jboss/netty/example/http/snoop/package-summary.html
        // - Port of Node.js HTTP parser to Java: https://github.com/a2800276/http-parser.java

        if (this.isHttp)
        {
            try
            {
                Log.d("test", Thread.currentThread().toString() + "skip: " + Long.toString(this.skipLength) + "buffer:" + Integer.toString(bytes_read) + " " + new String(buffer));

                long offset = 0;
                long length = bytes_read;

                // When skipping content (either content length or chunks), don't add to buffer
                if (this.skipLength > 0)
                {
                    if (this.skipLength >= bytes_read)
                    {
                        length = 0;
                        this.skipLength -= bytes_read;
                        
                        // Will go to next socket read
                    }
                    else
                    {
                        offset = this.skipLength;
                        length = bytes_read - this.skipLength;
                        this.skipLength = 0;
                    }
                }

                this.httpProtocolBuffer.append(buffer, (int) offset, (int) length);
                Log.d("test", Thread.currentThread().toString() + "actual: " + Long.toString(length) + " appended: " + Integer.toString(this.httpProtocolBuffer.length()));

                if (this.skipLength == 0)
                {
                    // Handle multiple HTTP messages within one socket read and across socket reads
                    while (true)
                    {
                        // Handle multiple chunks within one socket read and across socket reads
                        while (this.expectingChunkHeader)
                        {
                            // TODO: handle trailer
                            
                            final String lastChunkHeader = "0\r\n\r\n";
                            if (this.httpProtocolBuffer.length() >= lastChunkHeader.length() 
                                && this.httpProtocolBuffer.getStringAtStart(lastChunkHeader.length()) == lastChunkHeader)
                            {
                                // The final chunk has a trailing CRLF. Stop processing chunks now.
                                this.httpProtocolBuffer.deleteFromStart("0\r\n\r\n".length());
                                this.expectingChunkHeader = false;
                                break;
                            }
                            else
                            {
                                // The previous chunk content should already be skipped, so we're either
                                // at the next chunk header or we need more bytes
                                final String chunkHeaderTerminator = "\r\n";
                                int chunkHeaderEnd = this.httpProtocolBuffer.indexOf(chunkHeaderTerminator);
                                if (chunkHeaderEnd != -1)
                                {
                                    long chunkLength = Long.parseLong(
                                        this.httpProtocolBuffer.getStringAtStart(chunkHeaderEnd), 16);
                                    this.httpProtocolBuffer.deleteFromStart(chunkHeaderEnd);
                                    this.httpProtocolBuffer.deleteFromStart(chunkHeaderTerminator.length());
                                    if (chunkLength >= this.httpProtocolBuffer.length())
                                    {
                                        this.skipLength = chunkLength - this.httpProtocolBuffer.length();
                                        this.httpProtocolBuffer.deleteFromStart(this.httpProtocolBuffer.length());
                                        // Go to next socket read to process skipLength first and
                                        // then next expectingChunkHeader 
                                        break;
                                    }
                                    else
                                    {
                                        this.httpProtocolBuffer.deleteFromStart((int) chunkLength);
                                        // Go to next expectingChunkHeader
                                    }
                                }
                                else
                                {
                                    // Go to next socket read with expectingChunkHeader
                                    break;
                                }
                            }
                        }
                        
                        // If we're still expecting the final chunk, we need to read more data
                        if (this.expectingChunkHeader)
                        {
                            break;
                        }
                    
                        // Find first complete HTTP request/response in the buffer
                        // NOTE: could check for first request/response line as earlier HTTP check 
                        final String headersTerminator = "\r\n\r\n";
                        int headersEnd = this.httpProtocolBuffer.indexOf(headersTerminator);
                        if (headersEnd == -1 && this.httpProtocolBuffer.length() >= MAX_HTTP_HEADERS_LENGTH)
                        {
                            // This isn't HTTP, so stop buffering forever
                            this.httpProtocolBuffer = null;
                            this.isHttp = false;
                            break;
                        }
                        else if (headersEnd == -1)
                        {
                            // Incomplete header, read more data
                            break;
                        }
                        else if (headersEnd != -1)
                        {
                            String headers = this.httpProtocolBuffer.getStringAtStart(headersEnd);
                            this.httpProtocolBuffer.deleteFromStart(headersEnd);
                            this.httpProtocolBuffer.deleteFromStart(headersTerminator.length());
    
                            final String headerSeparator = "\r\n";
                            String lines[] = headers.split(headerSeparator);
                            BasicLineParser parser = new BasicLineParser();
                            int responseStatusCode = 0;
    
                            if (this.isHttpRequester)
                            {
                                RequestLine requestLine = BasicLineParser.parseRequestLine(lines[0], parser);
                                
                                // Push URI on stack to be consumed by peer on response
                                // (Assumes HTTP responses return in request order)
                                String uri = requestLine.getUri();
                                Log.d("test", Thread.currentThread().toString() + "push HTTP request: " + this.destHost + " " + uri);
                                this.pendingRequestStack.add(0, uri);
                            }
                            else
                            {
                                StatusLine statusLine = BasicLineParser.parseStatusLine(lines[0], parser);
                                responseStatusCode = statusLine.getStatusCode();
                                
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
                                        contentLength = Integer.parseInt(value);
                                    }
                                }
                                else if (header.getName().compareToIgnoreCase("Transfer-Encoding") == 0)
                                {
                                    String value = header.getValue();
                                    if (value.compareToIgnoreCase("chunked") == 0)
                                    {
                                        this.expectingChunkHeader = true;
                                        throw new Exception("can't handle chunking");  // TEMP!
                                    }
                                }
                            }
    
                            if (!this.isHttpRequester)
                            {
                                // ...continue response case now that headers are parseds
                                
                                // Pop request URI from peer's stack
                                // (Assumes HTTP responses return in response order)
                                Log.d("test", Thread.currentThread().toString() + "HTTP pop response for " + this.sibling.toString());
                                String requestURI = this.sibling.pendingRequestStack.remove(0);
                                Log.d("test", Thread.currentThread().toString() + "HTTP pop response: " + this.destHost + " " + requestURI + " " + contentType);
                                // TODO: increment stats (based on responseStatusCode, contentType, regex etc.)
                            }
    
                            // Chunk encoding takes precedence over content length
                            // (http://tools.ietf.org/html/rfc2616#section-4.4)
                            if (this.expectingChunkHeader)
                            {
                                contentLength = 0;
                            }
                            
                            // Skip content
                            this.skipLength = contentLength;
                            if (this.skipLength > 0)
                            {
                                // Skip content already in the buffer
                                // See above for skip case before data is in the buffer
                                if (this.httpProtocolBuffer.length() >= this.skipLength)
                                {
                                    this.httpProtocolBuffer.deleteFromStart((int) this.skipLength);
                                    this.skipLength = 0;
                                }
                                else
                                {
                                    this.skipLength -= this.httpProtocolBuffer.length();                                        
                                    this.httpProtocolBuffer.deleteFromStart(this.httpProtocolBuffer.length());
                                }
                            }
                        }
                    }
                }
            }
            catch (Exception e)
            {
                Log.e(PsiphonConstants.TAG, Thread.currentThread().toString() + "Page view stats error: " + e);
                // Assume this isn't HTTP, so stop buffering forever
                this.httpProtocolBuffer = null;
                this.isHttp = false;
                
                // TODO: force sibling to stop page views as well
            }
        }
    }
}
