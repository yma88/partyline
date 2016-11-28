/**
 * Copyright (C) 2015 Red Hat, Inc. (jdcasey@commonjava.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.util.partyline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Stack;

import static org.apache.commons.lang.StringUtils.join;

public class LockOwner
{

    private WeakReference<Thread> threadRef;

    private Long threadId;

    private String threadName;

    private StackTraceElement[] lockOrigin;

    private final Stack<String> lockRefs = new Stack<>();

    private final LockLevel lockLevel;

    public LockOwner( String label, LockLevel lockLevel )
    {
        this.lockLevel = lockLevel;

        final Thread t = Thread.currentThread();
        this.threadRef = new WeakReference<>( t );
        this.threadName = t.getName() + "(" + label + ")";
        this.threadId = t.getId();
        Logger logger = LoggerFactory.getLogger( getClass() );
        if ( logger.isDebugEnabled() )
        {
            this.lockOrigin = t.getStackTrace();
        }
        else
        {
            this.lockOrigin = null;
        }

        increment( label );
    }

    public boolean isLocked()
    {
        return !lockRefs.isEmpty();
    }

    public synchronized boolean lock( String label, LockLevel lockLevel )
    {
        switch ( lockLevel )
        {
            case delete:
            case write:
            {
                return false;
            }
            case read:
            {
                if ( this.lockLevel == LockLevel.delete )
                {
                    return false;
                }

                increment( label );
                return true;
            }
            default:
                return false;
        }
    }

    public boolean isAlive()
    {
        return threadRef.get() != null && threadRef.get().isAlive();
    }

    public long getThreadId()
    {
        return threadId;
    }

    public String getThreadName()
    {
        return threadName;
    }

    public StackTraceElement[] getLockOrigin()
    {
        return lockOrigin;
    }

    public Thread getThread()
    {
        return threadRef.get();
    }

    @Override
    public String toString()
    {
        return String.format( "LockOwner [%s(%s)]\n  %s", threadName, threadId,
                              lockOrigin == null ? "-suppressed-" : join( lockOrigin, "\n  " ) );
    }

    public boolean isOwnedByCurrentThread()
    {
        return threadId == Thread.currentThread().getId();
    }

    public synchronized CharSequence getLockInfo()
    {
        return new StringBuilder().append( "Lock level: " )
                                  .append( lockLevel )
                                  .append( "\nThread: " )
                                  .append( threadName )
                                  .append( "\nLock Count: " )
                                  .append( lockRefs.size() )
                                  .append( "\nReferences:\n  " )
                                  .append( join( lockRefs, "\n  " ) );
    }

    private synchronized int increment( String label )
    {
        lockRefs.push( label );
        Logger logger = LoggerFactory.getLogger( getClass() );

        int lockCount = lockRefs.size();
        logger.trace( "Incremented lock count to: {} with ref: {}", lockCount, label );
        return lockCount;
    }

    public synchronized boolean unlock()
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        String ref = null;
        if ( !lockRefs.isEmpty() )
        {
            ref = lockRefs.pop();
        }
        int lockCount = lockRefs.size();
        logger.trace( "Decrementing lock count, popping ref: {}. New count is: {}", ref, lockCount );

        if ( lockCount < 1 )
        {
            this.threadId = null;
            this.threadRef.clear();
            this.threadName = null;
            this.lockOrigin = null;
            return true;
        }

        return false;
    }

    public int getLockCount()
    {
        return lockRefs.size();
    }

    public LockLevel getLockLevel()
    {
        return lockLevel;
    }
}
