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

import com.psiphon3.psiphonlibrary.PsiphonConstants;
import com.psiphon3.psiphonlibrary.PsiphonData;

public class PsiphonStreamForwarder extends Thread
{
    static final int IO_BUFFER_SIZE = 4096; 
    
    OutputStream os;
    InputStream is;
    byte[] buffer = new byte[IO_BUFFER_SIZE];
    Channel c;
    PsiphonStreamForwarder sibling;
    Socket s;
    String mode;
    
    // Psiphon Stats
    private String destHost = null;
    private final int MAX_HTTP_HEADERS_LENGTH = 16384;
    private ByteBuffer httpProtocolBuffer = new ByteBuffer();
    private boolean isHttp = true;
    private boolean isHttpRequester = true;
    private long skipLength = 0;
    private boolean expectingChunkSize = false;
    private boolean expectingChunkEnd = false;
    private List<String> pendingRequestStack = Collections.synchronizedList(new ArrayList<String>());

    PsiphonStreamForwarder(
            Channel c,
            PsiphonStreamForwarder sibling,
            InputStream is,
            OutputStream os,
            String mode,
            String destHost)
        throws IOException
    {
        this.is = is;
        this.os = os;
        this.mode = mode;
        this.c = c;
        this.sibling = sibling;
        this.destHost = destHost;
        this.isHttpRequester = (mode == "LocalToRemote");
    }

