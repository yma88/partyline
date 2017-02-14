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

import org.apache.commons.io.IOUtils;
import org.commonjava.cdi.util.weft.ThreadContext;
import org.commonjava.util.partyline.callback.CallbackInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * File manager that attempts to manage read/write locks in the presence of output streams that will allow simultaneous access to read the content
 * they are writing. Also allows the user to lock/unlock files manually in case they need to be used outside the normal streaming use cases.
 *
 * @author jdcasey
 */
public class JoinableFileManager
{

    public static final String PARTYLINE_OPEN_FILES = "partyline-open-files";

    public static final long DEFAULT_TIMEOUT = 1000;

    private static final long DEFAULT_FILETREE_TIMEOUT = 100;

    private static final String WAIT_FOR_WRITE_UNLOCK = "waitForWriteUnlock";

    private static final String WITH_TIMEOUT = " (with timeout)";

    private static final String IS_WRITE_LOCKED = "isWriteLocked";

    private static final String UNLOCK = "unlock";

    private static final String LOCK = "lock";

    private static final String OPEN_INPUT_STREAM = "openInputStream";

    private static final String OPEN_OUTPUT_STREAM = "openOutputStream";

    private static final String GET_ACTIVE_LOCKS = "getActiveLocks";

    private static final String CLEANUP_CURRENT_THREAD = "cleanupCurrentThread";

    private static final String DELETE = "delete";

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final FileTree locks = new FileTree();

    private final Timer timer;

    private ReportingTask reporter;

    public JoinableFileManager()
    {
        this.timer = new Timer( true );
    }

    FileTree getFileTree()
    {
        return locks;
    }

    public void cleanupCurrentThread()
    {
        ThreadContext context = ThreadContext.getContext( false );
        if ( context == null )
        {
            return;
        }

        Map<String, WeakReference<Closeable>> open = (Map<String, WeakReference<Closeable>>) context.remove( PARTYLINE_OPEN_FILES );
        if ( open != null )
        {
            open.entrySet().parallelStream().forEach( ( e ) -> {
                String name = e.getKey();
                Closeable c = e.getValue().get();
                if ( c != null )
                {
                    try
                    {
                        c.close();
                    }
                    catch ( IOException ex )
                    {
                        logger.error( "Failed to close: " + name + ". Re-adding to thread context.", ex );
                        addToContext( name, c );
                    }
                }
            } );
        }
//        final long id = Thread.currentThread().getId();
//        locks.forFilesOwnedBy( id, CLEANUP_CURRENT_THREAD, ( jf ) -> {
//            final StringBuilder sb = new StringBuilder();
//            LockOwner owner = jf.getLockOwner();
//            sb.append( "CLEARING ORPHANED LOCK:\nFile: " )
//              .append( jf )
//              .append( "\nOwned by thread: " )
//              .append( owner.getThreadName() )
//              .append( " (ID: " )
//              .append( owner.getThreadId() )
//              .append( ")" )
//              .append( "\nLock Info:\n  " )
//              .append( owner.getLockInfo() )
//              .append( "\n\nLock type: " )
//              .append( jf.isWriteLocked() ? "WRITE" : "READ" )
//              .append( "\nLocked at:\n" );
//
//            StackTraceElement[] lockOrigin = owner.getLockOrigin();
//            if ( lockOrigin != null )
//            {
//                for ( final StackTraceElement elt : lockOrigin )
//                {
//                    sb.append( "\n  " ).append( elt );
//                }
//            }
//
//            sb.append( "\n\n" );
//
//            logger.warn( sb.toString() );
//
//            try
//            {
//                jf.forceClose();
//            }
//            catch ( IOException e )
//            {
//                logger.warn( String.format( "Failed to force-close: %s. Reason: %s", jf.getPath(), e.getMessage() ), e );
//            }
//            catch ( InterruptedException e )
//            {
//                logger.warn( "Interrupted while cleaning up resources owned by thread: {}", Thread.currentThread().getName() );
//                return false;
//            }
//
//            logger.trace( "After cleanup, lock info is: {}", jf.getLockOwner().getLockInfo() );
//
//            return true;
//        } );
    }

    public synchronized void startReporting()
    {
        startReporting( 0, 10000 );
    }

    public synchronized void startReporting( final long delay, final long period )
    {
        if ( reporter == null )
        {
            logger.info( "Starting file-lock statistics reporting with initial delay: {}ms and period: {}ms", delay,
                         period );
            reporter = new ReportingTask();
            timer.schedule( reporter, delay, period );
        }
    }

    public synchronized void stopReporting()
    {
        if ( reporter != null )
        {
            logger.info( "Stopping file-lock statistics reporting." );
            reporter.cancel();
        }
    }

