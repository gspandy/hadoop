/**
 * Copyright 2007 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase;

import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Vector;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.filter.RowFilterInterface;
import org.apache.hadoop.hbase.io.BatchOperation;
import org.apache.hadoop.hbase.io.BatchUpdate;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.InfoServer;
import org.apache.hadoop.hbase.util.Sleeper;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hbase.util.Writables;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.net.DNS;
import org.apache.hadoop.util.StringUtils;

/**
 * HRegionServer makes a set of HRegions available to clients.  It checks in with
 * the HMaster. There are many HRegionServers in a single HBase deployment.
 */
public class HRegionServer implements HConstants, HRegionInterface, Runnable {
  static final Log LOG = LogFactory.getLog(HRegionServer.class);
  
  // Set when a report to the master comes back with a message asking us to
  // shutdown.  Also set by call to stop when debugging or running unit tests
  // of HRegionServer in isolation. We use AtomicBoolean rather than
  // plain boolean so we can pass a reference to Chore threads.  Otherwise,
  // Chore threads need to know about the hosting class.
  protected final AtomicBoolean stopRequested = new AtomicBoolean(false);
  
  // Go down hard.  Used if file system becomes unavailable and also in
  // debugging and unit tests.
  protected volatile boolean abortRequested;
  
  // If false, the file system has become unavailable
  protected volatile boolean fsOk;
  
  protected final HServerInfo serverInfo;
  protected final HBaseConfiguration conf;
  private final Random rand = new Random();
  
  // region name -> HRegion
  protected final SortedMap<Text, HRegion> onlineRegions =
    Collections.synchronizedSortedMap(new TreeMap<Text, HRegion>());
  protected final Map<Text, HRegion> retiringRegions =
    new HashMap<Text, HRegion>();
 
  protected final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private final Vector<HMsg> outboundMsgs = new Vector<HMsg>();

  final int numRetries;
  protected final int threadWakeFrequency;
  private final int msgInterval;
  private final int serverLeaseTimeout;

  // Remote HMaster
  private HMasterRegionInterface hbaseMaster;

  // Server to handle client requests.  Default access so can be accessed by
  // unit tests.
  final Server server;
  
  // Leases
  private final Leases leases;
  
  // Request counter
  private final AtomicInteger requestCount = new AtomicInteger();
  
  // A sleeper that sleeps for msgInterval.
  private final Sleeper sleeper;

  // Info server.  Default access so can be used by unit tests.  REGIONSERVER
  // is name of the webapp and the attribute name used stuffing this instance
  // into web context.
  InfoServer infoServer;
  
  /** region server process name */
  public static final String REGIONSERVER = "regionserver";

  // Check to see if regions should be split
  private final Thread splitOrCompactCheckerThread;
  // Needed at shutdown. On way out, if can get this lock then we are not in
  // middle of a split or compaction: i.e. splits/compactions cannot be
  // interrupted.
  protected final Integer splitOrCompactLock = new Integer(0);
  
  /*
   * Runs periodically to determine if regions need to be compacted or split
   */
  class SplitOrCompactChecker extends Chore
  implements RegionUnavailableListener {
    private HTable root = null;
    private HTable meta = null;

    /**
     * @param stop
     */
    public SplitOrCompactChecker(final AtomicBoolean stop) {
      super(conf.getInt("hbase.regionserver.thread.splitcompactcheckfrequency",
        30 * 1000), stop);
    }

    /** {@inheritDoc} */
    public void closing(final Text regionName) {
      lock.writeLock().lock();
      try {
        // Remove region from regions Map and add it to the Map of retiring
        // regions.
        retiringRegions.put(regionName, onlineRegions.remove(regionName));
        if (LOG.isDebugEnabled()) {
          LOG.debug(regionName.toString() + " closing (" +
            "Adding to retiringRegions)");
        }
      } finally {
        lock.writeLock().unlock();
      }
    }
    
    /** {@inheritDoc} */
    public void closed(final Text regionName) {
      lock.writeLock().lock();
      try {
        retiringRegions.remove(regionName);
        if (LOG.isDebugEnabled()) {
          LOG.debug(regionName.toString() + " closed");
        }
      } finally {
        lock.writeLock().unlock();
      }
    }
    
    /**
     * Scan for splits or compactions to run.  Run any we find.
     */
    @Override
    protected void chore() {
      // Don't interrupt us while we're working
      synchronized (splitOrCompactLock) {
        checkForSplitsOrCompactions();
      }
    }
    
    private void checkForSplitsOrCompactions() {
      // Grab a list of regions to check
      List<HRegion> nonClosedRegionsToCheck = getRegionsToCheck();
      for(HRegion cur: nonClosedRegionsToCheck) {
        try {
          if (cur.needsCompaction()) {
            cur.compactStores();
          }
          // After compaction, it probably needs splitting.  May also need
          // splitting just because one of the memcache flushes was big.
          Text midKey = new Text();
          if (cur.needsSplit(midKey)) {
            split(cur, midKey);
          }
        } catch(IOException e) {
          //TODO: What happens if this fails? Are we toast?
          LOG.error("Split or compaction failed", e);
          if (!checkFileSystem()) {
            break;
          }
        }
      }
    }
    
    private void split(final HRegion region, final Text midKey)
    throws IOException {
      final HRegionInfo oldRegionInfo = region.getRegionInfo();
      final HRegion[] newRegions = region.closeAndSplit(midKey, this);
      
      // When a region is split, the META table needs to updated if we're
      // splitting a 'normal' region, and the ROOT table needs to be
      // updated if we are splitting a META region.
      HTable t = null;
      if (region.getRegionInfo().getTableDesc().getName().equals(META_TABLE_NAME)) {
        // We need to update the root region
        if (this.root == null) {
          this.root = new HTable(conf, ROOT_TABLE_NAME);
        }
        t = root;
      } else {
        // For normal regions we need to update the meta region
        if (meta == null) {
          meta = new HTable(conf, META_TABLE_NAME);
        }
        t = meta;
      }
      LOG.info("Updating " + t.getTableName() + " with region split info");

      // Mark old region as offline and split in META.
      // NOTE: there is no need for retry logic here. HTable does it for us.
      long lockid = t.startUpdate(oldRegionInfo.getRegionName());
      oldRegionInfo.setOffline(true);
      oldRegionInfo.setSplit(true);
      t.put(lockid, COL_REGIONINFO, Writables.getBytes(oldRegionInfo));
      t.put(lockid, COL_SPLITA, Writables.getBytes(
        newRegions[0].getRegionInfo()));
      t.put(lockid, COL_SPLITB, Writables.getBytes(
        newRegions[1].getRegionInfo()));
      t.commit(lockid);
      
      // Add new regions to META
      for (int i = 0; i < newRegions.length; i++) {
        lockid = t.startUpdate(newRegions[i].getRegionName());
        t.put(lockid, COL_REGIONINFO, Writables.getBytes(
          newRegions[i].getRegionInfo()));
        t.commit(lockid);
      }
          
      // Now tell the master about the new regions
      if (LOG.isDebugEnabled()) {
        LOG.debug("Reporting region split to master");
      }
      reportSplit(oldRegionInfo, newRegions[0].getRegionInfo(),
        newRegions[1].getRegionInfo());
      LOG.info("region split, META update, and report to master all" +
        " successful. Old region=" + oldRegionInfo.getRegionName() +
        ", new regions: " + newRegions[0].getRegionName() + ", " +
        newRegions[1].getRegionName());
      
      // Do not serve the new regions. Let the Master assign them.
    }
  }