    public void run()
    {
        try
        {
            PsiphonData.ReportedStats reportedStats = PsiphonData.getPsiphonData().getReportedStats();
            
            while (true)
            {
                int len = is.read(buffer);
                if (len <= 0)
                    break;

                // Psiphon Stats
                // NOTE: need to process stats before write to ensure
                // request stack is in correct state before response arrives
                // to sibling thread
                if (reportedStats != null)
                {
                    doStats(reportedStats, len);
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
    
    // NOTE: lots of opportunity for optimization
    class ByteBuffer
    {
        private final int ALLOCATE_SIZE = 8192;
        private byte[] buffer = new byte[ALLOCATE_SIZE];
        private int position = 0;
        
        public void append(byte[] buffer, int offset, int length)
        {
            if (this.position + length > this.buffer.length)
            {
                int newSize = ALLOCATE_SIZE*((this.position + length)/ALLOCATE_SIZE + 1);
                byte[] temp = new byte[newSize];
                System.arraycopy(this.buffer, 0, temp, 0, this.buffer.length);
                this.buffer = temp;
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
            if (length > this.position)
            {
                throw new Exception("deleteFromStart length too long");
            }
            System.arraycopy(this.buffer, length, this.buffer, 0, this.position - length);
            this.position -= length;
        }
        
        public String getStringAtStart(int length) throws Exception
        {
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
                    return i;
                }
            }            
            return -1;
        }
    }
    
    private void doStats(PsiphonData.ReportedStats reportedStats, int bytes_read)
    {
        // Psiphon Stats

        // Bytes transfered stats

        if (this.isHttpRequester)
        {
            reportedStats.addBytesSent(bytes_read);
        }
        else
        {
            reportedStats.addBytesReceived(bytes_read);
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

                // Don't try to parse if skipping, as the content body could look like headers
                if (this.skipLength == 0)
                {
                    // Handle multiple HTTP messages within one socket read and across socket reads
                    while (true)
                    {
                        // Handle multiple chunks within one socket read and across socket reads
                        while (this.expectingChunkSize)
                        {
                            // The previous chunk content should already be skipped, so we're either
                            // at the next chunk header or we need more bytes
                            final String chunkDelimiter = "\r\n";
                            int chunkHeaderEnd = this.httpProtocolBuffer.indexOf(chunkDelimiter);
                            if (chunkHeaderEnd == -1)
                            {
                                // Go to next socket read with expectingChunkSize
                                break;
                            }
                            else
                            {
                                String chunkLengthStr = this.httpProtocolBuffer.getStringAtStart(chunkHeaderEnd);
                                this.httpProtocolBuffer.deleteFromStart(chunkHeaderEnd);
                                // Ignore chunk extensions
                                int chunkExtension = chunkLengthStr.indexOf(";");
                                if (chunkExtension != -1)
                                {
                                    chunkLengthStr = chunkLengthStr.substring(0, chunkExtension);
                                }
                                long chunkLength = Long.parseLong(chunkLengthStr.trim(), 16);

                                if (chunkLength == 0)
                                {
                                    // On last chunk, skip until double CRLF, possibly after trailers
                                    this.expectingChunkSize = false;
                                    this.expectingChunkEnd = true;
                                    break;
                                }
                                else
                                {
                                    // Add to chunkLength to skip CRLF after data
                                    chunkLength += chunkDelimiter.length();
                                    
                                    // Consume CRLF after size and skip chunkLength bytes
                                    this.httpProtocolBuffer.deleteFromStart(chunkDelimiter.length());
                                    if (chunkLength >= this.httpProtocolBuffer.length())
                                    {
                                        this.skipLength = chunkLength - this.httpProtocolBuffer.length();
                                        this.httpProtocolBuffer.deleteFromStart(this.httpProtocolBuffer.length());
                                        // Go to next socket read to process skipLength first and
                                        // then next expectingChunkSize 
                                        break;
                                    }
                                    else
                                    {
                                        this.httpProtocolBuffer.deleteFromStart((int) chunkLength);
                                        // Go to next expectingChunkSize
                                        continue;
                                    }
                                }
                            }
                        }
                        
                        while (this.expectingChunkEnd)
                        {
                            final String chunkTerminator = "\r\n\r\n";
                            int chunkHeaderEnd = this.httpProtocolBuffer.indexOf(chunkTerminator);
                            if (chunkHeaderEnd == -1)
                            {
                                // Go to next socket read with expectingChunkEnd
                                break;
                            }
                            else
                            {
                                // Done processing chunks
                                this.httpProtocolBuffer.deleteFromStart(chunkHeaderEnd);
                                this.httpProtocolBuffer.deleteFromStart(chunkTerminator.length());
                                this.expectingChunkEnd = false;
                            }
                        }

                        // If we're still expecting chunk bytes, we need to read more data
                        if (this.expectingChunkSize || this.expectingChunkEnd)
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
    
                            // Parse headers:
                            // Host header is used for stats (socket destination host is used when Host header is missing)
                            // Content/Transfer-Encoding header is used to skip content

                            String host = this.destHost == null ? "" : this.destHost;
                            String contentType = null;
                            int contentLength = 0;
    
                            for (int i = 1; i < lines.length; i++)
                            {
                                Header header = BasicLineParser.parseHeader(lines[i], parser);
                                if (header.getName().compareToIgnoreCase("Host") == 0)
                                {
                                    // TODO: strip port suffix?
                                    host = header.getValue();
                                }
                                else if (header.getName().compareToIgnoreCase("Content-Type") == 0)
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
                                        this.expectingChunkSize = true;
                                        this.expectingChunkEnd = false;
                                    }
                                }
                            }
                            
                            if (this.isHttpRequester)
                            {
                                RequestLine requestLine = BasicLineParser.parseRequestLine(lines[0], parser);
                                                                
                                // Push URI on stack to be consumed by peer on response
                                // (Assumes HTTP responses return in request order)
                                String uri = requestLine.getUri();

                                // TODO: " " delimiter is ok? (http://en.wikipedia.org/wiki/Hostname#Restrictions_on_valid_host_names)
                                this.pendingRequestStack.add(0, host + " " + uri);
                            }
                            else
                            {
                                StatusLine statusLine = BasicLineParser.parseStatusLine(lines[0], parser);
                                responseStatusCode = statusLine.getStatusCode();
                                
                                // Pop request URI from peer's stack
                                // (Assumes HTTP responses return in request order)
                                String[] requestInfo = this.sibling.pendingRequestStack.remove(0).split(" ");
                                String requestHost = requestInfo[0];
                                String requestURI = requestInfo[1];
                                
                                // We update the stats if the response code is 200 
                                // and the content type is some kind of HTML page.
                                if (requestHost.length() > 0
                                    && requestURI.length() > 0
                                    && responseStatusCode == 200
                                    && contentType != null
                                    && (contentType.contains("text/html")
                                        || contentType.contains("application/xhtml+xml")))
                                {
                                    reportedStats.upsertPageView("http://"+requestHost+requestURI);
                                }
                            }
    
                            // Chunk encoding takes precedence over content length
                            // (http://tools.ietf.org/html/rfc2616#section-4.4)
                            if (this.expectingChunkSize)
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
                Log.e(PsiphonConstants.TAG, "Page view stats error", e);
                // Assume this isn't HTTP, so stop buffering forever
                this.httpProtocolBuffer = null;
                this.isHttp = false;
                
                // TODO: force sibling to stop page views as well
            }
        }
    }
}