    public Map<File, CharSequence> getActiveLocks()
    {
        final Map<File, CharSequence> active = new HashMap<File, CharSequence>();

        locks.forAll( ( jf ) -> {
            final StringBuilder owner = new StringBuilder();

            final LockOwner ref = jf.getLockOwner();

            if ( ref == null )
            {
                owner.append( "UNKNOWN OWNER; REF IS NULL." );
            }
            else
            {
                owner.append( ref.getLockInfo() );
            }

            if ( jf.isWriteLocked() )
            {
                owner.append( " (JoinableFile locked as WRITE)" );
            }
            else
            {
                owner.append( " (JoinableFile locked as READ)" );
            }

            active.put( new File( jf.getPath() ), owner );
        } );

        return active;
    }

    /**
     * If the file isn't marked as active, create a new {@link JoinableFile} to the specified file and pass it back to the user.
     */
    public OutputStream openOutputStream( final File file )
            throws IOException, InterruptedException
    {
        return openOutputStream( file, -1 );
    }

    /**
     * If the file isn't marked as active, create a new {@link JoinableFile} to the specified file and pass it back to the user. If the file is locked, wait for the specified milliseconds before giving up.
     */
    public OutputStream openOutputStream( final File file, final long timeout )
            throws IOException, InterruptedException
    {
        logger.trace( ">>>OPEN OUTPUT: {} with timeout: {}", file, timeout );

        OutputStream stream = locks.setOrJoinFile( file, null, true, timeout, TimeUnit.MILLISECONDS, ( result ) -> {
            if ( result == null )
            {
                throw new IOException( "Could not open output stream to: " + file + " in " + timeout + "ms." );
            }

            return result.getOutputStream();
        } );

        addToContext( "OUTPUT: " + file, stream );

        return stream;
    }

    public boolean tryDelete( File file )
            throws IOException, InterruptedException
    {
        return tryDelete( file, -1 );
    }

    public boolean tryDelete( File file, long timeout )
            throws IOException, InterruptedException
    {
        logger.trace( ">>>DELETE: {}", file, timeout );
        boolean result = locks.delete( file, timeout, TimeUnit.MILLISECONDS );
        logger.trace( "<<<DELETE (Result: {}, file exists? {})", result, file.exists() );
        return result;
    }

    /**
     * If there is an active {@link JoinableFile}, call {@link JoinableFile#joinStream()} and return it to the user. Otherwise, open
     * a new {@link FileInputStream} to the specified file, wrap it in a {@link CallbackInputStream} to notify this manager when it closes, and pass
     * the result back to the user.
     */
    public InputStream openInputStream( final File file )
            throws IOException, InterruptedException
    {
        return openInputStream( file, 0 );
    }

    /**
     * If there is an active {@link JoinableFile}, call {@link JoinableFile#joinStream()} and return it to the user. Otherwise, open
     * a new {@link FileInputStream} to the specified file, wrap it in a {@link CallbackInputStream} to notify this manager when it closes, and pass
     * the result back to the user. If the file is locked for reads, wait for the specified milliseconds before giving up.
     */
    public InputStream openInputStream( final File file, final long timeout )
            throws IOException, InterruptedException
    {
        logger.trace( ">>>OPEN INPUT: {} with timeout: {}", file, timeout );
        AtomicReference<InterruptedException> interrupt = new AtomicReference<>();
        InputStream stream = locks.setOrJoinFile( file, null, false, timeout, TimeUnit.MILLISECONDS, ( result ) -> {
            if ( result == null )
            {
                throw new IOException( "Could not open input stream to: " + file + " in " + timeout + "ms." );
            }

            try
            {
                return result.joinStream();
            }
            catch ( InterruptedException e )
            {
                interrupt.set( e );
            }

            return null;
        } );

        InterruptedException ie = interrupt.get();
        if ( ie != null )
        {
            throw ie;
        }

        addToContext( "INPUT: " + file, stream );

        return stream;
    }

    private void addToContext( String name, Closeable closeable )
    {
        logger.info( "Adding {} to closeable set in ThreadContext", name );

        ThreadContext threadContext = ThreadContext.getContext( false );
        if ( closeable != null && threadContext != null )
        {
            Map<String, WeakReference<Closeable>> open = (Map<String, WeakReference<Closeable>>) threadContext.get( PARTYLINE_OPEN_FILES );
            if ( open == null )
            {
                open = new WeakHashMap<>();
                threadContext.put( PARTYLINE_OPEN_FILES, open );
            }
            open.put( name, new WeakReference( closeable ) );
        }
    }

    /**
     * Manually lock the specified file to prevent opening any streams via this manager (until manually unlocked).
     */
    public boolean lock( final File file, long timeout, LockLevel lockLevel, String operationName )
            throws InterruptedException
    {
        logger.trace( ">>>MANUAL LOCK: {}", file );
        boolean result = locks.tryLock( file, operationName, "Manual lock", lockLevel, timeout, TimeUnit.MILLISECONDS );
        logger.trace( "<<<MANUAL LOCK (result: {})", result );

        return result;
    }