  // Cache flushing  
  private final Thread cacheFlusherThread;
  // Needed during shutdown so we send an interrupt after completion of a
  // flush, not in the midst.
  protected final Integer cacheFlusherLock = new Integer(0);
  
  /* Runs periodically to flush memcache.
   */
  class Flusher extends Chore {
    /**
     * @param period
     * @param stop
     */
    public Flusher(final int period, final AtomicBoolean stop) {
      super(period, stop);
    }
    
    @Override
    protected void chore() {
      synchronized(cacheFlusherLock) {
        checkForFlushesToRun();
      }
    }
    
    private void checkForFlushesToRun() {
      // Grab a list of items to flush
      List<HRegion> nonClosedRegionsToFlush = getRegionsToCheck();
      // Flush them, if necessary
      for(HRegion cur: nonClosedRegionsToFlush) {
        try {
          cur.optionallyFlush();
        } catch (DroppedSnapshotException e) {
          // Cache flush can fail in a few places.  If it fails in a critical
          // section, we get a DroppedSnapshotException and a replay of hlog
          // is required. Currently the only way to do this is a restart of
          // the server.
          LOG.fatal("Replay of hlog required. Forcing server restart", e);
          if (!checkFileSystem()) {
            break;
          }
          HRegionServer.this.stop();
        } catch (IOException iex) {
          LOG.error("Cache flush failed",
            RemoteExceptionHandler.checkIOException(iex));
          if (!checkFileSystem()) {
            break;
          }
        }
      }
    }
  }

  // HLog and HLog roller.  log is protected rather than private to avoid
  // eclipse warning when accessed by inner classes
  protected HLog log;
  private final Thread logRollerThread;
  protected final Integer logRollerLock = new Integer(0);
  
  /** Runs periodically to determine if the HLog should be rolled */
  class LogRoller extends Chore {
    private int MAXLOGENTRIES =
      conf.getInt("hbase.regionserver.maxlogentries", 30 * 1000);
    
    /**
     * @param period
     * @param stop
     */
    public LogRoller(final int period, final AtomicBoolean stop) {
      super(period, stop);
    }
 
    /** {@inheritDoc} */
    @Override
    protected void chore() {
      synchronized(logRollerLock) {
        checkForLogRoll();
      }
    }

    private void checkForLogRoll() {
      // If the number of log entries is high enough, roll the log.  This
      // is a very fast operation, but should not be done too frequently.
      int nEntries = log.getNumEntries();
      if(nEntries > this.MAXLOGENTRIES) {
        try {
          LOG.info("Rolling hlog. Number of entries: " + nEntries);
          log.rollWriter();
        } catch (IOException iex) {
          LOG.error("Log rolling failed",
            RemoteExceptionHandler.checkIOException(iex));
          checkFileSystem();
        }
      }
    }
  }

  /**
   * Starts a HRegionServer at the default location
   * @param conf
   * @throws IOException
   */
  public HRegionServer(HBaseConfiguration conf) throws IOException {
    this(new HServerAddress(conf.get(REGIONSERVER_ADDRESS,
        DEFAULT_REGIONSERVER_ADDRESS)), conf);
  }
  
