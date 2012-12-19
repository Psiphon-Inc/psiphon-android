
package ch.ethz.ssh2.util;

import java.io.UnsupportedEncodingException;

/**
 * 
 * @author Christian Plattner
 * @version 2.50, 03/15/10
 */
public class StringEncoder
{
	public static byte[] GetBytes(String data)
	{
		try
		{
			return data.getBytes("ISO8859_1");
		}
		catch (UnsupportedEncodingException e)
		{
			byte[] bytes = new byte[data.length()];

			for (int i = 0; i < data.length(); i++)
			{
				char c = data.charAt(i);
				if (c > 127)
					bytes[i] = 0x3F; // ?
				else
					bytes[i] = (byte) c;
			}

			return bytes;
		}
	}

	public static String GetString(byte[] data)
	{
		return GetString(data, 0, data.length);
	}
	
	public static String GetString(byte[] data, int off, int len)
	{
		try
		{
			return new String(data, off, len, "ISO8859_1");
		}
		catch (UnsupportedEncodingException e)
		{
			char[] chars = new char[len];

			for (int i = 0; i < len; i++)
			{
				char c = (char) data[off + i];
				if (c > 127)
					c = '?';
				chars[i] = c;
			}

			return new String(chars);
		}
	}
}