    /**
     * If the specified file was manually locked, unlock it and return the state of locks remaining on the file.
     * Return true if the file is unlocked, false if locks remain.
     */
    public boolean unlock( final File file, String operationName )
    {
        logger.trace( ">>>MANUAL UNLOCK: {} by: {}", file, operationName );
        boolean result = locks.unlock( file, operationName );

        if ( result )
        {
            logger.trace( "<<<MANUAL UNLOCK (success)" );
        }
        else
        {
            logger.trace( "<<<MANUAL UNLOCK (failed)" );
        }

        return result;
    }

    /**
     * Check if the specified file is locked against write operations. Files are write-locked if any other file access is active.
     */
    public boolean isWriteLocked( final File file )
    {
        return locks.getLockLevel( file ) != null;
    }

    /**
     * The only time reads are not allowed is when the file is locked for deletion.
     */
    public boolean isReadLocked( final File file )
    {
        return locks.getLockLevel( file ) == LockLevel.delete;
    }

    /**
     * Wait the specified timeout milliseconds for write access on the specified file to become available. Return false if the timeout elapses without
     * the file becoming available for writes.
     *
     * @see #isWriteLocked(File)
     */
    public boolean waitForWriteUnlock( final File file )
            throws InterruptedException
    {
        return waitForWriteUnlock( file, -1 );
    }

    /**
     * Wait the specified timeout milliseconds for write access on the specified file to become available. Return false if the timeout elapses without
     * the file becoming available for writes.
     *
     * @see #isWriteLocked(File)
     */
    // FIXME: How do we prevent new incoming lock requests while we wait?
    public boolean waitForWriteUnlock( final File file, long timeout )
            throws InterruptedException
    {
        long to = timeout < 1 ? DEFAULT_TIMEOUT : timeout;

        logger.trace( ">>>WAIT (write unlock): {} with timeout: {}", file, to );

        long end = System.currentTimeMillis() + to;
        boolean result = false;
        while ( System.currentTimeMillis() < end )
        {
            LockLevel lockLevel = locks.getLockLevel( file );
            if ( lockLevel == null )
            {
                result = true;
                break;
            }

            synchronized ( locks )
            {
                locks.wait( 100 );
            }

            lockLevel = locks.getLockLevel( file );
            if ( lockLevel == null )
            {
                result = true;
                break;
            }
        }

        logger.trace( "<<<WAIT (write unlock) result: {}", result );
        return result;
    }

    /**
     * Wait the specified timeout milliseconds for read access on the specified file to become available. Return false if the timeout elapses without
     * the file becoming available for reads. If a {@link JoinableFile} is available for the file, don't wait (immediately return true).
     *
     * @see #isReadLocked(File)
     */
    public boolean waitForReadUnlock( final File file, final long timeout )
            throws InterruptedException
    {
        long to = timeout < 1 ? DEFAULT_TIMEOUT : timeout;

        logger.trace( ">>>WAIT (read unlock): {} with timeout: {}", file, to );

        long end = System.currentTimeMillis() + to;
        boolean result = false;
        while ( System.currentTimeMillis() < end )
        {
            LockLevel lockLevel = locks.getLockLevel( file );
            if ( lockLevel != LockLevel.delete )
            {
                result = true;
                break;
            }

            synchronized ( locks )
            {
                locks.wait( 100 );
            }

            lockLevel = locks.getLockLevel( file );
            if ( lockLevel != LockLevel.delete )
            {
                result = true;
                break;
            }
        }

        logger.trace( "<<<WAIT (read unlock) result: {}", result );
        return result;
    }

    /**
     * Wait the specified timeout milliseconds for read access on the specified file to become available. Return false if the timeout elapses without
     * the file becoming available for reads. If a {@link JoinableFile} is available for the file, don't wait (immediately return true).
     *
     * @see #isReadLocked(File)
     */
    public boolean waitForReadUnlock( final File file )
            throws InterruptedException
    {
        return waitForReadUnlock( file, -1 );
    }

    private final class ReportingTask
            extends TimerTask
    {
        @Override
        public void run()
        {
            final Map<File, CharSequence> activeLocks = getActiveLocks();
            if ( activeLocks.isEmpty() )
            {
                logger.trace( "No file locks to report." );
                return;
            }

            final StringBuilder sb = new StringBuilder();
            sb.append( "\n\nThe following file locks are still active:" );
            for ( final File file : activeLocks.keySet() )
            {
                sb.append( "\n" ).append( file ).append( " is owned by " ).append( activeLocks.get( file ) );
            }

            sb.append( "\n\n" );

            logger.info( sb.toString() );
        }
    }

}