  /**
   * Starts a HRegionServer at the specified location
   * @param address
   * @param conf
   * @throws IOException
   */
  public HRegionServer(HServerAddress address, HBaseConfiguration conf)
  throws IOException {  
    this.abortRequested = false;
    this.fsOk = true;
    this.conf = conf;

    // Config'ed params
    this.numRetries =  conf.getInt("hbase.client.retries.number", 2);
    this.threadWakeFrequency = conf.getInt(THREAD_WAKE_FREQUENCY, 10 * 1000);
    this.msgInterval = conf.getInt("hbase.regionserver.msginterval", 3 * 1000);
    this.serverLeaseTimeout =
      conf.getInt("hbase.master.lease.period", 30 * 1000);

    // Cache flushing chore thread.
    this.cacheFlusherThread =
      new Flusher(this.threadWakeFrequency, stopRequested);
    
    // Check regions to see if they need to be split or compacted chore thread
    this.splitOrCompactCheckerThread =
      new SplitOrCompactChecker(this.stopRequested);
    
    // Task thread to process requests from Master
    this.worker = new Worker();
    this.workerThread = new Thread(worker);
    this.sleeper = new Sleeper(this.msgInterval, this.stopRequested);
    this.logRollerThread =
      new LogRoller(this.threadWakeFrequency, stopRequested);
    // Server to handle client requests
    this.server = RPC.getServer(this, address.getBindAddress(), 
      address.getPort(), conf.getInt("hbase.regionserver.handler.count", 10),
      false, conf);
    this.serverInfo = new HServerInfo(new HServerAddress(
      new InetSocketAddress(getThisIP(),
      this.server.getListenerAddress().getPort())), this.rand.nextLong(),
      this.conf.getInt("hbase.regionserver.info.port", 60030));
     this.leases = new Leases(
       conf.getInt("hbase.regionserver.lease.period", 3 * 60 * 1000),
       this.threadWakeFrequency);
  }

  /**
   * The HRegionServer sticks in this loop until closed. It repeatedly checks
   * in with the HMaster, sending heartbeats & reports, and receiving HRegion 
   * load/unload instructions.
   */
  public void run() {
    try {
      init(reportForDuty());
      long lastMsg = 0;
      while(!stopRequested.get()) {
        // Now ask master what it wants us to do and tell it what we have done
        for (int tries = 0; !stopRequested.get();) {
          long now = System.currentTimeMillis();
          if (lastMsg != 0 && (now - lastMsg) >= serverLeaseTimeout) {
            // It has been way too long since we last reported to the master.
            // Commit suicide.
            LOG.fatal("unable to report to master for " + (now - lastMsg) +
                " milliseconds - aborting server");
            abort();
            break;
          }
          if ((now - lastMsg) >= msgInterval) {
            HMsg outboundArray[] = null;
            synchronized(outboundMsgs) {
              outboundArray =
                this.outboundMsgs.toArray(new HMsg[outboundMsgs.size()]);
              this.outboundMsgs.clear();
            }

            try {
              this.serverInfo.setLoad(new HServerLoad(requestCount.get(),
                  onlineRegions.size()));
              this.requestCount.set(0);
              HMsg msgs[] =
                this.hbaseMaster.regionServerReport(serverInfo, outboundArray);
              lastMsg = System.currentTimeMillis();
              // Queue up the HMaster's instruction stream for processing
              boolean restart = false;
              for(int i = 0; i < msgs.length && !stopRequested.get() &&
                  !restart; i++) {
                switch(msgs[i].getMsg()) {
                
                case HMsg.MSG_CALL_SERVER_STARTUP:
                  if (LOG.isDebugEnabled()) {
                    LOG.debug("Got call server startup message");
                  }
                  // We the MSG_CALL_SERVER_STARTUP on startup but we can also
                  // get it when the master is panicing because for instance
                  // the HDFS has been yanked out from under it.  Be wary of
                  // this message.
                  if (checkFileSystem()) {
                    closeAllRegions();
                    synchronized (logRollerLock) {
                      try {
                        log.closeAndDelete();
                        serverInfo.setStartCode(rand.nextLong());
                        log = setupHLog();
                      } catch (IOException e) {
                        this.abortRequested = true;
                        this.stopRequested.set(true);
                        e = RemoteExceptionHandler.checkIOException(e); 
                        LOG.fatal("error restarting server", e);
                        break;
                      }
                    }
                    reportForDuty();
                    restart = true;
                  } else {
                    LOG.fatal("file system available check failed. " +
                        "Shutting down server.");
                  }
                  break;

                case HMsg.MSG_REGIONSERVER_STOP:
                  if (LOG.isDebugEnabled()) {
                    LOG.debug("Got regionserver stop message");
                  }
                  stopRequested.set(true);
                  break;

                default:
                  if (fsOk) {
                    try {
                      toDo.put(new ToDoEntry(msgs[i]));
                    } catch (InterruptedException e) {
                      throw new RuntimeException("Putting into msgQueue was " +
                        "interrupted.", e);
                    }
                  }
                }
              }
              if (restart || this.stopRequested.get()) {
                toDo.clear();
                break;
              }
              // Reset tries count if we had a successful transaction.
              tries = 0;
            } catch (IOException e) {
              e = RemoteExceptionHandler.checkIOException(e);
              if(tries < this.numRetries) {
                LOG.warn("Processing message (Retry: " + tries + ")", e);
                tries++;
              } else {
                LOG.error("Exceeded max retries: " + this.numRetries, e);
                if (!checkFileSystem()) {
                  continue;
                }
                // Something seriously wrong. Shutdown.
                stop();
              }
            }
          }
          
          this.sleeper.sleep(lastMsg);
        } // while (!stopRequested.get())
      }
    } catch (Throwable t) {
      LOG.fatal("Unhandled exception. Aborting...", t);
      abort();
    }
    this.leases.closeAfterLeasesExpire();
    this.worker.stop();
    this.server.stop();
    if (this.infoServer != null) {
      LOG.info("Stopping infoServer");
      try {
        this.infoServer.stop();
      } catch (InterruptedException ex) {
        ex.printStackTrace();
      }
    }

    // Send interrupts to wake up threads if sleeping so they notice shutdown.
    // TODO: Should we check they are alive?  If OOME could have exited already
    synchronized(logRollerLock) {
      this.logRollerThread.interrupt();
    }
    synchronized(cacheFlusherLock) {
      this.cacheFlusherThread.interrupt();
    }
    synchronized(splitOrCompactLock) {
      this.splitOrCompactCheckerThread.interrupt();
    }

    if (abortRequested) {
      if (this.fsOk) {
        // Only try to clean up if the file system is available
        try {
          this.log.close();
          LOG.info("On abort, closed hlog");
        } catch (IOException e) {
          LOG.error("Unable to close log in abort",
              RemoteExceptionHandler.checkIOException(e));
        }
        closeAllRegions(); // Don't leave any open file handles
      }
      LOG.info("aborting server at: " +
        serverInfo.getServerAddress().toString());
    } else {
      ArrayList<HRegion> closedRegions = closeAllRegions();
      try {
        log.closeAndDelete();
      } catch (IOException e) {
        LOG.error("Close and delete failed",
            RemoteExceptionHandler.checkIOException(e));
      }
      try {
        HMsg[] exitMsg = new HMsg[closedRegions.size() + 1];
        exitMsg[0] = new HMsg(HMsg.MSG_REPORT_EXITING);
        // Tell the master what regions we are/were serving
        int i = 1;
        for(HRegion region: closedRegions) {
          exitMsg[i++] = new HMsg(HMsg.MSG_REPORT_CLOSE,
              region.getRegionInfo());
        }

        LOG.info("telling master that region server is shutting down at: " +
            serverInfo.getServerAddress().toString());
        hbaseMaster.regionServerReport(serverInfo, exitMsg);
      } catch (IOException e) {
        LOG.warn("Failed to send exiting message to master: ",
            RemoteExceptionHandler.checkIOException(e));
      }
      LOG.info("stopping server at: " +
        serverInfo.getServerAddress().toString());
    }

    join();
    LOG.info(Thread.currentThread().getName() + " exiting");
  }
  
