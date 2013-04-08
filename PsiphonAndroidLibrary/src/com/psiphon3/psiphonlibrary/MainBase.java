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

package com.psiphon3.psiphonlibrary;

import com.psiphon3.psiphonlibrary.Utils.MyLog;

import android.content.Context;
import android.os.Bundle;

public abstract class MainBase
{
    public static String getResourceString(Context context, int stringResID, Object[] formatArgs)
    {
        if (formatArgs == null || formatArgs.length == 0)
        {
            return context.getString(stringResID);
        }
        
        return context.getString(stringResID, formatArgs);
    }
    
    public static abstract class Activity 
        extends android.app.Activity
        implements MyLog.ILogger
    {
        @Override
        protected void onCreate(Bundle savedInstanceState)
        {
            super.onCreate(savedInstanceState);
            
            MyLog.setLogger(this);
        }
        
        @Override
        protected void onDestroy()
        {
            super.onDestroy();
    
            MyLog.unsetLogger();
        }
        
        /*
         * Partial MyLog.ILogger implementation
         */
        
        /**
         * @see com.psiphon3.psiphonlibrary.Utils.MyLog.ILogger#getResourceString(int, java.lang.Object[])
         */
        @Override
        public String getResourceString(int stringResID, Object[] formatArgs)
        {
            return MainBase.getResourceString(this, stringResID, formatArgs);
        }
    }
    
    public static abstract class ListActivity
    extends android.app.ListActivity
    implements MyLog.ILogger
    {
        @Override
        protected void onCreate(Bundle savedInstanceState)
        {
            super.onCreate(savedInstanceState);
            
            MyLog.setLogger(this);
        }
        
        @Override
        protected void onDestroy()
        {
            super.onDestroy();
    
            MyLog.unsetLogger();
        }
        
        /*
         * Partial MyLog.ILogger implementation
         */
        
        /**
         * @see com.psiphon3.psiphonlibrary.Utils.MyLog.ILogger#getResourceString(int, java.lang.Object[])
         */
        @Override
        public String getResourceString(int stringResID, Object[] formatArgs)
        {
            return MainBase.getResourceString(this, stringResID, formatArgs);
        }
    }
}
