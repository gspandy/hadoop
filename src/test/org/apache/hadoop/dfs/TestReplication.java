/**
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
package org.apache.hadoop.dfs;

import junit.framework.TestCase;
import java.io.*;
import java.util.Iterator;
import java.util.Random;
import java.net.*;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.dfs.FSConstants.DatanodeReportType;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.ipc.RPC;

/**
 * This class tests the replication of a DFS file.
 */
public class TestReplication extends TestCase {
  private static final long seed = 0xDEADBEEFL;
  private static final int blockSize = 8192;
  private static final int fileSize = 16384;
  private static final String racks[] = new String[] {
    "/d1/r1", "/d1/r1", "/d1/r2", "/d1/r2", "/d1/r2", "/d2/r3", "/d2/r3"
  };
  private static final int numDatanodes = racks.length;
  private static final Log LOG = LogFactory.getLog(
                                                   "org.apache.hadoop.dfs.TestReplication");

  private void writeFile(FileSystem fileSys, Path name, int repl)
    throws IOException {
    // create and write a file that contains three blocks of data
    FSDataOutputStream stm = fileSys.create(name, true,
                                            fileSys.getConf().getInt("io.file.buffer.size", 4096),
                                            (short)repl, (long)blockSize);
    byte[] buffer = new byte[fileSize];
    Random rand = new Random(seed);
    rand.nextBytes(buffer);
    stm.write(buffer);
    stm.close();
  }
  
  /* check if there are at least two nodes are on the same rack */
  private void checkFile(FileSystem fileSys, Path name, int repl)
    throws IOException {
    Configuration conf = fileSys.getConf();
    ClientProtocol namenode = (ClientProtocol) RPC.getProxy(
                                                            ClientProtocol.class,
                                                            ClientProtocol.versionID,
                                                            DataNode.createSocketAddr(conf.get("fs.default.name")), 
                                                            conf);
      
    LocatedBlocks locations;
    boolean isReplicationDone;
    do {
      locations = namenode.getBlockLocations(name.toString(),0,Long.MAX_VALUE);
      isReplicationDone = true;
      for (LocatedBlock blk : locations.getLocatedBlocks()) {
        DatanodeInfo[] datanodes = blk.getLocations();
        if (Math.min(numDatanodes, repl) != datanodes.length) {
          isReplicationDone=false;
          LOG.warn("File has "+datanodes.length+" replicas, expecting "
                   +Math.min(numDatanodes, repl));
          try {
            Thread.sleep(15000L);
          } catch (InterruptedException e) {
            // nothing
          }
          break;
        }
      }
    } while(!isReplicationDone);
      
    boolean isOnSameRack = true, isNotOnSameRack = true;
    for (LocatedBlock blk : locations.getLocatedBlocks()) {
      DatanodeInfo[] datanodes = blk.getLocations();
      if (datanodes.length <= 1) break;
      if (datanodes.length == 2) {
        isNotOnSameRack = !(datanodes[0].getNetworkLocation().equals(
                                                                     datanodes[1].getNetworkLocation()));
        break;
      }
      isOnSameRack = false;
      isNotOnSameRack = false;
      for (int i = 0; i < datanodes.length-1; i++) {
        LOG.info("datanode "+ i + ": "+ datanodes[i].getName());
        boolean onRack = false;
        for( int j=i+1; j<datanodes.length; j++) {
           if( datanodes[i].getNetworkLocation().equals(
            datanodes[j].getNetworkLocation()) ) {
             onRack = true;
           }
        }
        if (onRack) {
          isOnSameRack = true;
        }
        if (!onRack) {
          isNotOnSameRack = true;                      
        }
        if (isOnSameRack && isNotOnSameRack) break;
      }
      if (!isOnSameRack || !isNotOnSameRack) break;
    }
    assertTrue(isOnSameRack);
    assertTrue(isNotOnSameRack);
  }
  
  private void cleanupFile(FileSystem fileSys, Path name) throws IOException {
    assertTrue(fileSys.exists(name));
    fileSys.delete(name);
    assertTrue(!fileSys.exists(name));
  }
  
  /**
   * Tests replication in DFS.
   */
  public void testReplication() throws IOException {
    Configuration conf = new Configuration();
    conf.setBoolean("dfs.replication.considerLoad", false);
    MiniDFSCluster cluster = new MiniDFSCluster(conf, numDatanodes, true, racks);
    cluster.waitActive();
    
    InetSocketAddress addr = new InetSocketAddress("localhost",
                                                   cluster.getNameNodePort());
    DFSClient client = new DFSClient(addr, conf);
    
    DatanodeInfo[] info = client.datanodeReport(DatanodeReportType.LIVE);
    assertEquals("Number of Datanodes ", numDatanodes, info.length);
    FileSystem fileSys = cluster.getFileSystem();
    try {
      Path file1 = new Path("/smallblocktest.dat");
      writeFile(fileSys, file1, 3);
      checkFile(fileSys, file1, 3);
      cleanupFile(fileSys, file1);
      writeFile(fileSys, file1, 10);
      checkFile(fileSys, file1, 10);
      cleanupFile(fileSys, file1);
      writeFile(fileSys, file1, 4);
      checkFile(fileSys, file1, 4);
      cleanupFile(fileSys, file1);
      writeFile(fileSys, file1, 1);
      checkFile(fileSys, file1, 1);
      cleanupFile(fileSys, file1);
      writeFile(fileSys, file1, 2);
      checkFile(fileSys, file1, 2);
      cleanupFile(fileSys, file1);
    } finally {
      fileSys.close();
      cluster.shutdown();
    }
  }
  