  /*
   * Run init. Sets up hlog and starts up all server threads.
   * @param c Extra configuration.
   */
  private void init(final MapWritable c) throws IOException {
    try {
      for (Map.Entry<Writable, Writable> e: c.entrySet()) {
        String key = e.getKey().toString();
        String value = e.getValue().toString();
        if (LOG.isDebugEnabled()) {
          LOG.debug("Config from master: " + key + "=" + value);
        }
        this.conf.set(key, value);
      }
      this.log = setupHLog();
      startServiceThreads();
    } catch (IOException e) {
      this.stopRequested.set(true);
      e = RemoteExceptionHandler.checkIOException(e); 
      LOG.fatal("Failed init", e);
      IOException ex = new IOException("region server startup failed");
      ex.initCause(e);
      throw ex;
    }
  }
  
  private HLog setupHLog() throws RegionServerRunningException,
    IOException {
    
    String rootDir = this.conf.get(HConstants.HBASE_DIR);
    LOG.info("Root dir: " + rootDir);
    Path logdir = new Path(new Path(rootDir), "log" + "_" + getThisIP() + "_" +
        this.serverInfo.getStartCode() + "_" + 
        this.serverInfo.getServerAddress().getPort());
    if (LOG.isDebugEnabled()) {
      LOG.debug("Log dir " + logdir);
    }
    FileSystem fs = FileSystem.get(this.conf);
    if (fs.exists(logdir)) {
      throw new RegionServerRunningException("region server already " +
        "running at " + this.serverInfo.getServerAddress().toString() +
        " because logdir " + logdir.toString() + " exists");
    }
    return new HLog(fs, logdir, conf);
  }
  
  /*
   * Start Chore Threads, Server, Worker and lease checker threads. Install an
   * UncaughtExceptionHandler that calls abort of RegionServer if we get
   * an unhandled exception.  We cannot set the handler on all threads.
   * Server's internal Listener thread is off limits.  For Server, if an OOME,
   * it waits a while then retries.  Meantime, a flush or a compaction that
   * tries to run should trigger same critical condition and the shutdown will
   * run.  On its way out, this server will shut down Server.  Leases are sort
   * of inbetween. It has an internal thread that while it inherits from
   * Chore, it keeps its own internal stop mechanism so needs to be stopped
   * by this hosting server.  Worker logs the exception and exits.
   */
  private void startServiceThreads() throws IOException {
    String n = Thread.currentThread().getName();
    UncaughtExceptionHandler handler = new UncaughtExceptionHandler() {
      public void uncaughtException(Thread t, Throwable e) {
        abort();
        LOG.fatal("Set stop flag in " + t.getName(), e);
      }
    };
    Threads.setDaemonThreadRunning(this.cacheFlusherThread, n + ".cacheFlusher",
      handler);
    Threads.setDaemonThreadRunning(this.splitOrCompactCheckerThread,
      n + ".splitOrCompactChecker", handler);
    Threads.setDaemonThreadRunning(this.logRollerThread, n + ".logRoller",
      handler);
    // Worker is not the same as the above threads in that it does not
    // inherit from Chore.  Set an UncaughtExceptionHandler on it in case its
    // the one to see an OOME, etc., first.  The handler will set the stop
    // flag.
    Threads.setDaemonThreadRunning(this.workerThread, n + ".worker", handler);
    // Leases is not a Thread. Internally it runs a daemon thread.  If it gets
    // an unhandled exception, it will just exit.
    this.leases.setName(n + ".leaseChecker");
    this.leases.start();
    // Put up info server.
    int port = this.conf.getInt("hbase.regionserver.info.port", 60030);
    if (port >= 0) {
      String a = this.conf.get("hbase.master.info.bindAddress", "0.0.0.0");
      this.infoServer = new InfoServer("regionserver", a, port, false);
      this.infoServer.setAttribute("regionserver", this);
      this.infoServer.start();
    }
    // Start Server.  This service is like leases in that it internally runs
    // a thread.
    this.server.start();
    LOG.info("HRegionServer started at: " +
        serverInfo.getServerAddress().toString());
  }

