/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */

package gobblin.runtime.locks;

import java.io.IOException;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.BasicConfigurator;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import gobblin.configuration.ConfigurationKeys;


/**
 * Unit test for {@link FileBasedJobLock}.
 *
 * @author Yinan Li
 */
@Test(groups = {"gobblin.runtime"})
public class FileBasedJobLockTest extends JobLockTest {

  private FileSystem fs;
  private Path path;

  @BeforeClass
  public void setUp() throws IOException {
    BasicConfigurator.configure();
    this.fs = FileSystem.getLocal(new Configuration());
    this.path = new Path("MRJobLockTest");
    if (!this.fs.exists(this.path)) {
      this.fs.mkdirs(this.path);
    }
  }

  @Override
  protected JobLock getJobLock() throws JobLockException {
    Properties properties = new Properties();
    properties.setProperty(FileBasedJobLock.JOB_LOCK_DIR, this.path.getName());
    properties.setProperty(ConfigurationKeys.JOB_NAME_KEY, "FileBasedJobLockTest-" + System.currentTimeMillis());
    return new FileBasedJobLock(properties);
  }

  @AfterClass
  public void tearDown() throws IOException {
    if (this.fs.exists(this.path)) {
      this.fs.delete(this.path, true);
    }
  }
}
