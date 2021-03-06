// Copyright 2014 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.util;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;

import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.AllocationFileLoaderService;
import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.cloudera.impala.common.ByteUnits;
import com.cloudera.impala.thrift.TPoolConfigResult;
import com.google.common.io.Files;

/**
 * Unit tests for the user to pool resolution, authorization, and getting configuration
 * parameters via {@link RequestPoolService}. Sets a configuration file and ensures the
 * appropriate user to pool resolution, authentication, and pool configs are returned.
 * This also tests that updating the files after startup causes them to be reloaded and
 * the updated values are returned.
 * TODO: Move tests to C++ to test the API that's actually used.
 */
public class TestRequestPoolService {
  // Pool definitions and includes memory resource limits, copied to a temporary file
  private static final String ALLOCATION_FILE = "fair-scheduler-test.xml";

  // A second allocation file which overwrites the temporary file to check for changes.
  private static final String ALLOCATION_FILE_MODIFIED = "fair-scheduler-test2.xml";
  private static final String ALLOCATION_FILE_EMPTY = "fair-scheduler-empty.xml";

  // Contains per-pool configurations for maximum number of running queries and queued
  // requests.
  private static final String LLAMA_CONFIG_FILE = "llama-site-test.xml";

  // A second Llama config which overwrites the temporary file to check for changes.
  private static final String LLAMA_CONFIG_FILE_MODIFIED = "llama-site-test2.xml";
  private static final String LLAMA_CONFIG_FILE_EMPTY = "llama-site-empty.xml";

  // Set the file check interval to something short so we don't have to wait long after
  // changing the file.
  private static final long CHECK_INTERVAL_MS = 100L;

  // Temp folder where the config files are copied so we can modify them in place.
  // The JUnit @Rule creates and removes the temp folder between every test.
  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private RequestPoolService poolService_;
  private File allocationConfFile_;
  private File llamaConfFile_;

  void createPoolService(String allocationFilename, String llamaConfFilename)
      throws Exception {
    allocationConfFile_ = tempFolder.newFile("fair-scheduler-temp-file.xml");
    Files.copy(getClasspathFile(allocationFilename), allocationConfFile_);
    llamaConfFile_ = tempFolder.newFile("llama-conf-temp-file.xml");
    Files.copy(getClasspathFile(llamaConfFilename), llamaConfFile_);
    poolService_ = new RequestPoolService(allocationConfFile_.getAbsolutePath(),
        llamaConfFile_.getAbsolutePath());

    // Lower the wait times on the AllocationFileLoaderService and RequestPoolService so
    // the test doesn't have to wait very long to test that file changes are reloaded.
    Field f = AllocationFileLoaderService.class.getDeclaredField("reloadIntervalMs");
    f.setAccessible(true);
    f.set(poolService_.allocLoader_, CHECK_INTERVAL_MS);
    poolService_.llamaConfWatcher_.setCheckIntervalMs(CHECK_INTERVAL_MS);
    poolService_.start();
  }

  @After
  public void cleanUp() throws Exception {
    if (poolService_ != null) poolService_.stop();
  }

  /**
   * Returns a {@link File} for the file on the classpath.
   */
  private static File getClasspathFile(String filename) {
    return new File(
        Thread.currentThread().getContextClassLoader().getResource(filename).getPath());
  }

  @Test
  public void testPoolResolution() throws Exception {
    createPoolService(ALLOCATION_FILE, LLAMA_CONFIG_FILE);
    Assert.assertEquals("root.queueA", poolService_.assignToPool("root.queueA", "userA"));
    Assert.assertNull(poolService_.assignToPool("queueC", "userA"));
  }

  @Test
  public void testPoolAcls() throws Exception {
    createPoolService(ALLOCATION_FILE, LLAMA_CONFIG_FILE);
    Assert.assertTrue(poolService_.hasAccess("root.queueA", "userA"));
    Assert.assertTrue(poolService_.hasAccess("root.queueB", "userB"));
    Assert.assertFalse(poolService_.hasAccess("root.queueB", "userA"));
    Assert.assertTrue(poolService_.hasAccess("root.queueB", "root"));
  }