  /** @return the HLog */
  HLog getLog() {
    return this.log;
  }

  /*
   * Use interface to get the 'real' IP for this host. 'serverInfo' is sent to
   * master.  Should have the real IP of this host rather than 'localhost' or
   * 0.0.0.0 or 127.0.0.1 in it.
   * @return This servers' IP.
   */
  private String getThisIP() throws UnknownHostException {
    return DNS.getDefaultIP(conf.get("dfs.datanode.dns.interface","default"));
  }

  /**
   * Sets a flag that will cause all the HRegionServer threads to shut down
   * in an orderly fashion.  Used by unit tests and called by {@link Flusher}
   * if it judges server needs to be restarted.
   */
  synchronized void stop() {
    this.stopRequested.set(true);
    notifyAll();                        // Wakes run() if it is sleeping
  }
  
  /**
   * Cause the server to exit without closing the regions it is serving, the
   * log it is using and without notifying the master.
   * Used unit testing and on catastrophic events such as HDFS is yanked out
   * from under hbase or we OOME.
   */
  synchronized void abort() {
    this.abortRequested = true;
    stop();
  }

  /** 
   * Wait on all threads to finish.
   * Presumption is that all closes and stops have already been called.
   */
  void join() {
    join(this.workerThread);
    join(this.logRollerThread);
    join(this.cacheFlusherThread);
    join(this.splitOrCompactCheckerThread);
  }

  private void join(final Thread t) {
    while (t.isAlive()) {
      try {
        t.join();
      } catch (InterruptedException e) {
        // continue
      }
    }
  }
  
