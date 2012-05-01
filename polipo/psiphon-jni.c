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

#include "jni.h"

int psiphonMainPreInit(int proxyPortParam, int localParentProxyPortParam);
int psiphonMainInit();
int psiphonMainEventLoop(int (*checkSignalStop)());

static int g_preInit = 0;

JNIEXPORT jint JNICALL Java_com_psiphon3_Polipo_initPolipo(
    JNIEnv* env,
    jobject obj,
    int proxyPort,
    int localParentProxyPort)
{
    if (!g_preInit)
    {
        // NOTE: preInit can only be run once due to Polipo code.
        // This means the config params are only set one time.

        g_preInit = 1;
        int errcode = psiphonMainPreInit(proxyPort, localParentProxyPort);
        if (errcode) return errcode;
    }
    
    return psiphonMainInit();
}

static JNIEnv* g_env = 0;
static jobject g_obj = 0;
jfieldID g_signalStopFid = 0;

int checkSignalStop()
{
    return (*g_env)->GetBooleanField(g_env, g_obj, g_signalStopFid);
}

JNIEXPORT jint JNICALL Java_com_psiphon3_Polipo_runPolipo(
    JNIEnv* env,
    jobject obj)
{
    // NOTE: this method of accessing fields will only work with one instance
    // of the Polipo object; however, most of the Polipo C code uses global
    // variables, so we can only have one instance in any case.
  
    g_env = env;
    g_obj = obj;
    jclass cls = (*g_env)->GetObjectClass(g_env, g_obj);
    g_signalStopFid = (*g_env)->GetFieldID(g_env, cls, "m_signalStop", "Z");
    if (!g_signalStopFid) return -1;

    return psiphonMainEventLoop(&checkSignalStop);
}