  @Test
  public void testPoolLimitConfigs() throws Exception {
    createPoolService(ALLOCATION_FILE, LLAMA_CONFIG_FILE);
    checkPoolConfigResult("root", 15, 50, -1);
    checkPoolConfigResult("root.queueA", 10, 30, 1024 * ByteUnits.MEGABYTE);
    checkPoolConfigResult("root.queueB", 5, 10, -1);
  }

  @Test
  public void testDefaultConfigs() throws Exception {
    createPoolService(ALLOCATION_FILE_EMPTY, LLAMA_CONFIG_FILE_EMPTY);
    Assert.assertEquals("root.userA", poolService_.assignToPool("", "userA"));
    Assert.assertTrue(poolService_.hasAccess("root.userA", "userA"));
    checkPoolConfigResult("root", 20, 50, -1);
  }

  @Test
  public void testUpdatingConfigs() throws Exception {
    // Tests updating the config files and then checking the pool resolution, ACLs, and
    // pool limit configs. This tests all three together rather than separating into
    // separate test cases because we updateConfigFiles() will end up waiting around 7
    // seconds, so this helps cut down on the total test execution time.
    // A one second pause is necessary to ensure the file timestamps are unique if the
    // test gets here within one second.
    createPoolService(ALLOCATION_FILE, LLAMA_CONFIG_FILE);
    Thread.sleep(1000L);
    Files.copy(getClasspathFile(ALLOCATION_FILE_MODIFIED), allocationConfFile_);
    Files.copy(getClasspathFile(LLAMA_CONFIG_FILE_MODIFIED), llamaConfFile_);
    // Wait at least 1 second more than the time it will take for the
    // AllocationFileLoaderService to update the file. The FileWatchService does not
    // have that additional wait time, so it will be updated within 'CHECK_INTERVAL_MS'
    Thread.sleep(1000L + CHECK_INTERVAL_MS +
        AllocationFileLoaderService.ALLOC_RELOAD_WAIT_MS);
    checkModifiedConfigResults();
  }

  @Test
  public void testModifiedConfigs() throws Exception {
    // Tests the results are the same as testUpdatingConfigs() as when we create the
    // pool service with the same modified configs initially (i.e. not updating).
    createPoolService(ALLOCATION_FILE_MODIFIED, LLAMA_CONFIG_FILE_MODIFIED);
    checkModifiedConfigResults();
  }

  private void checkModifiedConfigResults() throws IOException {
    // Test pool resolution: now there's a queueC
    Assert.assertEquals("root.queueA", poolService_.assignToPool("queueA", "userA"));
    Assert.assertNull(poolService_.assignToPool("queueX", "userA"));
    Assert.assertEquals("root.queueC", poolService_.assignToPool("queueC", "userA"));

    // Test pool ACL changes
    Assert.assertTrue(poolService_.hasAccess("root.queueA", "userA"));
    Assert.assertTrue(poolService_.hasAccess("root.queueB", "userB"));
    Assert.assertTrue(poolService_.hasAccess("root.queueB", "userA"));
    Assert.assertFalse(poolService_.hasAccess("root.queueC", "userA"));
    Assert.assertTrue(poolService_.hasAccess("root.queueC", "root"));

    // Test pool limit changes
    checkPoolConfigResult("root", 15, 100, -1);
    checkPoolConfigResult("root.queueA", 10, 30, 100000 * ByteUnits.MEGABYTE);
    checkPoolConfigResult("root.queueB", 5, 10, -1);
    checkPoolConfigResult("root.queueC", 10, 30, 128 * ByteUnits.MEGABYTE);
  }

  /**
   * Helper method to verify the per-pool limits.
   */
  private void checkPoolConfigResult(String pool, long expectedMaxRequests,
      long expectedMaxQueued, long expectedMaxMemUsage) {
    TPoolConfigResult expectedResult = new TPoolConfigResult();
    expectedResult.setMax_requests(expectedMaxRequests);
    expectedResult.setMax_queued(expectedMaxQueued);
    expectedResult.setMem_limit(expectedMaxMemUsage);
    Assert.assertEquals("Unexpected config values for pool " + pool,
        expectedResult, poolService_.getPoolConfig(pool));
  }
}
