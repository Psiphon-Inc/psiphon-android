/*
 * Copyright (c) 2013, Psiphon Inc.
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

// With snippets from:
// http://code.google.com/p/badvpn/source/browse/trunk/tun2socks/tun2socks.c
/**
 * @file tun2socks.c
 * @author Ambroz Bizjak <ambrop7@gmail.com>
 *
 * @section LICENSE
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the author nor the
 *    names of its contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


/*

In-progress TODOs
=================

- finish cleanup code (Fd2Socks.reset())
- don't call simply when epoll_wait returns; use time-elapsed logic
- DNS
  -- intercept and parse UDP packets
  -- resend packets destinated to port 53 to local (proxied) DNS server
  -- listen for datagrams from DNS server and feed back into IP fd
- circular buffer implementation

- verify that local socket connections (stream and datagram) don't need protect() from VPN
- need real write buffer for VPN fd?
- decide if need to flush or drop relayed TCP data when either side closes connection
- documentation

*/


#include <vector>
#include <algorithm>
#include <lwip/init.h>
#include <lwip/tcp_impl.h>
#include <lwip/netif.h>
#include <lwip/tcp.h>
#include "jni.h"


static Fd2Socks* gFd2Socks = 0;


JNIEXPORT jint JNICALL Java_com_psiphon3_psiphonlibrary_fd2socks_signalStop(
    JNIEnv* env)
{
    if (0 != gFd2Socks)
    {
        gFd2Socks->signalStop();
    }
    return 0;
}


JNIEXPORT jint JNICALL Java_com_psiphon3_psiphonlibrary_fd2socks_run(
    JNIEnv* env,
    jint vpnIpPacketFileDescriptor,
    jstring vpnIpAddress,
    jstring vpnNetMask,
    jstring socksServerIpAddress,
    jint socksServerPort)
{
    Fd2Socks fd2Socks;
    gFd2Socks = &fd2Socks;

    const char* vpnIpAddressStr = env->GetStringUTFChars(vpnIpAddress, 0);
    const char* vpnNetMaskStr = env->GetStringUTFChars(vpnNetMask, 0);
    const char* socksServerIpAddressStr = env->GetStringUTFChars(socksServerIpAddress, 0);

    bool result = fd2Socks.run(
            vpnIpAddressStr,
            vpnNetMaskStr,
            vpnIpPacketFileDescriptor,
            socksServerIpAddressStr
            socksServerPort);

    gFd2Socks = 0;

    env->ReleaseStringUTFChars(vpnIpAddressStr, vpnIpAddress, 0);
    env->ReleaseStringUTFChars(vpnNetMaskStr, vpnNetMask, 0);
    env->ReleaseStringUTFChars(socksServerIpAddressStr, socksServerIpAddress, 0);

    return result ? 1 : 0;
}


static void log(const char* message)
{
    if (0 != gFd2Socks)
    {
        gFd2Socks->log(message);
    }
}


const static size_t IP_PACKET_MTU = 1500;
const static size_t SOCKS_BUFFER_SIZE = 8192;
const static int MAX_EPOLL_EVENTS = 100;


static extern "C" err_t initNetifCallback(struct netif* netif);
static extern "C" err_t netifOutputCallback(struct netif* netif, struct pbuf* p, ip_addr_t* ipaddr);
static extern "C" err_t acceptCallback(void* arg, struct tcp_pcb* newPcb, err_t err);;
static extern "C" err_t tcpClientReceiveCallback(void* arg, struct tcp_pcb* pcb, struct pbuf* p, err_t err);
static extern "C" err_t tcpClientSentCallback(void* arg, struct tcp_pcb* tpcb, u16_t len);
static extern "C" void tcpClientErrorCallback(void* arg, err_t err);


