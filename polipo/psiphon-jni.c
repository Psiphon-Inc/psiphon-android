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

int psiphonMain(
        int proxyPort,
        int localParentProxyPort,
        void (*setSignalPolipoListening)(),
        int (*checkSignalStop)());

static JNIEnv* g_env = 0;
static jobject g_obj = 0;
jfieldID g_polipoListeningFid = 0;
jfieldID g_signalStopFid = 0;

static void initFieldAccessors(JNIEnv* env, jobject obj)
{
    // NOTE: this method of accessing fields will only work with one instance
    // of the Polipo object; however, most of the Polipo C code uses global
    // variables, so we can only have one instance in any case.
  
    g_env = env;
    g_obj = obj;
    jclass cls = (*g_env)->GetObjectClass(g_env, g_obj);
    g_polipoListeningFid = (*g_env)->GetFieldID(g_env, cls, "m_polipoListening", "Z");
    if (!g_polipoListeningFid) return;
    g_signalStopFid = (*g_env)->GetFieldID(g_env, cls, "m_signalStop", "Z");
    if (!g_signalStopFid) return;
}

void setSignalPolipoListening()
{
    (*g_env)->SetBooleanField(g_env, g_obj, g_polipoListeningFid, 1);
}

int checkSignalStop()
{
    return (*g_env)->GetBooleanField(g_env, g_obj, g_signalStopFid);
}

JNIEXPORT jint JNICALL Java_com_psiphon3_Polipo_runPolipo(
    JNIEnv* env,
    jobject obj,
    int proxyPort,
    int localParentProxyPort)
{
    initFieldAccessors(env, obj);

    return psiphonMain(
                proxyPort,
                localParentProxyPort,
                &setSignalPolipoListening,
                &checkSignalStop);
}
