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

// With code from:
// - http://code.google.com/p/badvpn/source/browse/trunk/tun2socks/tun2socks.c
// - also using the tun2socks lwip customizations ("pretend" flag)

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

vpn2socks
=========

This module relays IP traffic from the Android VpnService file descriptor
through local Psiphon proxies.

TCP connections are relayed through the Psiphon SOCKS proxy. DNS UDP traffic
is relayed through the Psiphon DNS server. Other UDP and IP traffic is
dropped.

*/

/*

In-progress TODOs
=================

- don't call simply when epoll_wait returns; use time-elapsed logic
- DNS
  -- intercept and parse UDP packets
  -- resend packets destinated to port 53 to local (proxied) DNS server
  -- listen for datagrams from DNS server and feed back into IP fd

- verify that local socket connections (stream and datagram) don't need protect() from VPN
- need real write buffer for VPN fd?
- decide if need to flush or drop relayed TCP data when either side closes connection

*/


#include <vector>
#include <algorithm>
#include <lwip/init.h>
#include <lwip/tcp_impl.h>
#include <lwip/netif.h>
#include <lwip/tcp.h>
#include "jni.h"
#include "StreamDataBuffer.h"


static Vpn2Socks* gVpn2Socks = 0;


JNIEXPORT jint JNICALL Java_com_psiphon3_psiphonlibrary_vpn2socks_signalStop(
    JNIEnv* env)
{
    if (0 != gVpn2Socks)
    {
        gVpn2Socks->signalStop();
    }
    return 0;
}


JNIEXPORT jint JNICALL Java_com_psiphon3_psiphonlibrary_vpn2socks_run(
    JNIEnv* env,
    jint vpnIpPacketFileDescriptor,
    jstring vpnIpAddress,
    jstring vpnNetMask,
    jstring dnsServerIpAddress,
    jint dnsServerPort,
    jstring socksServerIpAddress,
    jint socksServerPort)
{
    Vpn2Socks vpn2Socks;
    gVpn2Socks = &vpn2Socks;

    const char* vpnIpAddressStr = env->GetStringUTFChars(vpnIpAddress, 0);
    const char* vpnNetMaskStr = env->GetStringUTFChars(vpnNetMask, 0);
    const char* dnsServerIpAddressStr = env->GetStringUTFChars(dnsServerIpAddress, 0);
    const char* socksServerIpAddressStr = env->GetStringUTFChars(socksServerIpAddress, 0);

    bool result = vpn2Socks.run(
            vpnIpAddressStr,
            vpnNetMaskStr,
            vpnIpPacketFileDescriptor,
            dnsServerIpAddressStr,
            dnsServerPort,
            socksServerIpAddressStr,
            socksServerPort);

    vpn2Socks.reset();
    gVpn2Socks = 0;

    env->ReleaseStringUTFChars(vpnIpAddressStr, vpnIpAddress, 0);
    env->ReleaseStringUTFChars(vpnNetMaskStr, vpnNetMask, 0);
    env->ReleaseStringUTFChars(socksServerIpAddressStr, socksServerIpAddress, 0);
    env->ReleaseStringUTFChars(dnsServerIpAddressStr, dnsServerIpAddress, 0);

    return result ? 1 : 0;
}


static void log(const char* message)
{
    if (0 != gVpn2Socks)
    {
        gVpn2Socks->log(message);
    }
}


const static size_t IP_PACKET_MTU = 65536;
const static size_t SOCKS_BUFFER_SIZE = 8192;
const static int MAX_EPOLL_EVENTS = 100;


static extern "C" err_t initNetifCallback(struct netif* netif);
static extern "C" err_t netifOutputCallback(struct netif* netif, struct pbuf* p, ip_addr_t* ipaddr);
static extern "C" err_t acceptCallback(void* arg, struct tcp_pcb* newPcb, err_t err);;
static extern "C" err_t tcpClientReceiveCallback(void* arg, struct tcp_pcb* pcb, struct pbuf* p, err_t err);
static extern "C" err_t tcpClientSentCallback(void* arg, struct tcp_pcb* tpcb, u16_t len);
static extern "C" void tcpClientErrorCallback(void* arg, err_t err);


class TcpClientProxy
{
protected:

    Vpn2Socks* mVpn2Socks;
    struct tcp_pcb* mLwipState;
    socket mSocksSocket;
    struct epoll_event mSocksEpollEvent;
    bool receievedSocksServerResponse;
    StreamDataBuffer mSocksSendBuffer;
    StreamDataBuffer mSocksReceiveBuffer;

public:

    TcpClientProxy(Vpn2Socks* vpn2Socks)
    {
        mVpn2Socks = vpn2Socks;
        mLwipState = 0;
        mSocksSocket = NULL;
        memset(&mSocksEpollEvent, 0, sizeof(mSocksEpollEvent));
        receievedSocksServerResponse = false;
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
            epoll_ctr(mVpn2Socks->mEpollFacility, EPOLL_CTL_DEL, mSocksSocket, 0);
        }

        if (0 != mSocksSocket)
        {
            close(mSocksSocket);
        }
    }

    bool init(struct tcp_pcb* newPcb)
    {
        if (!mSocksSendBuffer.init(SOCKS_BUFFER_SIZE) ||
            !mSocksReceiveBuffer.init(SOCKS_BUFFER_SIZE))
        {
            log("TcpClientProxy init: Buffer init failed");
            return false;
        }

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
            log("vpn2socks TcpClientProxy init: getprotobyname failed");
            return false;
        }

        if (-1 == (mSocksSocket = socket(AF_INET, SOCK_STREAM, protocol->p_proto))
        {
            log("vpn2socks TcpClientProxy init: socket failed %d", errno);
            return false;
        }

        if (-1 == (fcntl(mSocksSocket, F_SETFL, O_NONBLOCK))
        {
            log("vpn2socks TcpClientProxy init: fcntl failed %d", errno);
            return false;
        }

        if (-1 == (connect(mSocksSocket, &(mVpn2Socks->mSocksAddress), sizeof(mVpn2Socks->mSocksAddress)))
        {
            if (errno != EINPROGRESS)
            {
                log("vpn2socks TcpClientProxy init: connect failed %d", errno);
                return false;
            }
        }

        // Add SOCKS client to epoll facility.

        mSocksEpollEvent.events = EPOLLIN | EPOLLPRI | EPOLLOUT | EPOLLERR | EPOLLHUP;
        mSocksEpollEvent.data.ptr = tcpClient;

        if (-1 == epoll_ctl(mVpn2Socks->epollFacility, EPOLL_CTL_ADD, mSocksSocket, mSocksEpollEvent))
        {
            log("vpn2socks TcpClientProxy init: epoll_ctl failed %d", errno);
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

        if (mSocketSendBuffer.getWriteCapacity() < sizeof(socksConnectRequest) ||
            !memcpy(mSocketSendBuffer.getWriteData(), socksConnectRequest, sizeof(socksConnectRequest)) ||
            !mSocketSendBuffer.commitWrite(sizeof(socksConnectRequest)))
        {
            log("vpn2socks TcpClientProxy init: buffer SOCKS request header failed %d", errno);
            return false;
        }

        return true;
    }

    bool readSocksData()
    {
        // Read data from the SOCKS server.

        while (mSocksReceiveBuffer.getWriteCapacity() > 0)
        {
            ssize_t readCount;
            if (-1 == (readCount = read(
                                    mSocksSocket,
                                    mSocksReceiveBuffer.getWriteData(),
                                    mSocksReceiveBuffer.getWriteCapacity()))
            {
                if (EGAIN == errno)
                {
                    break;
                }

                log("vpn2socks readSocksData: read failed %d", errno);
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

            if (mSocksReceiveBuffer.getReadAvailable() < expectedResponseSize)
            {
                // Wait for more bytes.
                return true;
            }

            // Check that the status byte contains the "granted" value.
            uint8_t status = mSocksReceiveBuffer.getReadData()[1];
            if (0x5a != status)
            {
                log("vpn2socks readSocksData: unexpected SOCKS status %d", (int)status);
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
        while (mSocksReceiveBuffer.getReadAvailable() > 0)
        {
            size_t capacity = tcp_sndbuf(mLwipState);
            size_t dataSize = mSocksReceiveBuffer.getReadAvailable();
            size_t len = (capacity < dataSize ? capacity : dataSize);

            err_t err = tcp_write(mLwipState, mSocksReceiveBuffer.getReadData(), len, TCP_WRITE_FLAG_COPY);

            if (ERR_OK != err)
            {
                if (ERR_MEM == err)
                {
                    // TCP send buffer is full.

                    break;
                }

                // TODO: kill client
                log("vpn2socks writeTcpData: tcp_write failed %d", err);
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
                log("vpn2socks writeTcpData: tcp_output failed %d", err);
                return false;
            }
        }

        return true;
    }

    bool bufferTcpDataForSocksWrite(struct pbuf* p)
    {
        // Write data received from the TCP stack through to the SOCKS relay.

        bool writeAlreadyPending = (mSocketSendBuffer.getReadAvailable() > 0);

        if (mSocketSendBuffer.getWriteCapacity() < p->total_len)
        {
            // Too much data.
            return false;
        }

        if (p->tot_len != pbuf_copy_partial(p, mSocketSendBuffer.getWriteData(), p->tot_len, 0))
        {
            log("vpn2socks bufferTcpDataForSocksWrite: pbuf_copy_partial failed %d");
            return false;
        }

        mSocketSendBuffer.commitWrite(p->tot_len);

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
        while (mSocketSendBuffer.getReadAvailable() > 0)
        {
            ssize_t writeCount;

            if (-1 == (writeCount = write(
                                        mSocksSocket,
                                        mSocketSendBuffer.getReadData(),
                                        mSocketSendBuffer.getReadAvailable()))
            {
                if (errno == EAGAIN || errno == EWOULDBLOCK)
                {
                    break;
                }

                log("vpn2socks writeSocksData: write failed %d", errno);
                return false;
            }

            // We wait and acknowlege receiving the TCP data after
            // it's sent on to SOCKS; this keeps the TCP window in
            // sync with the SOCKS server data rate.

            tcp_recved(mLwipState, writeCount);

            mSocketSendBuffer.commitRead(writeCount);
        }

        return true;
    }
};


class Vpn2Socks
{
protected:

    int mSignalStopFlag;
    struct sockaddr mDnsAddress;
    struct sockaddr mSocksAddress;
    struct netif mNetif;
    bool mNetifAdded;
    struct tcp_pcb* mLocalListener;
    int mEpollFacility;
    struct epoll_event* mEvents;
    socket mDnsSocket;
    struct epoll_event mDnsEpollEvent;
    int mIpPacketFileDescriptor;
    struct epoll_event mIpPacketEpollEvent;
    StreamDataBuffer mIpPacketBuffer;
    std::vector<TcpClient*> mClients;
    (void*)(const char*) mLogger;

public:
    Vpn2Socks()
    {
        init();
    }

    virtual ~Vpn2Socks()
    {
        reset();
    }

    void init()
    {
        lwip_init();

        mSignalStopFlag = 0;
        memset(&mDnsAddress, 0, sizeof(mDnsAddress);
        memset(&mSocksAddress, 0, sizeof(mSocksAddress);
        memset(&mNetif, 0, sizeof(mNetif));
        mNetifAdded = false;
        mLocalListener = 0;
        mEpollFacility = 0;
        mEvents = 0;
        mDnsSocket = 0;
        memset(&mDnsEpollEvent, 0, sizeof(mDnsEpollEvent);
        mIpPacketFileDescriptor = 0;
        memset(&mIpPacketEpollEvent, 0, sizeof(mIpPacketEpollEvent);
        mLogger = 0;        
    }

    void reset()
    {
        removeClients();

        if (0 != mLocalListener)
        {
            tcp_accept(mLocalListener, NULL);
            tcp_close(mLocalListener);
        }

        if (mNetifAdded)
        {
            netif_remove(&mNetif);
        }

        if (0 != mEpollFacility)
        {
            close(mEpollFacility);
        }

        if (0 != mEvents)
        {
            delete[] mEvents;
        }

        if (0 != mDnsSocket)
        {
            close(mDnsSocket);
        }

        if (0 != mIpPacketFileDescriptor)
        {
            // We close the VPN file descriptor as per:
            // http://developer.android.com/reference/android/net/VpnService.Builder.html#establish%28%29
            close(mIpPacketFileDescriptor);
        }

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
        const char* dnsServerIpAddressStr,
        int dnsServerPort,
        const char* socksServerIpAddressStr,
        int socksServerPort)
    {
        if (!mIpPacketBuffer.init(IP_PACKET_MTU))
        {
            log("vpn2socks run: Buffer init failed");
            return false;
        }

        mIpPacketFileDescriptor = vpnIpPacketFileDescriptor;

        memset(&mDnsAddress, 0, sizeof(mDnsAddress));
        if (1 != inet_pton(AF_INET, dnsServerIpAddressStr, &mDnsAddress.sin_addr))
        {
            log("vpn2socks run: inet_pton failed");
            return false;
        }
        mDnsAddress.sin_family = AF_INET;
        mDnsAddress.sin_port = htons(dnsServerPort);

        memset(&mSocksAddress, 0, sizeof(mSocksAddress));
        if (1 != inet_pton(AF_INET, socksServerIpAddressStr, &mSocksAddress.sin_addr))
        {
            log("vpn2socks run: inet_pton failed");
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
            log("vpn2socks run: inet_pton failed");
            return false;
        }

        ip_addr_set_any(&gatewayAddress);

        if (!netif_add(
                &mNetif,
                &(virtualDeviceAddress.s_addr),
                &(netmask.s_addr),
                &(gateway.s_addr),
                this,
                initNetifCallback,
                ip_input))
        {
            log("vpn2socks run: netif_add failed");
            return false;
        }

        mNetifAdded = true;
        
        netif_set_up(&mNetif);
        netif_set_default(&mNetif);
        netif_set_pretend_tcp(&mNetif, 1);

        // To proxy outbound TCP, use the lwip/tun2socks "pretend" flag,
        // which redirects the traffic destination to the virtual
        // interface. We run a local listener which accepts TCP
        // connections and we relay the traffic to the intended
        // destination via the SOCKS proxy.

        if (!(mLocalListener = tcp_new()))
        {
            log("vpn2socks run: tcp_new failed");
            return false;
        }

        tcp_arg(mLocalListener, this);
        
        if (ERR_OK != tcp_bind_to_netif(mLocalListener, "xx0"))
        {
            log("vpn2socks run: tcp_bind_to_netif failed");
            return false;
        }

        if (!(mLocalListener = tcp_listen(mLocalListener)))
        {
            log("vpn2socks run: tcp_new failed");
            return false;
        }
        
        tcp_accept(mLocalListener, acceptCallback);

        // Initialize epoll facility and virtual device polling. Additional
        // descriptors (socks sockets) are added in the TCP accept handler.

        if (-1 == (mEpollFacility = epoll_create1(0))
        {
            log("vpn2socks run: epoll_create1 failed %d", errno);
            return false;
        }

        if (0 == (mEvents = new struct epoll_event[MAX_EPOLL_EVENTS]))
        {
            log("vpn2socks run: allocate events failed");
            return false;
        }

        if (-1 == (fcntl(mIpPacketFileDescriptor, F_SETFL, O_NONBLOCK))
        {
            log("vpn2socks run: fcntl failed %d", errno);
            return false;
        }

        mIpPacketEpollEvent.events = EPOLLIN | EPOLLERR | EPOLLHUP;
        mIpPacketEpollEvent.data.ptr = NULL;

        if (-1 == epoll_ctl(mEpollFacility, EPOLL_CTL_ADD, mIpPacketFileDescriptor, &mIpPacketEpollEvent)
        {
            log("vpn2socks run: epoll_ctl failed %d", errno);
            return false;
        }

        // DNS socket used to relay DNS UDP packets to DNS proxy.

        ...

        // Main event loop. We move packets to and from the VPN IP packet stream
        // and to and from the SOCKS connections. We also need to periodically
        // invoke an lwip function and check for a stop signal.

        while (!mSignalStopFlag)
        {
            int eventCount = 0;

            if (-1 == (eventCount = epoll_wait(mEpollFacility, mEvents, MAX_EPOLL_EVENTS, TCP_TMR_INTERVAL))
            {
                log("vpn2socks run: epoll_wait failed %d", errno);
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
                    if (mEvents[i].events & EPOLLERR)
                    {
                        log("vpn2socks run: epoll_wait mIpPacketFileDescriptor error");
                        mSignalStopFlag = 1;
                        break;
                    }
                    else if (mEvents[i].events & EPOLLHUP)
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
                }
                else if (fd == mDnsSocket)
                {
                    if (mEvents[i].events & EPOLLERR)
                    {
                        log("vpn2socks run: epoll_wait mDnsSocket error");
                        mSignalStopFlag = 1;
                        break;
                    }
                    else if (mEvents[i].events & EPOLLHUP)
                    {
                        mSignalStopFlag = 1;
                        break;
                    }
                    else if (mEvents[i].events & EPOLLIN)
                    {
                        if (!tcpClient->readSocksData())
                        {
                            mSignalStopFlag = 1;
                            break;
                        }
                    }
                    else if (mEvents[i].events & EPOLLOUT)
                    {
                        if (!tcpClient->writeSocksData())
                        {
                            mSignalStopFlag = 1;
                            break;
                        }
                    }
                }
                else
                {
                    TcpClient* tcpClient = (TcpClient*)mEvents[i].data.ptr;

                    if (mEvents[i].events & EPOLLERR)
                    {
                        log("vpn2socks run: epoll_wait tcpClient error");
                        removeClient(tcpClient);
                        tcpClient = 0;
                    }
                    else if (mEvents[i].events & EPOLLHUP)
                    {
                        removeClient(tcpClient);
                        tcpClient = 0;
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
        // Write IP packet from VPN file descriptor. Pass TCP packets into virtual interface.
        // DNS UDP packets are handled explicitly. Other packet types are silently dropped.

        // We're not buffering for packets split across reads:
        // http://developer.android.com/reference/android/net/VpnService.Builder.html#establish%28%29
        // "Each read retrieves an outgoing packet which was routed to the interface."
        //
        // This is also how tun devices work (one read is one packet), and the Android VpnService file
        // descriptor acts as a tun device.
        // http://www.freebsd.org/cgi/man.cgi?query=tun&sektion=4

        while (1)
        {
            mIpPacketBuffer.clear();
            uint8_t* packet = mIpPacketBuffer.getWriteData();
            ssize_t readCount;

            if (-1 == (readCount = read(
                                    mIpPacketFileDescriptor,
                                    packet,
                                    mIpPacketBuffer.getWriteCapacity()))
            {
                if (EGAIN == errno)
                {
                    return true;
                }

                log("vpn2socks readIpPacket: read failed %d", errno);
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
                        log("vpn2socks readIpPacket: pbuf_alloc failed");
                        return false;
                    }
        
                    if (ERR_OK != pbuf_take(p, packet, readCount))
                    {
                        log("vpn2socks readIpPacket: pbuf_take failed");
                        pbuf_free(p);
                        return false;                    
                    }
        
                    if (ERR_OK != netif.input(p, &netif))
                    {
                        log("vpn2socks readIpPacket: netif.input failed");
                        pbuf_free(p);
                        return false;                    
                    }
                }
            }
        }

        return true;
    }

    bool writeIpPacket(struct pbuf* p)
    {
        // Write IP packet from virtual interface to VPN file descriptor.

        // We're not buffering for multiple packets within a write. As with
        // tun-device-type reads, a write is exactly one packet (see references
        // in readIpPacket); and writes will not block.

        mIpPacketBuffer.clear();

        if (mIpPacketBuffer.getWriteCapacity() < p->total_len)
        {
            // Too much data: drop the packet.
            return false;
        }

        if (p->tot_len != pbuf_copy_partial(p, mIpPacketBuffer.getWriteData(), p->tot_len, 0))
        {
            log("vpn2socks bufferIpPacketForWrite: pbuf_copy_partial failed %d");
            return false;
        }

        mIpPacketBuffer.commitWrite(p->tot_len);

        ssize_t writeCount;
        if (-1 == (writeCount = write(
                                    mIpPacketFileDescriptor,
                                    mIpPacketBuffer.getReadData(),
                                    mIpPacketBuffer.getReadAvailable()))
        {
            // Note: EAGAIN/EWOULDBLOCK are not expected.

            log("vpn2socks writeIpPacket: write failed %d", errno);
            return false;
        }

        // Packet write should be all-or-nothing, as per the Android/tun spec.

        if (mIpPacketBuffer.getReadAvailable() != writeCount)
        {
            log("vpn2socks writeIpPacket: unexpected write count");
            return false;
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

    Vpn2Socks* vpn2Socks = (Vpn2Socks*)netif->state;

    vpn2Socks->writeIpPacket(p);

    return ERR_OK;
}

static extern "C" err_t acceptCallback(void* arg, struct tcp_pcb* newPcb, err_t err)
{
    Vpn2Socks* vpn2Socks = (Vpn2Socks*)arg;

    tcp_accepted(localListener);

    TcpClient* tcpClient = 0;

    // TODO: lwip frees newPcb on error?

    if (0 == (tcpClient = new TcpClient(vpn2Socks)))
    {
        log("vpn2socks acceptCallback: new failed");
        return -1;
    }

    if (!tcpClient.init(newPcb))
    {
        log("vpn2socks acceptCallback: init failed");
        delete tcpClient;
        tcpClient = 0;
        return -1;
    }

    vpn2Socks.addClient(tcpClient);

    return ERR_OK;
}

static extern "C" void tcpClientErrorCallback(void* arg, err_t err)
{
    TcpClient* tcpClient = (TcpClient*)arg;
    
    log("vpn2socks tcpClientErrorCallback: error %d", (int) err);

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
        return ERR_ABRT;
    }
    
    if (!tcpClient->bufferTcpDataForSocksWrite(p))
    {
        log("vpn2socks tcpClientReceiveCallback: bufferSocksDataForWrite failed");
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
        return ERR_ABRT;
    }

    return ERR_OK;
}