/*
class Buffer
{
protected:
    ...

public:
    Buffer()
    {
        ...
    }

    virtual ~Buffer()
    {
        ...
    }

    bool init(size_t capacity)
    {
        ...
    }

    void clear()
    {

    }

    size_t getCapacity()
    {
        ...
    }

    size_t getRemainingCapacity()
    {
        ...
    }

    bool hasData()
    {
        return getDataSize() > 0;
    }

    size_t getDataSize()
    {
        ...
    }

    const uint8_t* getData()
    {
        ...
    }

    uint8_t* allocateData(size_t size)
    {
        ...
    }

    bool appendData(const uint8_t* data, size_t size)
    {
        ...
    }

    void trimData(size_t size)
    {
        ...
    }

    void trimDataFromEnd(size_t size)
    {
        ...
    }

    size_t getContiguousWriteBufferSize()
    {
        ...
    }

    uint8_t* getContiguousWriteBuffer()
    {
        ...
    }

    void commitWrite(size_t size)
    {
        ...
    }

    size_t getContiguousReadBufferSize()
    {
        ...
    }

    uint8_t* getContiguousReadBuffer()
    {
        ...
    }

};
*/


class TcpClientProxy
{
protected:

    Fd2Socks* mFd2Socks;
    struct tcp_pcb* mLwipState;
    socket mSocksSocket;
    struct epoll_event mSocksEpollEvent;
    bool receievedSocksServerResponse;
    struct Buffer mSocksSendBuffer;
    struct Buffer mSocksReceiveBuffer;

public:

    TcpClientProxy(Fd2Socks* fd2Socks)
    {
        mFd2Socks = fd2Socks;
        mLwipState = 0;
        mSocksSocket = NULL;
        memset(&mSocksEpollEvent, 0, sizeof(mSocksEpollEvent));
        receievedSocksServerResponse = false;
        mSocksSendBuffer.init(SOCKS_BUFFER_SIZE);
        mSocksReceiveBuffer.init(SOCKS_BUFFER_SIZE);
    }

    virtual ~TcpClientProxy()
    {
        if (0 != mLwipState)
        {
            tcp_err(mLwipState, NULL);
            tcp_recv(mLwipState, NULL);
            tcp_sent(mLwipState, NULL);
            if (ERR_OK != tcp_close(mLwipState))
            {
                tcp_abort(mLwipState);
            }
            free(mLwipState);
        }

        if (0 != mSocksEpollEvent.data.ptr)
        {
            epoll_ctr(mFd2Socks->mEpollFacility, EPOLL_CTL_DEL, mSocksSocket, 0);
        }

        if (0 != mSocksSocket)
        {
            close(mSocksSocket);
        }
    }