  /*
   * Let the master know we're here
   * Run initialization using parameters passed us by the master.
   */
  private MapWritable reportForDuty() throws IOException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Telling master we are up");
    }
    // Do initial RPC setup.
    this.hbaseMaster = (HMasterRegionInterface)RPC.waitForProxy(
      HMasterRegionInterface.class, HMasterRegionInterface.versionID,
      new HServerAddress(conf.get(MASTER_ADDRESS)).getInetSocketAddress(),
      this.conf);
    MapWritable result = null;
    long lastMsg = 0;
    while(!stopRequested.get()) {
      try {
        this.requestCount.set(0);
        this.serverInfo.setLoad(new HServerLoad(0, onlineRegions.size()));
        result = this.hbaseMaster.regionServerStartup(serverInfo);
        lastMsg = System.currentTimeMillis();
        if (LOG.isDebugEnabled()) {
          LOG.debug("Done telling master we are up");
        }
        break;
      } catch(IOException e) {
        LOG.warn("error telling master we are up", e);
        this.sleeper.sleep(lastMsg);
        continue;
      }
    }
    return result;
  }

  /** Add to the outbound message buffer */
  private void reportOpen(HRegion region) {
    synchronized(outboundMsgs) {
      outboundMsgs.add(new HMsg(HMsg.MSG_REPORT_OPEN, region.getRegionInfo()));
    }
  }

  /** Add to the outbound message buffer */
  private void reportClose(HRegion region) {
    synchronized(outboundMsgs) {
      outboundMsgs.add(new HMsg(HMsg.MSG_REPORT_CLOSE, region.getRegionInfo()));
    }
  }
  
  /**
   * Add to the outbound message buffer
   * 
   * When a region splits, we need to tell the master that there are two new 
   * regions that need to be assigned.
   * 
   * We do not need to inform the master about the old region, because we've
   * updated the meta or root regions, and the master will pick that up on its
   * next rescan of the root or meta tables.
   */
  void reportSplit(HRegionInfo oldRegion, HRegionInfo newRegionA,
      HRegionInfo newRegionB) {
    synchronized(outboundMsgs) {
      outboundMsgs.add(new HMsg(HMsg.MSG_REPORT_SPLIT, oldRegion));
      outboundMsgs.add(new HMsg(HMsg.MSG_REPORT_OPEN, newRegionA));
      outboundMsgs.add(new HMsg(HMsg.MSG_REPORT_OPEN, newRegionB));
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  // HMaster-given operations
  //////////////////////////////////////////////////////////////////////////////

  private static class ToDoEntry {
    int tries;
    HMsg msg;
    ToDoEntry(HMsg msg) {
      this.tries = 0;
      this.msg = msg;
    }
  }
  BlockingQueue<ToDoEntry> toDo = new LinkedBlockingQueue<ToDoEntry>();
  private Worker worker;
  private Thread workerThread;
  
  /** Thread that performs long running requests from the master */
  class Worker implements Runnable {
    void stop() {
      synchronized(toDo) {
        toDo.notifyAll();
      }
    }
    
    /** {@inheritDoc} */
    public void run() {
      try {
        for(ToDoEntry e = null; !stopRequested.get(); ) {
          try {
            e = toDo.poll(threadWakeFrequency, TimeUnit.MILLISECONDS);
          } catch (InterruptedException ex) {
            // continue
          }
          if(e == null || stopRequested.get()) {
            continue;
          }
          try {
            LOG.info(e.msg.toString());
            switch(e.msg.getMsg()) {

            case HMsg.MSG_REGION_OPEN:
              // Open a region
              openRegion(e.msg.getRegionInfo());
              break;

            case HMsg.MSG_REGION_CLOSE:
              // Close a region
              closeRegion(e.msg.getRegionInfo(), true);
              break;

            case HMsg.MSG_REGION_CLOSE_WITHOUT_REPORT:
              // Close a region, don't reply
              closeRegion(e.msg.getRegionInfo(), false);
              break;

            default:
              throw new AssertionError(
                  "Impossible state during msg processing.  Instruction: "
                  + e.msg.toString());
            }
          } catch (IOException ie) {
            ie = RemoteExceptionHandler.checkIOException(ie);
            if(e.tries < numRetries) {
              LOG.warn(ie);
              e.tries++;
              try {
                toDo.put(e);
              } catch (InterruptedException ex) {
                throw new RuntimeException("Putting into msgQueue was " +
                  "interrupted.", ex);
              }
            } else {
              LOG.error("unable to process message: " + e.msg.toString(), ie);
              if (!checkFileSystem()) {
                break;
              }
            }
          }
        }
      } catch(Throwable t) {
        LOG.fatal("Unhandled exception", t);
      } finally {
        LOG.info("worker thread exiting");
      }
    }
  }
  
  void openRegion(final HRegionInfo regionInfo) throws IOException {
    HRegion region = onlineRegions.get(regionInfo.getRegionName());
    if(region == null) {
      region = new HRegion(new Path(this.conf.get(HConstants.HBASE_DIR)),
        this.log, FileSystem.get(conf), conf, regionInfo, null);
      this.lock.writeLock().lock();
      try {
        this.log.setSequenceNumber(region.getMinSequenceId());
        this.onlineRegions.put(region.getRegionName(), region);
      } finally {
        this.lock.writeLock().unlock();
      }
    }
    reportOpen(region); 
  }

  void closeRegion(final HRegionInfo hri, final boolean reportWhenCompleted)
  throws IOException {  
    this.lock.writeLock().lock();
    HRegion region = null;
    try {
      region = onlineRegions.remove(hri.getRegionName());
    } finally {
      this.lock.writeLock().unlock();
    }
      
    if(region != null) {
      region.close();
      if(reportWhenCompleted) {
        reportClose(region);
      }
    }
  }

  /** Called either when the master tells us to restart or from stop() */
  ArrayList<HRegion> closeAllRegions() {
    ArrayList<HRegion> regionsToClose = new ArrayList<HRegion>();
    this.lock.writeLock().lock();
    try {
      regionsToClose.addAll(onlineRegions.values());
      onlineRegions.clear();
    } finally {
      this.lock.writeLock().unlock();
    }
    for(HRegion region: regionsToClose) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("closing region " + region.getRegionName());
      }
      try {
        region.close(abortRequested);
      } catch (IOException e) {
        LOG.error("error closing region " + region.getRegionName(),
          RemoteExceptionHandler.checkIOException(e));
      }
    }
    return regionsToClose;
  }

  //
  // HRegionInterface
  //

  /** {@inheritDoc} */
  public HRegionInfo getRegionInfo(final Text regionName)
    throws NotServingRegionException {
    
    requestCount.incrementAndGet();
    return getRegion(regionName).getRegionInfo();
  }

  /** {@inheritDoc} */
  public byte [] get(final Text regionName, final Text row,
      final Text column) throws IOException {

    checkOpen();
    requestCount.incrementAndGet();
    try {
      return getRegion(regionName).get(row, column);
      
    } catch (IOException e) {
      checkFileSystem();
      throw e;
    }
  }

  /** {@inheritDoc} */
  public byte [][] get(final Text regionName, final Text row,
      final Text column, final int numVersions) throws IOException {

    checkOpen();
    requestCount.incrementAndGet();
    try {
      return getRegion(regionName).get(row, column, numVersions);
      
    } catch (IOException e) {
      checkFileSystem();
      throw e;
    }
  }

  /** {@inheritDoc} */
  public byte [][] get(final Text regionName, final Text row, final Text column, 
      final long timestamp, final int numVersions) throws IOException {

    checkOpen();
    requestCount.incrementAndGet();
    try {
      return getRegion(regionName).get(row, column, timestamp, numVersions);
      
    } catch (IOException e) {
      checkFileSystem();
      throw e;
    }
  }

  /** {@inheritDoc} */
  public MapWritable getRow(final Text regionName, final Text row)
    throws IOException {

    checkOpen();
    requestCount.incrementAndGet();
    try {
      HRegion region = getRegion(regionName);
      MapWritable result = new MapWritable();
      TreeMap<Text, byte[]> map = region.getFull(row);
      for (Map.Entry<Text, byte []> es: map.entrySet()) {
        result.put(new HStoreKey(row, es.getKey()),
            new ImmutableBytesWritable(es.getValue()));
      }
      return result;
      
    } catch (IOException e) {
      checkFileSystem();
      throw e;
    }
  }

  /** {@inheritDoc} */
  public MapWritable next(final long scannerId) throws IOException {

    checkOpen();
    requestCount.incrementAndGet();
    try {
      String scannerName = String.valueOf(scannerId);
      HInternalScannerInterface s = scanners.get(scannerName);
      if (s == null) {
        throw new UnknownScannerException("Name: " + scannerName);
      }
      this.leases.renewLease(scannerId, scannerId);

      // Collect values to be returned here
      MapWritable values = new MapWritable();
      HStoreKey key = new HStoreKey();
      TreeMap<Text, byte []> results = new TreeMap<Text, byte []>();
      while (s.next(key, results)) {
        for(Map.Entry<Text, byte []> e: results.entrySet()) {
          values.put(new HStoreKey(key.getRow(), e.getKey(), key.getTimestamp()),
            new ImmutableBytesWritable(e.getValue()));
        }

        if(values.size() > 0) {
          // Row has something in it. Return the value.
          break;
        }

        // No data for this row, go get another.
        results.clear();
      }
      return values;
      
    } catch (IOException e) {
      checkFileSystem();
      throw e;
    }
  }

  /** {@inheritDoc} */
  public void batchUpdate(Text regionName, long timestamp, BatchUpdate b)
  throws IOException {
    checkOpen();
    this.requestCount.incrementAndGet();
    // If timestamp == LATEST_TIMESTAMP and we have deletes, then they need
    // special treatment.  For these we need to first find the latest cell so
    // when we write the delete, we write it with the latest cells' timestamp
    // so the delete record overshadows.  This means deletes and puts do not
    // happen within the same row lock.
    List<Text> deletes = null;
    try {
      long lockid = startUpdate(regionName, b.getRow());
      for (BatchOperation op: b) {
        switch(op.getOp()) {
        case PUT:
          put(regionName, lockid, op.getColumn(), op.getValue());
          break;

        case DELETE:
          if (timestamp == LATEST_TIMESTAMP) {
            // Save off these deletes.
            if (deletes == null) {
              deletes = new ArrayList<Text>();
            }
            deletes.add(op.getColumn());
          } else {
            delete(regionName, lockid, op.getColumn());
          }
          break;
        }
      }
      commit(regionName, lockid,
        (timestamp == LATEST_TIMESTAMP)? System.currentTimeMillis(): timestamp);
      
      if (deletes != null && deletes.size() > 0) {
        // We have some LATEST_TIMESTAMP deletes to run.
        HRegion r = getRegion(regionName);
        for (Text column: deletes) {
          r.deleteMultiple(b.getRow(), column, LATEST_TIMESTAMP, 1);
        }
      }
    } catch (IOException e) {
      checkFileSystem();
      throw e;
    }
  }
  
  //
  // remote scanner interface
  //

  /** {@inheritDoc} */
  public long openScanner(Text regionName, Text[] cols, Text firstRow,
      final long timestamp, final RowFilterInterface filter)
    throws IOException {
    checkOpen();
    requestCount.incrementAndGet();
    try {
      HRegion r = getRegion(regionName);
      long scannerId = -1L;
      HInternalScannerInterface s =
        r.getScanner(cols, firstRow, timestamp, filter);
      scannerId = rand.nextLong();
      String scannerName = String.valueOf(scannerId);
      synchronized(scanners) {
        scanners.put(scannerName, s);
      }
      this.leases.
        createLease(scannerId, scannerId, new ScannerListener(scannerName));
      return scannerId;
    } catch (IOException e) {
      LOG.error("Error opening scanner (fsOk: " + this.fsOk + ")",
          RemoteExceptionHandler.checkIOException(e));
      checkFileSystem();
      throw e;
    }
  }
  
  /** {@inheritDoc} */
  public void close(final long scannerId) throws IOException {
    checkOpen();
    requestCount.incrementAndGet();
    try {
      String scannerName = String.valueOf(scannerId);
      HInternalScannerInterface s = null;
      synchronized(scanners) {
        s = scanners.remove(scannerName);
      }
      if(s == null) {
        throw new UnknownScannerException(scannerName);
      }
      s.close();
      this.leases.cancelLease(scannerId, scannerId);
    } catch (IOException e) {
      checkFileSystem();
      throw e;
    }
  }

  Map<String, HInternalScannerInterface> scanners =
    Collections.synchronizedMap(new HashMap<String,
      HInternalScannerInterface>());

  /** 
   * Instantiated as a scanner lease.
   * If the lease times out, the scanner is closed
   */
  private class ScannerListener implements LeaseListener {
    private final String scannerName;
    
    ScannerListener(final String n) {
      this.scannerName = n;
    }
    
    /** {@inheritDoc} */
    public void leaseExpired() {
      LOG.info("Scanner " + this.scannerName + " lease expired");
      HInternalScannerInterface s = null;
      synchronized(scanners) {
        s = scanners.remove(this.scannerName);
      }
      if (s != null) {
        try {
          s.close();
        } catch (IOException e) {
          LOG.error("Closing scanner", e);
        }
      }
    }
  }
  
  //
  // Methods that do the actual work for the remote API
  //
  
  protected long startUpdate(Text regionName, Text row) throws IOException {
    HRegion region = getRegion(regionName);
    return region.startUpdate(row);
  }

  protected void put(final Text regionName, final long lockid,
      final Text column, final byte [] val)
  throws IOException {
    HRegion region = getRegion(regionName, true);
    region.put(lockid, column, val);
  }

  protected void delete(Text regionName, long lockid, Text column) 
  throws IOException {
    HRegion region = getRegion(regionName);
    region.delete(lockid, column);
  }
  
  /** {@inheritDoc} */
  public void deleteAll(final Text regionName, final Text row,
      final Text column, final long timestamp) 
  throws IOException {
    HRegion region = getRegion(regionName);
    region.deleteAll(row, column, timestamp);
  }

  protected void commit(Text regionName, final long lockid,
      final long timestamp) throws IOException {

    HRegion region = getRegion(regionName, true);
    region.commit(lockid, timestamp);
  }

  /**
   * @return Info on this server.
   */
  public HServerInfo getServerInfo() {
    return this.serverInfo;
  }

  /**
   * @return Immutable list of this servers regions.
   */
  public SortedMap<Text, HRegion> getOnlineRegions() {
    return Collections.unmodifiableSortedMap(this.onlineRegions);
  }

  /** @return the request count */
  public AtomicInteger getRequestCount() {
    return this.requestCount;
  }
  
  /** 
   * Protected utility method for safely obtaining an HRegion handle.
   * @param regionName Name of online {@link HRegion} to return
   * @return {@link HRegion} for <code>regionName</code>
   * @throws NotServingRegionException
   */
  protected HRegion getRegion(final Text regionName)
  throws NotServingRegionException {
    return getRegion(regionName, false);
  }
  
  /** 
   * Protected utility method for safely obtaining an HRegion handle.
   * @param regionName Name of online {@link HRegion} to return
   * @param checkRetiringRegions Set true if we're to check retiring regions
   * as well as online regions.
   * @return {@link HRegion} for <code>regionName</code>
   * @throws NotServingRegionException
   */
  protected HRegion getRegion(final Text regionName,
      final boolean checkRetiringRegions)
  throws NotServingRegionException {
    HRegion region = null;
    this.lock.readLock().lock();
    try {
      region = onlineRegions.get(regionName);
      if (region == null && checkRetiringRegions) {
        region = this.retiringRegions.get(regionName);
        if (LOG.isDebugEnabled()) {
          if (region != null) {
            LOG.debug("Found region " + regionName + " in retiringRegions");
          }
        }
      }

      if (region == null) {
        throw new NotServingRegionException(regionName.toString());
      }
      
      return region;
    } finally {
      this.lock.readLock().unlock();
    }
  }

  /**
   * Called to verify that this server is up and running.
   * 
   * @throws IOException
   */
  private void checkOpen() throws IOException {
    if (this.stopRequested.get() || this.abortRequested) {
      throw new IOException("Server not running");
    }
    if (!fsOk) {
      throw new IOException("File system not available");
    }
  }
  
  /**
   * Checks to see if the file system is still accessible.
   * If not, sets abortRequested and stopRequested
   * 
   * @return false if file system is not available
   */
  protected boolean checkFileSystem() {
    if (this.fsOk) {
      FileSystem fs = null;
      try {
        fs = FileSystem.get(this.conf);
        if (fs != null && !FSUtils.isFileSystemAvailable(fs)) {
          LOG.fatal("Shutting down HRegionServer: file system not available");
          this.abortRequested = true;
          this.stopRequested.set(true);
          fsOk = false;
        }
      } catch (Exception e) {
        LOG.error("Failed get of filesystem", e);
        LOG.fatal("Shutting down HRegionServer: file system not available");
        this.abortRequested = true;
        this.stopRequested.set(true);
        fsOk = false;
      }
    }
    return this.fsOk;
  }
 
  /**
   * @return Returns list of non-closed regions hosted on this server.  If no
   * regions to check, returns an empty list.
   */
  protected List<HRegion> getRegionsToCheck() {
    ArrayList<HRegion> regionsToCheck = new ArrayList<HRegion>();
    lock.readLock().lock();
    try {
      regionsToCheck.addAll(this.onlineRegions.values());
    } finally {
      lock.readLock().unlock();
    }
    // Purge closed regions.
    for (final ListIterator<HRegion> i = regionsToCheck.listIterator();
        i.hasNext();) {
      HRegion r = i.next();
      if (r.isClosed()) {
        i.remove();
      }
    }
    return regionsToCheck;
  }

  /** {@inheritDoc} */
  public long getProtocolVersion(final String protocol, 
      @SuppressWarnings("unused") final long clientVersion)
  throws IOException {  
    if (protocol.equals(HRegionInterface.class.getName())) {
      return HRegionInterface.versionID;
    }
    throw new IOException("Unknown protocol to name node: " + protocol);
  }

  //
  // Main program and support routines
  //
  
  private static void printUsageAndExit() {
    printUsageAndExit(null);
  }
  
  private static void printUsageAndExit(final String message) {
    if (message != null) {
      System.err.println(message);
    }
    System.err.println("Usage: java " +
        "org.apache.hbase.HRegionServer [--bind=hostname:port] start");
    System.exit(0);
  }
  
  /**
   * Do class main.
   * @param args
   * @param regionServerClass HRegionServer to instantiate.
   */
  protected static void doMain(final String [] args,
      final Class<? extends HRegionServer> regionServerClass) {
    if (args.length < 1) {
      printUsageAndExit();
    }
    Configuration conf = new HBaseConfiguration();
    
    // Process command-line args. TODO: Better cmd-line processing
    // (but hopefully something not as painful as cli options).
    final String addressArgKey = "--bind=";
    for (String cmd: args) {
      if (cmd.startsWith(addressArgKey)) {
        conf.set(REGIONSERVER_ADDRESS, cmd.substring(addressArgKey.length()));
        continue;
      }
      
      if (cmd.equals("start")) {
        try {
          // If 'local', don't start a region server here.  Defer to
          // LocalHBaseCluster.  It manages 'local' clusters.
          if (LocalHBaseCluster.isLocal(conf)) {
            LOG.warn("Not starting a distinct region server because " +
              "hbase.master is set to 'local' mode");
          } else {
            Constructor<? extends HRegionServer> c =
              regionServerClass.getConstructor(Configuration.class);
            HRegionServer hrs = c.newInstance(conf);
            Thread t = new Thread(hrs);
            t.setName("regionserver" + hrs.server.getListenerAddress());
            t.start();
          }
        } catch (Throwable t) {
          LOG.error( "Can not start region server because "+
              StringUtils.stringifyException(t) );
          System.exit(-1);
        }
        break;
      }
      
      if (cmd.equals("stop")) {
        printUsageAndExit("There is no regionserver stop mechanism. To stop " +
          "regionservers, shutdown the hbase master");
      }
      
      // Print out usage if we get to here.
      printUsageAndExit();
    }
  }
  
  /**
   * @param args
   */
  public static void main(String [] args) {
    doMain(args, HRegionServer.class);
  }
}
