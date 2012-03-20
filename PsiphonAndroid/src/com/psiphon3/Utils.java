package com.psiphon3;

import java.io.Serializable;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.RandomAccess;


public class Utils
{
    /** This class is from http://stackoverflow.com/questions/7266042/java-ring-buffer
     * With the addition of the Serializable support. 
     */
    public static class CircularArrayList<E> extends AbstractList<E> implements RandomAccess, Serializable {

        private final int n; // buffer length
        private final List<E> buf; // a List implementing RandomAccess
        private int leader = 0;
        private int size = 0;
        
        private static final long serialVersionUID = 1L;
        
        public CircularArrayList(int capacity) {
            n = capacity + 1;
            buf = new ArrayList<E>(Collections.nCopies(n, (E) null));
        }
        
        public int capacity() {
            return n - 1;
        }
        
        private int wrapIndex(int i) {
            int m = i % n;
            if (m < 0) { // modulus can be negative
                m += n;
            }
            return m;
        }
        
        @Override
        public int size() {
            return this.size;
        }
        
        @Override
        public E get(int i) {
            if (i < 0 || i >= n-1) throw new IndexOutOfBoundsException();
        
            if(i > size()) throw new NullPointerException("Index is greater than size.");
        
            return buf.get(wrapIndex(leader + i));
        }
        
        @Override
        public E set(int i, E e) {
            if (i < 0 || i >= n-1) {
                throw new IndexOutOfBoundsException();
            }
            if(i == size()) // assume leader's position as invalid (should use insert(e))
                throw new IndexOutOfBoundsException("The size of the list is " + size() + " while the index was " + i
                        +". Please use insert(e) method to fill the list.");
            return buf.set(wrapIndex(leader - size + i), e);
        }
        
        public void insert(E e)
        {
            int s = size();     
            buf.set(wrapIndex(leader), e);
            leader = wrapIndex(++leader);
            buf.set(leader, null);
            if(s == n-1)
                return; // we have replaced the eldest element.
            this.size++;
        
        }
        
        @Override
        public void clear()
        {
            int cnt = wrapIndex(leader-size());
            for(; cnt != leader; cnt = wrapIndex(++cnt))
                this.buf.set(cnt, null);
            this.size = 0;      
        }
        
        public E removeOldest() {
            int i = wrapIndex(leader+1);
        
            for(;;i = wrapIndex(++i)) {
                if(buf.get(i) != null) break;
                if(i == leader)
                    throw new IllegalStateException("Cannot remove element."
                            + " CircularArrayList is empty.");
            }
        
            this.size--;
            return buf.set(i, null);
        }
        
        @Override
        public String toString()
        {
            int i = wrapIndex(leader - size());
            StringBuilder str = new StringBuilder(size());
        
            for(; i != leader; i = wrapIndex(++i)){
                str.append(buf.get(i));
            }
            return str.toString();
        }
        
        public E getOldest(){
            int i = wrapIndex(leader+1);
        
            for(;;i = wrapIndex(++i)) {
                if(buf.get(i) != null) break;
                if(i == leader)
                    throw new IllegalStateException("Cannot remove element."
                            + " CircularArrayList is empty.");
            }
        
            return buf.get(i);
        }
        
        public E getNewest(){
            int i = wrapIndex(leader-1);
            if(buf.get(i) == null)
                throw new IndexOutOfBoundsException("Error while retrieving the newest element. The Circular Array list is empty.");
            return buf.get(i);
        }
    }
}