    bool init(struct tcp_pcb* newPcb)
    {
        mLwipState = newPcb;

        tcp_arg(mLwipState, this);
        tcp_err(mLwipState, tcpClientErrorCallback);
        tcp_recv(mLwipState, tcpClientReceiveCallback);
        tcp_sent(mLwipState, tcpClientSentCallback);

        uint16_t originalDestinationPort = hton16(newPcb->local_port);
        uint32_t originalDestinationIpAddress = newPcb->local_ip.addr;

        // Initiate connection to SOCKS proxy.

        struct protoent* protocol = getprotobyname("tcp");
        if (!protocol)
        {
            log("fd2socks TcpClient init: getprotobyname failed");
            return false;
        }

        if (-1 == (mSocksSocket = socket(AF_INET, SOCK_STREAM, protocol->p_proto))
        {
            log("fd2socks TcpClient init: socket failed %d", errno);
            return false;
        }

        if (-1 == (fcntl(mSocksSocket, F_SETFL, O_NONBLOCK))
        {
            log("fd2socks TcpClient init: fcntl failed %d", errno);
            return false;
        }

        if (-1 == (connect(mSocksSocket, &(mFd2Socks->mSocksAddress), sizeof(mFd2Socks->mSocksAddress)))
        {
            if (errno != EINPROGRESS)
            {
                log("fd2socks TcpClient init: connect failed %d", errno);
                return false;
            }
        }

        // Add SOCKS client to epoll facility.

        mSocksEpollEvent.events = EPOLLIN | EPOLLPRI | EPOLLOUT | EPOLLERR | EPOLLHUP;
        mSocksEpollEvent.data.ptr = tcpClient;

        if (-1 == epoll_ctl(mFd2Socks->epollFacility, EPOLL_CTL_ADD, mSocksSocket, mSocksEpollEvent))
        {
            log("fd2socks TcpClient init: epoll_ctl failed %d", errno);
            return false;
        }

        // When the socket connect succeeds/fails, we expect epoll to complete with EPOLLIN. Then
        // we'll write the SOCKS header which we're buffering now. That write will indicate if the
        // connect succeeded.

        // SOCKS 4 CONNECT request header.

        uint8_t socksConnectRequest[9];
        socksConnectRequest[0] = 0x04;
        socksConnectRequest[1] = 0x01;
        memcpy(socksConnectRequest + 2, &destinationPort, 2);
        memcpy(socksConnectRequest + 4, &destinationIpAddress, 4);
        socksConnectRequest[8] = 0x00;

        if (!mSocketSendBuffer.appendData(mSocketSendBuffer, socksConnectRequest, sizeof(socksConnectRequest)))
        {
            log("fd2socks TcpClient init: epoll_ctl failed %d", errno);
            return false;
        }

        return true;
    }

    bool readSocksData()
    {
        // Read data from the SOCKS server.

        while (mSocksReceiveBuffer.getRemainingCapacity() > 0)
        {
            size_t len = mSocksReceiveBuffer.getContiguousWriteBufferSize();
            uint8_t* data = mSocksReceiveBuffer.getContiguousWriteBuffer();

            ssize_t readCount;
            if (-1 == (readCount = read(mSocksSocket, data, len))
            {
                if (EGAIN == errno)
                {
                    break;
                }

                log("fd2socks readSocksData: read failed %d", errno);
                return false;
            }
            else if (readCount == 0)
            {
                // On IP packet EOF, trigger a stop.

                return false;
            }
            else if (readCount > 0)
            {
                mSocksReceiveBuffer.commitWrite(readCount);
            }
        }

        if (!receievedSocksServerResponse)
        {
            // SOCKS 4 CONNECT response.

            size_t expectedResponseSize = 8;

            if (mSocksReceiveBuffer.getContiguousReadBufferSize() < expectedResponseSize)
            {
                // Wait for more bytes.
                return true;
            }

            // Check that the status byte contains the "granted" value.
            uint8_t status = mSocksReceiveBuffer.getContiguousReadBuffer()[1];
            if (0x5a != status)
            {
                log("fd2socks readSocksData: unexpected SOCKS status %d", (int)status);
                return false;
            }

            mSocksReceiveBuffer.commitRead(expectedResponseSize);
            receievedSocksServerResponse = true;
        }

        return writeTcpData();
    }

    bool writeTcpData()
    {
        // Relay read data through the TCP stack back to the original client.

        boolean needTcpOutput = false;
        while (mSocksReceiveBuffer.hasData())
        {
            size_t capacity = tcp_sndbuf(mLwipState);
            size_t dataSize = mSocksReceiveBuffer.getContiguousReadBufferSize();
            size_t len = (capacity < dataSize ? capacity : dataSize);

            err_t err = tcp_write(mLwipState, mSocksReceiveBuffer.getContiguousReadBuffer(), len, TCP_WRITE_FLAG_COPY);

            if (ERR_OK != err)
            {
                if (ERR_MEM == err)
                {
                    // TCP send buffer is full.

                    break;
                }

                // TODO: kill client
                log("fd2socks writeTcpData: tcp_write failed %d", err);
                return false;
            }

            mSocksReceiveBuffer.commitRead(len);
            if (len > 0)
            {
                needTcpOutput = true;
            }
        }

        if (needTcpOutput)
        {
            err_t err = tcp_output(mLwipState);
            if (ERR_OK != err)
            {
                // TODO: kill client
                log("fd2socks writeTcpData: tcp_output failed %d", err);
                return false;
            }
        }

        return true;
    }

    bool bufferTcpDataForSocksWrite(struct pbuf* p)
    {
        // Write data received from the TCP stack through to the SOCKS relay.

        if (mSocketSendBuffer.getRemainingCapacity() < p->total_len)
        {
            // Too much data.
            return false;
        }

        bool writeAlreadyPending = mSocketSendBuffer.hasData();

        uint8_t* data = mSocketSendBuffer.allocateData(p->total_len);
        **** what if alloc fails?

        if (p->tot_len != pbuf_copy_partial(p, data, p->tot_len, 0))
        {
            log("fd2socks bufferTcpDataForSocksWrite: pbuf_copy_partial failed %d");
            mSocketSendBuffer.trimDataFromEnd(p->tot_len);
            return false;
        }

        if (!writeAlreadyPending)
        {
            // The main epoll loop may complete this write.
            // And more data may get appended in the meantime.
            writeData();
        }

        return true;
    }

    bool writeSocksData()
    {
        while (mSocketSendBuffer.hasData())
        {
            ssize_t writeCount;

            if (-1 == (writeCount = write(
                                        mSocksSocket,
                                        mSocketSendBuffer.getData(),
                                        mSocketSendBuffer.getDataSize()))
            {
                if (errno == EAGAIN || errno == EWOULDBLOCK)
                {
                    break;
                }

                log("fd2socks writeSocksData: write failed %d", errno);
                return false;
            }

            // We wait and acknowlege receiving the TCP data after
            // it's sent on to SOCKS; this keeps the TCP window in
            // sync with the SOCKS server data rate.

            tcp_recved(mLwipState, writeCount);

            mSocketSendBuffer.trimData(writeCount);
        }

        return true;
    }
};


class Fd2Socks
{
protected:

    int mSignalStopFlag;
    struct sockaddr mSocksAddress;
    struct netif mNetif;
    struct tcp_pcb* mLocalListener;
    int mEpollFacility;
    struct epoll_event* mEvents;
    int mIpPacketFileDescriptor;
    struct epoll_event mIpPacketEpollEvent;
    struct Buffer mIpPacketSendBuffer;
    std::vector<TcpClient*> mClients;
    (void*)(const char*) mLogger;

public:
    Fd2Socks()
    {
        init();
    }

    virtual ~Fd2Socks()
    {
        reset();
    }

    void init()
    {
        lwip_init();

        mSignalStopFlag = 0;
        memset(&mSocksAddress, 0, sizeof(mSocksAddress);
        memset(&mNetif, 0, sizeof(mNetif));
        mLocalListener = 0;
        mEpollFacility = 0;
        eMvents = 0;
        mIpPacketFileDescriptor = 0;
        memset(&mIpPacketEpollEvent, 0, sizeof(mIpPacketEpollEvent);
        mipPacketSendBuffer.init(IP_PACKET_MTU);
        mLogger = 0;        
    }

    void reset()
    {
        removeClients();

        // TODO: close ip packet fd (http://developer.android.com/reference/android/net/VpnService.Builder.html#establish%28%29)
        //       netif_remove?
        //       free ipPacketSendBuffer, close epoll facility

        init();
    }

    void log(const char* message)
    {
        if (mLogger)
        {
            mLogger(message);
        }
    }

    void signalStop()
    {
        mSignalStopFlag = 1;
    }

    bool run(
        const char* vpnIpAddress,
        const char* vpnNetMask,
        int vpnIpPacketFileDescriptor,
        const char* socksServerIpAddressStr,
        int socksServerPort)
    {
        mIpPacketFileDescriptor = vpnIpPacketFileDescriptor;

        memset(&mSocksAddress, 0, sizeof(mSocksAddress));
        if (1 != inet_pton(AF_INET, socksServerIpAddressStr, &mSocksAddress.sin_addr))
        {
            log("fd2socks run: inet_pton failed");
            return false;
        }
        mSocksAddress.sin_family = AF_INET;
        mSocksAddress.sin_port = htons(socksServerPort);

        // VPN traffic, sent and received via the provided
        // file descriptor, will flow through a virtual
        // device interface (netif).

        struct in_addr virtualDeviceAddress;
        struct in_addr netMask;
        struct in_addr gatewayAddress;

        if (1 != inet_pton(AF_INET, vpnIpAddress, &virtualDeviceAddress) ||
            1 != inet_pton(AF_INET, vpnNetMask, &netMask))
        {
            log("fd2socks run: inet_pton failed");
            return false;
        }

        ip_addr_set_any(&gatewayAddress);

        if (!netif_add(
                &mNetif,
                &(virtualDeviceAddress.s_addr),
                &(netmask.s_addr),
                &(gateway.s_addr),
                NULL,
                initNetifCallback,
                ip_input))
        {
            log("fd2socks run: netif_add failed");
            return false;
        }
        
        netif_set_up(&mNetif);
        netif_set_default(&mNetif);
        netif_set_pretend_tcp(&mNetif, 1);

        // To proxy outbound TCP, use the lwip "pretend" flag,
        // which redirects the traffic destination to the virtual
        // interface. We run a local listener which accepts TCP
        // connections and we relay the traffic to the intended
        // destination via the SOCKS proxy.

        if (!(mLocalListener = tcp_new()))
        {
            log("fd2socks run: tcp_new failed");
            return false;
        }

        tcp_arg(mLocalListener, this);
        
        if (ERR_OK != tcp_bind_to_netif(mLocalListener, "xx0"))
        {
            log("fd2socks run: tcp_bind_to_netif failed");
            return false;
        }

        if (!(mLocalListener = tcp_listen(mLocalListener)))
        {
            log("fd2socks run: tcp_new failed");
            return false;
        }
        
        tcp_accept(mLocalListener, acceptCallback);

        // Initialize epoll facility and virtual device polling. Additional
        // descriptors (socks sockets) are added in the TCP accept handler.

        if (-1 == (mEpollFacility = epoll_create1(0))
        {
            log("fd2socks run: epoll_create1 failed %d", errno);
            return false;
        }

        if (NULL == (mEvents = malloc(MAX_EPOLL_EVENTS*sizeof(struct epoll_event))))
        {
            log("fd2socks run: malloc failed");
            return false;
        }

        if (-1 == (fcntl(mIpPacketFileDescriptor, F_SETFL, O_NONBLOCK))
        {
            log("fd2socks run: fcntl failed %d", errno);
            return false;
        }

        mIpPacketEpollEvent.events = EPOLLIN | EPOLLOUT | EPOLLERR | EPOLLHUP;
        mIpPacketEpollEvent.data.ptr = NULL;

        if (-1 == epoll_ctl(mEpollFacility, EPOLL_CTL_ADD, mIpPacketFileDescriptor, &mIpPacketEpollEvent)
        {
            log("fd2socks run: epoll_ctl failed %d", errno);
            return false;
        }

        if (!mIpPacketSendBuffer.init(IP_PACKET_MTU))
        {
            log("fd2socks run: Buffer init failed");
            return false;
        }

        // Main event loop. We move packets to and from the VPN IP packet stream
        // and to and from the SOCKS connections. We also need to periodically
        // invoke an lwip function and check for a stop signal.

        while (!mSignalStopFlag)
        {
            int eventCount = 0;

            if (-1 == (eventCount = epoll_wait(mEpollFacility, mEvents, MAX_EPOLL_EVENTS, TCP_TMR_INTERVAL))
            {
                log("fd2socks run: epoll_wait failed %d", errno);
                return false;
            }

            // Call lwip periodic work every TCP_TMR_INTERVAL milliseconds.
            // TODO: don't call simply when epoll_wait returns; use time-elapsed logic
            tcp_tmr();

            for (int i = 0; i < eventCount; i++)
            {
                int fd = mEvents[i].data.fd;

                if (fd == mIpPacketFileDescriptor)
                {
                    // Note that once the VPN file descriptor is closed, everything
                    // is immediately shutdown. We don't wait to flush bytes sent
                    // to the SOCKS proxy.
                    // TODO: flush these bytes?

                    if ((mEvents[i].events & EPOLLERR) || (mEvents[i].events & EPOLLHUP))
                    {
                        mSignalStopFlag = 1;
                        break;
                    }
                    else if (mEvents[i].events & EPOLLIN)
                    {
                        if (!readIpPacket())
                        {
                            mSignalStopFlag = 1;
                            break;
                        }
                    }
                    else if (mEvents[i].events & EPOLLOUT)
                    {
                        if (!writeIpPacket())
                        {
                            mSignalStopFlag = 1;
                            break;                        
                        }
                    }
                }
                else
                {
                    TcpClient* tcpClient = (TcpClient*)mEvents[i].data.ptr;

                    if ((mEvents[i].events & EPOLLERR) || (mEvents[i].events & EPOLLHUP))
                    {
                        // flush and close: tcpClient
                    }
                    else if (mEvents[i].events & EPOLLIN)
                    {
                        if (!tcpClient->readSocksData())
                        {
                            removeClient(tcpClient);
                            tcpClient = 0;
                        }
                    }
                    else if (mEvents[i].events & EPOLLOUT)
                    {
                        if (!tcpClient->writeSocksData())
                        {
                            removeClient(tcpClient);
                            tcpClient = 0;
                        }
                    }
                }
            }
        }

        return true;
    }

    void addClient(TcpClient* client)
    {
        mClients.push_back(client);
    }

    void removeClient(TcpClient* client)
    {
        std::vector<TcpClient*>::iterator iter = find(mClients.begin(), mClients.end(), client);
        if (iter != mClients.end())
        {
            mClients.erase(iter);
        }
        delete client;
    }

    void removeClients()
    {
        for (std::vector<TcpClient*>::iterator iter = mClients.begin(); iter != mClients.end(); ++iter)
        {
            delete *iter;
        }
        mClients.clear();
    }

    bool readIpPacket()
    {
        // We're taking this literally and not buffering for packets split across reads:
        // http://developer.android.com/reference/android/net/VpnService.Builder.html#establish%28%29
        // "Each read retrieves an outgoing packet which was routed to the interface."

        uint8_t packet[IP_PACKET_MTU];
        ssize_t readCount;

        while (1)
        {
            if (-1 == (readCount = read(mIpPacketFileDescriptor, packet, sizeof(packet)))
            {
                if (EGAIN == errno)
                {
                    return true;
                }

                log("fd2socks readIpPacket: read failed %d", errno);
                return false;
            }
            else if (readCount == 0)
            {
                // On IP packet EOF, trigger a stop.

                return false;
            }
            else if (readCount > 0)
            {
                // Process incoming IP packet.

                if (isDNS(packet))
                {
                    handleDNS(packet);
                }
                else if (isTCP())
                {
                    // Pass the packet into the lwip stack.
                    // Keep running on errors: peer TCP stack will retry.

                    struct pbuf *p = pbuf_alloc(PBUF_RAW, readCount, PBUF_POOL);
                    if (!p)
                    {
                        log("fd2socks readIpPacket: pbuf_alloc failed");
                        return false;
                    }
        
                    if (ERR_OK != pbuf_take(p, packet, readCount))
                    {
                        log("fd2socks readIpPacket: pbuf_take failed");
                        pbuf_free(p);
                        return false;                    
                    }
        
                    if (ERR_OK != netif.input(p, &netif))
                    {
                        log("fd2socks readIpPacket: netif.input failed");
                        pbuf_free(p);
                        return false;                    
                    }
                }
            }
        }

        return true;
    }

    void bufferIpPacketForWrite(struct pbuf* p)
    {
        // Write IP packet from virtual interface to VPN file descriptor.

        // We're taking this literally and not buffering for multiple packets within a write:
        // http://developer.android.com/reference/android/net/VpnService.Builder.html#establish%28%29
        // "Each write injects an incoming packet just like it was received from the interface."
        // We do use a write buffer, but it only stores one packet. If it's busy, we drop the packet.

        if (mIpPacketSendBuffer.hasData())
        {
            // Buffer is busy: drop the packet.
            return;
        }

        if (mIpPacketSendBuffer.getCapacity() < p->total_len)
        {
            // Too much data: drop the packet.
            return;
        }

        uint8_t* buffer = mIpPacketSendBuffer.allocateSpace(p->total_len);

        if (p->tot_len != pbuf_copy_partial(p, buffer, p->tot_len, 0))
        {
            log("fd2socks bufferIpPacketForWrite: pbuf_copy_partial failed %d");
            ipPacketSendBuffer.clear();
            return;
        }

        // The main epoll loop may complete this write.
        // If the write fails, we drop the packet.
        writeIpPacket();
    }

    bool writeIpPacket()
    {
        while (mIpPacketSendBuffer.hasData())
        {
            ssize_t writeCount;

            if (-1 == (writeCount = write(
                                        mIpPacketFileDescriptor,
                                        mIpPacketSendBuffer.getData(),
                                        mIpPacketSendBuffer.getDataSize()))
            {
                if (errno == EAGAIN || errno == EWOULDBLOCK)
                {
                    break;
                }

                log("fd2socks writeIpPacket: write failed %d", errno);
                return false;
            }
            mIpPacketSendBuffer.trimData(writeCount);
        }

        return true;
    }
};

static extern "C" err_t initNetifCallback(struct netif* netif)
{
    netif->output = netifOutputCallback;
    return ERR_OK;
}

static extern "C" err_t netifOutputCallback(struct netif* netif, struct pbuf* p, ip_addr_t* ipaddr)
{
    // Handle data sent through the TCP stack, which flows through netif.

    // TODO: is there a user "arg" so we don't need to use this global?

    if (0 != gFd2Socks)
    {
        gFd2Socks->bufferIpPacketForWrite(p);
    }

    return ERR_OK;
}

static extern "C" err_t acceptCallback(void* arg, struct tcp_pcb* newPcb, err_t err)
{
    Fd2Socks* fd2Socks = (Fd2Socks*)arg;

    tcp_accepted(localListener);

    TcpClient* tcpClient = 0;

    // TODO: lwip frees newPcb on error?

    if (0 == (tcpClient = new TcpClient(fd2Socks)))
    {
        log("fd2socks acceptCallback: new failed");
        return -1;
    }

    if (!tcpClient.init(newPcb))
    {
        log("fd2socks acceptCallback: init failed");
        delete tcpClient;
        tcpClient = 0;
        return -1;
    }

    fd2Socks.addClient(tcpClient);

    return ERR_OK;
}

static extern "C" void tcpClientErrorCallback(void* arg, err_t err)
{
    TcpClient* tcpClient = (TcpClient*)arg;
    
    log("fd2socks tcpClientErrorCallback: error %d", (int) err);

    removeClient(tcpClient);
    tcpClient = 0;
}

static extern "C" err_t tcpClientReceiveCallback(void* arg, struct tcp_pcb* pcb, struct pbuf* p, err_t err)
{
    // Handle data received through the TCP stack, which is to be relayed through SOCKS.

    if (err != ERR_OK)
    {
        return err;
    }

    TcpClient* tcpClient = (TcpClient*)arg;

    if (NULL == p)
    {
        // Peer has closed connection.
        // TODO: flush data to send to SOCKS?
        //       - peer is Android process
        //       - lwip TCP stack has indicated to peer that destination received bytes

        removeClient(tcpClient);
        tcpClient = 0;
        return ERR_OK;
    }
    
    if (!tcpClient->bufferTcpDataForSocksWrite(p))
    {
        log("fd2socks tcpClientReceiveCallback: bufferSocksDataForWrite failed");
        return ERR_MEM;
    }

    pbuf_free(p);
    
    return ERR_OK;
}

static err_t tcpClientSentCallback(void* arg, struct tcp_pcb* tpcb, u16_t len)
{
    TcpClient* tcpClient = (TcpClient*)arg;

    // We can send more SOCKS data back through the TCP stack.

    // TODO: use "len" to keep this client open while flushing
    //       data received from SOCKS after SOCKS socket closed.

    if (!tcpClient->writeTcpData())
    {
        removeClient(tcpClient);
        tcpClient = 0;
    }
}
