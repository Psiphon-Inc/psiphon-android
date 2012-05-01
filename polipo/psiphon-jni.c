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

int main(int argc, char **argv);

JNIEXPORT void JNICALL Java_com_psiphon3_Polipo_runPolipo(JNIEnv* env, jobject obj)
{
    // Call Polipo main() with Psiphon command line arguments
    // TODO: pass in a "stop" object and have polipo gracefully shutdown when the stop flag is set
    char* args[] =
    {
        "proxyPort=8080",
        "diskCacheRoot=\"\"",
        "disableLocalInterface=true",
        "socksParentProxy=127.0.0.1:1080",
        "logLevel=1"
    };
    main(sizeof(args)/sizeof(char*), args);
}
