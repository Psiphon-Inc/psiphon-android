package com.psiphon3.psiphonlibrary;

import com.psiphon3.psiphonlibrary.Utils.MyLog;

import android.app.Activity;
import android.os.Bundle;

public abstract class MainActivityBase 
    extends Activity
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
        if (formatArgs == null || formatArgs.length == 0)
        {
            return getString(stringResID);
        }
        
        return getString(stringResID, formatArgs);
    }
}
