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

#include <limits.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <linux/netfilter_ipv4.h>
#include <stdio.h>
#include "jni.h"

// from: http://stackoverflow.com/questions/6899868/how-to-pass-java-net-socket-to-a-c-dll-function-waiting-for-boostsocket-assig

static int getFd(JNIEnv *env, jobject sock)
{
    JNIEnv e = *env;
    jclass clazz;
    jfieldID fid;
    jobject impl;
    jobject fdesc;

    /* get the SocketImpl from the Socket */
    if (!(clazz = e->GetObjectClass(env,sock)) ||
        !(fid = e->GetFieldID(env,clazz,"impl","Ljava/net/SocketImpl;")) ||
        !(impl = e->GetObjectField(env,sock,fid))) return -1;

    /* get the FileDescriptor from the SocketImpl */
    if (!(clazz = e->GetObjectClass(env,impl)) ||
        !(fid = e->GetFieldID(env,clazz,"fd","Ljava/io/FileDescriptor;")) ||
        !(fdesc = e->GetObjectField(env,impl,fid))) return -1;

    /* get the fd from the FileDescriptor */
    if (!(clazz = e->GetObjectClass(env,fdesc)) ||
        !(fid = e->GetFieldID(env,clazz,"descriptor","I"))) return -1;

    /* return the descriptor */
    return e->GetIntField(env,fdesc,fid);
}

JNIEXPORT jstring JNICALL Java_ch_ethz_ssh2_channel_TransparentProxyAcceptThread_getOriginalDest(
    JNIEnv* env, jobject obj, jobject sock)
{
    const char* ret = "";
    int fd = 0;
    struct sockaddr_storage orig_dst;
    socklen_t orig_dst_len = sizeof(orig_dst);
    struct sockaddr* saddr = 0;
    const int buffer_size = INET_ADDRSTRLEN + 1 + sizeof(unsigned int)*2 + 1; // "<IP>:<port>\0"
    char buffer[buffer_size];

    if (-1 != (fd = getFd(env, sock)) &&
        0 == getsockopt(fd, SOL_IP, SO_ORIGINAL_DST, (struct sockaddr*)&orig_dst, &orig_dst_len))
    {
        saddr = (struct sockaddr*)&orig_dst;

        // NOTE: only supports INET IPv4 addresses
        if (saddr->sa_family == AF_INET)
        {
            struct sockaddr_in *sin = (struct sockaddr_in*)saddr;
            snprintf(buffer, buffer_size, "%s:%u", inet_ntoa(sin->sin_addr), ntohs(sin->sin_port));
            ret = buffer;
        }
    }

    return (*env)->NewStringUTF(env, ret);
}
