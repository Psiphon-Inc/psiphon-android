/*
 * Copyright (c) 2014, Psiphon Inc.
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

int psiphonMain(int bindAll, int proxyPortParam, int localParentProxyPortParam);

JNIEXPORT jint JNICALL Java_com_psiphon3_psiphonlibrary_Polipo_runPolipo(
    JNIEnv* env,
    jobject obj,
    int bindAll,
    int proxyPort,
    int localParentProxyPort)
{
    return psiphonMain(bindAll, proxyPort, localParentProxyPort);
}