  // Waits for all of the blocks to have expected replication
  private void waitForBlockReplication(String filename, 
                                       ClientProtocol namenode,
                                       int expected, long maxWaitSec) 
                                       throws IOException {
    long start = System.currentTimeMillis();
    
    //wait for all the blocks to be replicated;
    System.out.println("Checking for block replication for " + filename);
    int iters = 0;
    while (true) {
      boolean replOk = true;
      LocatedBlocks blocks = namenode.getBlockLocations(filename, 0, 
                                                        Long.MAX_VALUE);
      
      for (Iterator<LocatedBlock> iter = blocks.getLocatedBlocks().iterator();
           iter.hasNext();) {
        LocatedBlock block = iter.next();
        int actual = block.getLocations().length;
        if ( actual < expected ) {
          if (true || iters > 0) {
            System.out.println("Not enough replicas for " + block.getBlock() +
                               " yet. Expecting " + expected + ", got " + 
                               actual + ".");
          }
          replOk = false;
          break;
        }
      }
      
      if (replOk) {
        return;
      }
      
      iters++;
      
      if (maxWaitSec > 0 && 
          (System.currentTimeMillis() - start) > (maxWaitSec * 1000)) {
        throw new IOException("Timedout while waiting for all blocks to " +
                              " be replicated for " + filename);
      }
      
      try {
        Thread.sleep(500);
      } catch (InterruptedException ignored) {}
    }
  }
  
  /* This test makes sure that NameNode retries all the available blocks 
   * for under replicated blocks. 
   * 
   * It creates a file with one block and replication of 4. It corrupts 
   * two of the blocks and removes one of the replicas. Expected behaviour is
   * that missing replica will be copied from one valid source.
   */
  public void testPendingReplicationRetry() throws IOException {
    
    MiniDFSCluster cluster = null;
    int numDataNodes = 4;
    String testFile = "/replication-test-file";
    Path testPath = new Path(testFile);
    
    byte buffer[] = new byte[1024];
    for (int i=0; i<buffer.length; i++) {
      buffer[i] = '1';
    }
    
    try {
      Configuration conf = new Configuration();
      conf.set("dfs.replication", Integer.toString(numDataNodes));
      //first time format
      cluster = new MiniDFSCluster(0, conf, numDataNodes, true,
                                   true, null, null);
      cluster.waitActive();
      DFSClient dfsClient = new DFSClient(new InetSocketAddress("localhost",
                                            cluster.getNameNodePort()),
                                            conf);
      
      OutputStream out = cluster.getFileSystem().create(testPath);
      out.write(buffer);
      out.close();
      
      waitForBlockReplication(testFile, dfsClient.namenode, numDataNodes, -1);

      // get first block of the file.
      String block = dfsClient.namenode.
                       getBlockLocations(testFile, 0, Long.MAX_VALUE).
                       get(0).getBlock().toString();
      
      cluster.shutdown();
      cluster = null;
      
      //Now mess up some of the replicas.
      //Delete the first and corrupt the next two.
      File baseDir = new File(System.getProperty("test.build.data"), 
                                                 "dfs/data");
      for (int i=0; i<25; i++) {
        buffer[i] = '0';
      }
      
      int fileCount = 0;
      for (int i=0; i<6; i++) {
        File blockFile = new File(baseDir, "data" + (i+1) + "/current/" + block);
        System.out.println("Checking for file " + blockFile);
        
        if (blockFile.exists()) {
          if (fileCount == 0) {
            assertTrue(blockFile.delete());
          } else {
            // corrupt it.
            long len = blockFile.length();
            assertTrue(len > 50);
            RandomAccessFile blockOut = new RandomAccessFile(blockFile, "rw");
            blockOut.seek(len/3);
            blockOut.write(buffer, 0, 25);
          }
          fileCount++;
        }
      }
      assertEquals(3, fileCount);
      
      /* Start the MiniDFSCluster with more datanodes since once a writeBlock
       * to a datanode node fails, same block can not be written to it
       * immediately. In our case some replication attempts will fail.
       */
      conf = new Configuration();
      conf.set("dfs.replication", Integer.toString(numDataNodes));
      conf.set("dfs.replication.pending.timeout.sec", Integer.toString(2));
      conf.set("dfs.datanode.block.write.timeout.sec", Integer.toString(5));
      conf.set("dfs.safemode.threshold.pct", "0.75f"); // only 3 copies exist
      
      cluster = new MiniDFSCluster(0, conf, numDataNodes*2, false,
                                   true, null, null);
      cluster.waitActive();
      
      dfsClient = new DFSClient(new InetSocketAddress("localhost",
                                  cluster.getNameNodePort()),
                                  conf);
      
      waitForBlockReplication(testFile, dfsClient.namenode, numDataNodes, -1);
      
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }  
}
