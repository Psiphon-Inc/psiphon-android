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


class StreamDataBuffer
{
protected:
    uint8_t* data;
    size_t space;
    size_t len;

public:
    Buffer()
    {
        data = 0;
        space = 0;
        len = 0;
    }

    virtual ~Buffer()
    {
        delete[] data;
    }

    bool init(size_t capacity)
    {
        delete[] data;
        space = 0;
        len = 0;
        data = new uint8_t[capacity];
        if (!data)
        {
            return false;
        }
        space = capacity;
        len = 0;
        return true;
    }

    void clear()
    {
        len = 0;
    }

    size_t getReadAvailable()
    {
        return len;
    }

    uint8_t* getReadData()
    {
        return data;
    }

    bool commitRead(size_t readCount)
    {
        // This function shifts unread data to the front of the
        // buffer, keeping every other operation simple. We could
        // avoid the memmove operation with a circular buffer,
        // but that results in non-contiguous buffers which don't
        // play well with system APIs (e.g., read()/write()).
        // In the event that this becomes a performance bottleneck,
        // some other options to consider:
        // - use a circular buffer and call system APIs twice
        //   (probably slower than memmove)
        // - use a Virtual Ring Buffer
        //   (http://vrb.slashusr.org/)
        // - use a BipBuffer
        //   (http://www.codeproject.com/Articles/3479/The-Bip-Buffer-The-Circular-Buffer-with-a-Twist)

        if (readCount <= len)
        {
            memmove(data, data + readCount, len - readCount);
            len -= readCount;
            return true;
        }
        return false;
    }

    size_t getWriteCapacity()
    {
        return space - len;
    }

    uint8_t* getWriteData()
    {
        return data + len;
    }

    bool commitWrite(size_t writeCount)
    {
        if (writeCount <= space - len)
        {
            len += writeCount;
            return true;
        }
        return false;        
    }
};
