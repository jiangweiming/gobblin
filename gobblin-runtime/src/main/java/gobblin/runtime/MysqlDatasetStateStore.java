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

package gobblin.runtime;

import com.google.common.base.CharMatcher;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import gobblin.annotation.Alias;
import gobblin.configuration.ConfigurationKeys;
import gobblin.metastore.DatasetStateStore;
import gobblin.metastore.MysqlStateStore;
import gobblin.password.PasswordManager;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import org.apache.commons.dbcp.BasicDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * A custom extension to {@link MysqlStateStore} for storing and reading {@link JobState.DatasetState}s.
 *
 * <p>
 *   The purpose of having this class is to hide some implementation details that are unnecessarily
 *   exposed if using the {@link MysqlStateStore} to store and serve job/dataset states between job runs.
 * </p>
 *
 * <p>
 *   In addition to persisting and reading {@link JobState.DatasetState}s. This class is also able to
 *   read job state files of existing jobs that store serialized instances of {@link JobState} for
 *   backward compatibility.
 * </p>
 *
 */
public class MysqlDatasetStateStore extends MysqlStateStore<JobState.DatasetState>
    implements DatasetStateStore<JobState.DatasetState> {

  private static final Logger LOGGER = LoggerFactory.getLogger(MysqlDatasetStateStore.class);

  @Alias("mysql")
  public static class Factory implements DatasetStateStore.Factory {
    @Override
    public DatasetStateStore<JobState.DatasetState> createStateStore(Properties props) {
      BasicDataSource basicDataSource = new BasicDataSource();
      PasswordManager passwordManager = PasswordManager.getInstance(props);

      basicDataSource.setDriverClassName(props.getProperty(ConfigurationKeys.STATE_STORE_DB_JDBC_DRIVER_KEY,
          ConfigurationKeys.DEFAULT_STATE_STORE_DB_JDBC_DRIVER));
      // MySQL server can timeout a connection so need to validate connections before use
      basicDataSource.setValidationQuery("select 1");
      basicDataSource.setTestOnBorrow(true);
      basicDataSource.setDefaultAutoCommit(false);
      basicDataSource.setTimeBetweenEvictionRunsMillis(60000);
      basicDataSource.setUrl(props.getProperty(ConfigurationKeys.STATE_STORE_DB_URL_KEY));
      basicDataSource.setUsername(passwordManager.readPassword(
          props.getProperty(ConfigurationKeys.STATE_STORE_DB_USER_KEY)));
      basicDataSource.setPassword(passwordManager.readPassword(
          props.getProperty(ConfigurationKeys.STATE_STORE_DB_PASSWORD_KEY)));
      basicDataSource.setMinEvictableIdleTimeMillis(Long.parseLong(props.getProperty(
          ConfigurationKeys.STATE_STORE_DB_CONN_MIN_EVICTABLE_IDLE_TIME_KEY,
          Long.toString(ConfigurationKeys.DEFAULT_STATE_STORE_DB_CONN_MIN_EVICTABLE_IDLE_TIME))));

      String stateStoreTableName = props.getProperty(ConfigurationKeys.STATE_STORE_DB_TABLE_KEY,
          ConfigurationKeys.DEFAULT_STATE_STORE_DB_TABLE);
      boolean compressedValues =
          Boolean.parseBoolean(props.getProperty(ConfigurationKeys.STATE_STORE_DB_COMPRESSED_VALUES_KEY,
          Boolean.toString(ConfigurationKeys.DEFAULT_STATE_STORE_DB_COMPRESSED_VALUES)));

      try {
        return new MysqlDatasetStateStore(basicDataSource, stateStoreTableName, compressedValues);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  public MysqlDatasetStateStore(DataSource dataSource, String stateStoreTableName, boolean compressedValues)
      throws IOException {
    super(dataSource, stateStoreTableName, compressedValues, JobState.DatasetState.class);
  }

  /**
   * Get a {@link Map} from dataset URNs to the latest {@link JobState.DatasetState}s.
   *
   * @param jobName the job name
   * @return a {@link Map} from dataset URNs to the latest {@link JobState.DatasetState}s
   * @throws IOException if there's something wrong reading the {@link JobState.DatasetState}s
   */
  public Map<String, JobState.DatasetState> getLatestDatasetStatesByUrns(String jobName) throws IOException {
    List<JobState.DatasetState> previousDatasetStates =
        getAll(jobName, "%" + CURRENT_DATASET_STATE_FILE_SUFFIX + DATASET_STATE_STORE_TABLE_SUFFIX, true);

    Map<String, JobState.DatasetState> datasetStatesByUrns = Maps.newHashMap();
    if (!previousDatasetStates.isEmpty()) {
      JobState.DatasetState previousDatasetState = previousDatasetStates.get(0);
      datasetStatesByUrns.put(previousDatasetState.getDatasetUrn(), previousDatasetState);
    }

    // The dataset (job) state from the deprecated "current.jst" will be read even though
    // the job has transitioned to the new dataset-based mechanism
    if (datasetStatesByUrns.size() > 1) {
      datasetStatesByUrns.remove(ConfigurationKeys.DEFAULT_DATASET_URN);
    }

    return datasetStatesByUrns;
  }

  /**
   * Get the latest {@link JobState.DatasetState} of a given dataset.
   *
   * @param storeName the name of the dataset state store
   * @param datasetUrn the dataset URN
   * @return the latest {@link JobState.DatasetState} of the dataset or {@link null} if it is not found
   * @throws IOException
   */
  public JobState.DatasetState getLatestDatasetState(String storeName, String datasetUrn) throws IOException {
    String alias =
        Strings.isNullOrEmpty(datasetUrn) ? CURRENT_DATASET_STATE_FILE_SUFFIX + DATASET_STATE_STORE_TABLE_SUFFIX
            : datasetUrn + "-" + CURRENT_DATASET_STATE_FILE_SUFFIX + DATASET_STATE_STORE_TABLE_SUFFIX;
    return get(storeName, alias, datasetUrn);
  }

  /**
   * Persist a given {@link JobState.DatasetState}.
   *
   * @param datasetUrn the dataset URN
   * @param datasetState the {@link JobState.DatasetState} to persist
   * @throws IOException if there's something wrong persisting the {@link JobState.DatasetState}
   */
  public void persistDatasetState(String datasetUrn, JobState.DatasetState datasetState) throws IOException {
    String jobName = datasetState.getJobName();
    String jobId = datasetState.getJobId();

    datasetUrn = CharMatcher.is(':').replaceFrom(datasetUrn, '.');
    String tableName = Strings.isNullOrEmpty(datasetUrn) ? jobId + DATASET_STATE_STORE_TABLE_SUFFIX
        : datasetUrn + "-" + jobId + DATASET_STATE_STORE_TABLE_SUFFIX;
    LOGGER.info("Persisting " + tableName + " to the job state store");

    put(jobName, tableName, datasetState);
    createAlias(jobName, tableName, getAliasName(datasetUrn));
  }

  private static String getAliasName(String datasetUrn) {
    return Strings.isNullOrEmpty(datasetUrn) ? CURRENT_DATASET_STATE_FILE_SUFFIX + DATASET_STATE_STORE_TABLE_SUFFIX
        : datasetUrn + "-" + CURRENT_DATASET_STATE_FILE_SUFFIX + DATASET_STATE_STORE_TABLE_SUFFIX;
  }
}
