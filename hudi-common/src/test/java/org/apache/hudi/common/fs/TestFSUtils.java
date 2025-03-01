/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.common.fs;

import org.apache.hudi.common.config.SerializableConfiguration;
import org.apache.hudi.common.engine.HoodieLocalEngineContext;
import org.apache.hudi.common.model.HoodieLogFile;
import org.apache.hudi.common.table.HoodieTableConfig;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.cdc.HoodieCDCUtils;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.testutils.HoodieCommonTestHarness;
import org.apache.hudi.common.testutils.HoodieTestUtils;
import org.apache.hudi.common.util.CollectionUtils;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.exception.HoodieIOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.Rule;
import org.junit.contrib.java.lang.system.EnvironmentVariables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.hudi.common.model.HoodieFileFormat.HOODIE_LOG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests file system utils.
 */
public class TestFSUtils extends HoodieCommonTestHarness {

  private static final String TEST_WRITE_TOKEN = "1-0-1";
  private static final String BASE_FILE_EXTENSION = HoodieTableConfig.BASE_FILE_FORMAT.defaultValue().getFileExtension();

  @Rule
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables();

  @BeforeEach
  public void setUp() throws IOException {
    initMetaClient();
  }

  @AfterEach
  public void tearDown() throws Exception {
    cleanMetaClient();
  }

  @Test
  public void testMakeDataFileName() {
    String instantTime = HoodieActiveTimeline.formatDate(new Date());
    String fileName = UUID.randomUUID().toString();
    assertEquals(FSUtils.makeBaseFileName(instantTime, TEST_WRITE_TOKEN, fileName), fileName + "_" + TEST_WRITE_TOKEN + "_" + instantTime + BASE_FILE_EXTENSION);
  }

  @Test
  public void testMaskFileName() {
    String instantTime = HoodieActiveTimeline.formatDate(new Date());
    int taskPartitionId = 2;
    assertEquals(FSUtils.maskWithoutFileId(instantTime, taskPartitionId), "*_" + taskPartitionId + "_" + instantTime + BASE_FILE_EXTENSION);
  }

  /**
   * Tests if process Files return only paths excluding marker directories Cleaner, Rollback and compaction-scheduling
   * logic was recursively processing all subfolders including that of ".hoodie" when looking for partition-paths. This
   * causes a race when they try to list all folders (recursively) but the marker directory (that of compaction inside
   * of ".hoodie" folder) is deleted underneath by compactor. This code tests the fix by ensuring ".hoodie" and their
   * subfolders are never processed.
   */
  @Test
  public void testProcessFiles() throws Exception {
    // All directories including marker dirs.
    List<String> folders =
        Arrays.asList("2016/04/15", "2016/05/16", ".hoodie/.temp/2/2016/04/15", ".hoodie/.temp/2/2016/05/16");
    folders.forEach(f -> {
      try {
        metaClient.getFs().mkdirs(new Path(new Path(basePath), f));
      } catch (IOException e) {
        throw new HoodieException(e);
      }
    });

    // Files inside partitions and marker directories
    List<String> files = Stream.of("2016/04/15/1_1-0-1_20190528120000",
            "2016/05/16/2_1-0-1_20190528120000",
            ".hoodie/.temp/2/2016/05/16/2_1-0-1_20190528120000",
            ".hoodie/.temp/2/2016/04/15/1_1-0-1_20190528120000")
        .map(fileName -> fileName + BASE_FILE_EXTENSION)
        .collect(Collectors.toList());

    files.forEach(f -> {
      try {
        metaClient.getFs().create(new Path(new Path(basePath), f));
      } catch (IOException e) {
        throw new HoodieException(e);
      }
    });

    // Test excluding meta-folder
    final List<String> collected = new ArrayList<>();
    FSUtils.processFiles(metaClient.getFs(), basePath, (status) -> {
      collected.add(status.getPath().toString());
      return true;
    }, true);

    assertTrue(collected.stream().noneMatch(s -> s.contains(HoodieTableMetaClient.METAFOLDER_NAME)),
        "Hoodie MetaFolder MUST be skipped but got :" + collected);
    // Check if only files are listed
    assertEquals(2, collected.size());

    // Test including meta-folder
    final List<String> collected2 = new ArrayList<>();
    FSUtils.processFiles(metaClient.getFs(), basePath, (status) -> {
      collected2.add(status.getPath().toString());
      return true;
    }, false);

    assertFalse(collected2.stream().noneMatch(s -> s.contains(HoodieTableMetaClient.METAFOLDER_NAME)),
        "Hoodie MetaFolder will be present :" + collected2);
    // Check if only files are listed including hoodie.properties
    assertEquals(5, collected2.size(), "Collected=" + collected2);
  }

  @Test
  public void testGetCommitTime() {
    String instantTime = HoodieActiveTimeline.formatDate(new Date());
    String fileName = UUID.randomUUID().toString();
    String fullFileName = FSUtils.makeBaseFileName(instantTime, TEST_WRITE_TOKEN, fileName);
    assertEquals(instantTime, FSUtils.getCommitTime(fullFileName));
    // test log file name
    fullFileName = FSUtils.makeLogFileName(fileName, HOODIE_LOG.getFileExtension(), instantTime, 1, TEST_WRITE_TOKEN);
    assertEquals(instantTime, FSUtils.getCommitTime(fullFileName));
  }

  @Test
  public void testGetFileNameWithoutMeta() {
    String instantTime = HoodieActiveTimeline.formatDate(new Date());
    String fileName = UUID.randomUUID().toString();
    String fullFileName = FSUtils.makeBaseFileName(instantTime, TEST_WRITE_TOKEN, fileName);
    assertEquals(fileName, FSUtils.getFileId(fullFileName));
  }

  @Test
  public void testEnvVarVariablesPickedup() {
    environmentVariables.set("HOODIE_ENV_fs_DOT_key1", "value1");
    Configuration conf = FSUtils.prepareHadoopConf(HoodieTestUtils.getDefaultHadoopConf());
    assertEquals("value1", conf.get("fs.key1"));
    conf.set("fs.key1", "value11");
    conf.set("fs.key2", "value2");
    assertEquals("value11", conf.get("fs.key1"));
    assertEquals("value2", conf.get("fs.key2"));
  }

  @Test
  public void testGetRelativePartitionPath() {
    Path basePath = new Path("/test/apache");
    Path partitionPath = new Path("/test/apache/hudi/sub");
    assertEquals("hudi/sub", FSUtils.getRelativePartitionPath(basePath, partitionPath));

    Path nonPartitionPath = new Path("/test/something/else");
    assertThrows(IllegalArgumentException.class, () -> FSUtils.getRelativePartitionPath(basePath, nonPartitionPath));
  }

  @Test
  public void testGetRelativePartitionPathSameFolder() {
    Path basePath = new Path("/test");
    Path partitionPath = new Path("/test");
    assertEquals("", FSUtils.getRelativePartitionPath(basePath, partitionPath));
  }

  @Test
  public void testGetRelativePartitionPathRepeatedFolderNameBasePath() {
    Path basePath = new Path("/test/apache/apache");
    Path partitionPath = new Path("/test/apache/apache/hudi");
    assertEquals("hudi", FSUtils.getRelativePartitionPath(basePath, partitionPath));
  }

  @Test
  public void testGetRelativePartitionPathRepeatedFolderNamePartitionPath() {
    Path basePath = new Path("/test/apache");
    Path partitionPath = new Path("/test/apache/apache/hudi");
    assertEquals("apache/hudi", FSUtils.getRelativePartitionPath(basePath, partitionPath));
  }

  @Test
  public void testOldLogFileName() {
    // Check if old log file names are still parsable by FSUtils method
    String partitionPath = "2019/01/01/";
    String fileName = UUID.randomUUID().toString();
    String oldLogFile = makeOldLogFileName(fileName, ".log", "100", 1);
    Path rlPath = new Path(new Path(partitionPath), oldLogFile);
    assertTrue(FSUtils.isLogFile(rlPath));
    assertEquals(fileName, FSUtils.getFileIdFromLogPath(rlPath));
    assertEquals("100", FSUtils.getDeltaCommitTimeFromLogPath(rlPath));
    assertEquals(1, FSUtils.getFileVersionFromLog(rlPath));
    assertNull(FSUtils.getTaskPartitionIdFromLogPath(rlPath));
    assertNull(FSUtils.getStageIdFromLogPath(rlPath));
    assertNull(FSUtils.getTaskAttemptIdFromLogPath(rlPath));
    assertNull(FSUtils.getWriteTokenFromLogPath(rlPath));
  }

  @Test
  public void tesLogFileName() {
    // Check if log file names are parsable by FSUtils method
    String partitionPath = "2019/01/01/";
    String fileName = UUID.randomUUID().toString();
    String logFile = FSUtils.makeLogFileName(fileName, ".log", "100", 2, "1-0-1");
    System.out.println("Log File =" + logFile);
    Path rlPath = new Path(new Path(partitionPath), logFile);
    assertTrue(FSUtils.isLogFile(rlPath));
    assertEquals(fileName, FSUtils.getFileIdFromLogPath(rlPath));
    assertEquals("100", FSUtils.getDeltaCommitTimeFromLogPath(rlPath));
    assertEquals(2, FSUtils.getFileVersionFromLog(rlPath));
    assertEquals(1, FSUtils.getTaskPartitionIdFromLogPath(rlPath));
    assertEquals(0, FSUtils.getStageIdFromLogPath(rlPath));
    assertEquals(1, FSUtils.getTaskAttemptIdFromLogPath(rlPath));
  }

  @Test
  public void testCdcLogFileName() {
    String partitionPath = "2022/11/04/";
    String fileName = UUID.randomUUID().toString();
    String logFile = FSUtils.makeLogFileName(fileName, ".log", "100", 2, "1-0-1") + HoodieCDCUtils.CDC_LOGFILE_SUFFIX;
    Path path = new Path(new Path(partitionPath), logFile);

    assertTrue(FSUtils.isLogFile(path));
    assertEquals("log", FSUtils.getFileExtensionFromLog(path));
    assertEquals(fileName, FSUtils.getFileIdFromLogPath(path));
    assertEquals("100", FSUtils.getDeltaCommitTimeFromLogPath(path));
    assertEquals(1, FSUtils.getTaskPartitionIdFromLogPath(path));
    assertEquals("1-0-1", FSUtils.getWriteTokenFromLogPath(path));
    assertEquals(0, FSUtils.getStageIdFromLogPath(path));
    assertEquals(1, FSUtils.getTaskAttemptIdFromLogPath(path));
    assertEquals(2, FSUtils.getFileVersionFromLog(path));
  }

  @Test
  public void testArchiveLogFileName() {
    String partitionPath = "2022/11/04/";
    String fileName = "commits";
    String logFile = FSUtils.makeLogFileName(fileName, ".archive", "", 2, "1-0-1");
    Path path = new Path(new Path(partitionPath), logFile);

    assertFalse(FSUtils.isLogFile(path));
    assertEquals("archive", FSUtils.getFileExtensionFromLog(path));
    assertEquals(fileName, FSUtils.getFileIdFromLogPath(path));
    assertEquals("", FSUtils.getDeltaCommitTimeFromLogPath(path));
    assertEquals(1, FSUtils.getTaskPartitionIdFromLogPath(path));
    assertEquals("1-0-1", FSUtils.getWriteTokenFromLogPath(path));
    assertEquals(0, FSUtils.getStageIdFromLogPath(path));
    assertEquals(1, FSUtils.getTaskAttemptIdFromLogPath(path));
    assertEquals(2, FSUtils.getFileVersionFromLog(path));
  }

  /**
   * Test Log File Comparisons when log files do not have write tokens.
   */
  @Test
  public void testOldLogFilesComparison() {
    String log1Ver0 = makeOldLogFileName("file1", ".log", "1", 0);
    String log1Ver1 = makeOldLogFileName("file1", ".log", "1", 1);
    String log1base2 = makeOldLogFileName("file1", ".log", "2", 0);
    List<HoodieLogFile> logFiles = Stream.of(log1base2, log1Ver1, log1Ver0).map(HoodieLogFile::new)
        .sorted(HoodieLogFile.getLogFileComparator()).collect(Collectors.toList());
    assertEquals(log1Ver0, logFiles.get(0).getFileName());
    assertEquals(log1Ver1, logFiles.get(1).getFileName());
    assertEquals(log1base2, logFiles.get(2).getFileName());
  }

  /**
   * Test Log File Comparisons when log files do not have write tokens.
   */
  @Test
  public void testLogFilesComparison() {
    String log1Ver0W0 = FSUtils.makeLogFileName("file1", ".log", "1", 0, "0-0-1");
    String log1Ver0W1 = FSUtils.makeLogFileName("file1", ".log", "1", 0, "1-1-1");
    String log1Ver1W0 = FSUtils.makeLogFileName("file1", ".log", "1", 1, "0-0-1");
    String log1Ver1W1 = FSUtils.makeLogFileName("file1", ".log", "1", 1, "1-1-1");
    String log1base2W0 = FSUtils.makeLogFileName("file1", ".log", "2", 0, "0-0-1");
    String log1base2W1 = FSUtils.makeLogFileName("file1", ".log", "2", 0, "1-1-1");

    List<HoodieLogFile> logFiles =
        Stream.of(log1Ver1W1, log1base2W0, log1base2W1, log1Ver1W0, log1Ver0W1, log1Ver0W0)
            .map(HoodieLogFile::new).sorted(HoodieLogFile.getLogFileComparator()).collect(Collectors.toList());
    assertEquals(log1Ver0W0, logFiles.get(0).getFileName());
    assertEquals(log1Ver0W1, logFiles.get(1).getFileName());
    assertEquals(log1Ver1W0, logFiles.get(2).getFileName());
    assertEquals(log1Ver1W1, logFiles.get(3).getFileName());
    assertEquals(log1base2W0, logFiles.get(4).getFileName());
    assertEquals(log1base2W1, logFiles.get(5).getFileName());
  }

  @Test
  public void testLogFilesComparisonWithCDCFile() {
    HoodieLogFile log1 = new HoodieLogFile(new Path(FSUtils.makeLogFileName("file1", ".log", "1", 0, "0-0-1")));
    HoodieLogFile log2 = new HoodieLogFile(new Path(FSUtils.makeLogFileName("file1", ".log", "2", 0, "0-0-1")));
    HoodieLogFile log3 = new HoodieLogFile(new Path(FSUtils.makeLogFileName("file1", ".log", "2", 1, "0-0-1")));
    HoodieLogFile log4 = new HoodieLogFile(new Path(FSUtils.makeLogFileName("file1", ".log", "2", 1, "1-1-1")));
    HoodieLogFile log5 = new HoodieLogFile(new Path(FSUtils.makeLogFileName("file1", ".log", "2", 1, "1-1-1") + HoodieCDCUtils.CDC_LOGFILE_SUFFIX));

    TreeSet<HoodieLogFile> logFilesSet = new TreeSet<>(HoodieLogFile.getLogFileComparator());
    logFilesSet.add(log1);
    logFilesSet.add(log2);
    logFilesSet.add(log3);
    logFilesSet.add(log4);
    logFilesSet.add(log5);

    List<HoodieLogFile> logFilesList = new ArrayList<>(logFilesSet);
    assertEquals(log1, logFilesList.get(0));
    assertEquals(log2, logFilesList.get(1));
    assertEquals(log3, logFilesList.get(2));
    assertEquals(log4, logFilesList.get(3));
    assertEquals(log5, logFilesList.get(4));
  }

  public static String makeOldLogFileName(String fileId, String logFileExtension, String deltaCommitTime, int version) {
    return "." + String.format("%s_%s%s.%d", fileId, deltaCommitTime, logFileExtension, version);
  }

  @Test
  public void testFileNameRelatedFunctions() throws Exception {
    String instantTime = "20160501010101";
    String partitionStr = "2016/05/01";
    int taskPartitionId = 456;
    String writeToken = "456";
    String fileId = "Id123";
    int version = 1;
    final String LOG_STR = "log";
    final String LOG_EXTENSION = "." + LOG_STR;

    // data file name
    String dataFileName = FSUtils.makeBaseFileName(instantTime, writeToken, fileId);
    assertEquals(instantTime, FSUtils.getCommitTime(dataFileName));
    assertEquals(fileId, FSUtils.getFileId(dataFileName));

    String logFileName = FSUtils.makeLogFileName(fileId, LOG_EXTENSION, instantTime, version, writeToken);
    assertTrue(FSUtils.isLogFile(new Path(logFileName)));
    assertEquals(instantTime, FSUtils.getDeltaCommitTimeFromLogPath(new Path(logFileName)));
    assertEquals(fileId, FSUtils.getFileIdFromLogPath(new Path(logFileName)));
    assertEquals(version, FSUtils.getFileVersionFromLog(new Path(logFileName)));
    assertEquals(LOG_STR, FSUtils.getFileExtensionFromLog(new Path(logFileName)));

    // create three versions of log file
    java.nio.file.Path partitionPath = Paths.get(basePath, partitionStr);
    Files.createDirectories(partitionPath);
    String log1 = FSUtils.makeLogFileName(fileId, LOG_EXTENSION, instantTime, 1, writeToken);
    Files.createFile(partitionPath.resolve(log1));
    String log2 = FSUtils.makeLogFileName(fileId, LOG_EXTENSION, instantTime, 2, writeToken);
    Files.createFile(partitionPath.resolve(log2));
    String log3 = FSUtils.makeLogFileName(fileId, LOG_EXTENSION, instantTime, 3, writeToken);
    Files.createFile(partitionPath.resolve(log3));

    assertEquals(3, (int) FSUtils.getLatestLogVersion(FSUtils.getFs(basePath, new Configuration()),
        new Path(partitionPath.toString()), fileId, LOG_EXTENSION, instantTime).get().getLeft());
  }

  @Test
  public void testGetFilename() {
    assertEquals("file1.parquet", FSUtils.getFileName("/2022/07/29/file1.parquet", "/2022/07/29"));
    assertEquals("file2.parquet", FSUtils.getFileName("2022/07/29/file2.parquet", "2022/07/29"));
    assertEquals("file3.parquet", FSUtils.getFileName("/file3.parquet", ""));
    assertEquals("file4.parquet", FSUtils.getFileName("file4.parquet", ""));
  }

  private void prepareTestDirectory(FileSystem fileSystem, Path rootDir) throws IOException {
    // Directory structure
    // .hoodie/.temp/
    //  - subdir1
    //    - file1.txt
    //  - subdir2
    //    - file2.txt
    //  - file3
    String subDir1 = rootDir + "/subdir1";
    String file1 = subDir1 + "/file1.txt";
    String subDir2 = rootDir + "/subdir2";
    String file2 = subDir2 + "/file2.txt";
    String file3 = rootDir + "/file3.txt";
    String[] dirs = new String[] {rootDir.toString(), subDir1, subDir2};
    String[] files = new String[] {file1, file2, file3};
    // clean up first
    cleanUpTestDirectory(fileSystem, rootDir);
    for (String dir : dirs) {
      fileSystem.mkdirs(new Path(dir));
    }
    for (String filename : files) {
      fileSystem.create(new Path(filename));
    }
  }

  private void cleanUpTestDirectory(FileSystem fileSystem, Path rootDir) throws IOException {
    fileSystem.delete(rootDir, true);
  }

  @Test
  public void testDeleteExistingDir() throws IOException {
    Path rootDir = getHoodieTempDir();
    FileSystem fileSystem = metaClient.getFs();
    prepareTestDirectory(fileSystem, rootDir);

    assertTrue(fileSystem.exists(rootDir));
    assertTrue(FSUtils.deleteDir(
        new HoodieLocalEngineContext(metaClient.getHadoopConf()), fileSystem, rootDir, 2));
    assertFalse(fileSystem.exists(rootDir));
  }

  @Test
  public void testDeleteNonExistingDir() throws IOException {
    Path rootDir = getHoodieTempDir();
    FileSystem fileSystem = metaClient.getFs();
    cleanUpTestDirectory(fileSystem, rootDir);

    assertFalse(FSUtils.deleteDir(
        new HoodieLocalEngineContext(metaClient.getHadoopConf()), fileSystem, rootDir, 2));
  }

  @Test
  public void testDeleteSubDirectoryRecursively() throws IOException {
    Path rootDir = getHoodieTempDir();
    Path subDir = new Path(rootDir, "subdir1");
    FileSystem fileSystem = metaClient.getFs();
    prepareTestDirectory(fileSystem, rootDir);

    assertTrue(FSUtils.deleteSubPath(
        subDir.toString(), new SerializableConfiguration(fileSystem.getConf()), true));
  }

  @Test
  public void testDeleteSubDirectoryNonRecursively() throws IOException {
    Path rootDir = getHoodieTempDir();
    Path subDir = new Path(rootDir, "subdir1");
    FileSystem fileSystem = metaClient.getFs();
    prepareTestDirectory(fileSystem, rootDir);

    assertThrows(
        HoodieIOException.class,
        () -> FSUtils.deleteSubPath(
            subDir.toString(), new SerializableConfiguration(fileSystem.getConf()), false));
  }

  @Test
  public void testDeleteSubPathAsFile() throws IOException {
    Path rootDir = getHoodieTempDir();
    Path subDir = new Path(rootDir, "file3.txt");
    FileSystem fileSystem = metaClient.getFs();
    prepareTestDirectory(fileSystem, rootDir);

    assertTrue(FSUtils.deleteSubPath(
        subDir.toString(), new SerializableConfiguration(fileSystem.getConf()), false));
  }

  @Test
  public void testDeleteNonExistingSubDirectory() throws IOException {
    Path rootDir = getHoodieTempDir();
    Path subDir = new Path(rootDir, "subdir10");
    FileSystem fileSystem = metaClient.getFs();
    cleanUpTestDirectory(fileSystem, rootDir);

    assertFalse(FSUtils.deleteSubPath(
        subDir.toString(), new SerializableConfiguration(fileSystem.getConf()), true));
  }

  @Test
  public void testParallelizeSubPathProcessWithExistingDir() throws IOException {
    Path rootDir = getHoodieTempDir();
    FileSystem fileSystem = metaClient.getFs();
    prepareTestDirectory(fileSystem, rootDir);
    Map<String, List<String>> result = FSUtils.parallelizeSubPathProcess(
        new HoodieLocalEngineContext(fileSystem.getConf()), fileSystem, rootDir, 2,
        fileStatus -> !fileStatus.getPath().getName().contains("1"),
        pairOfSubPathAndConf -> {
          Path subPath = new Path(pairOfSubPathAndConf.getKey());
          List<String> listFiles = new ArrayList<>();
          try {
            FileSystem fs = subPath.getFileSystem(pairOfSubPathAndConf.getValue().get());
            FileStatus[] fileStatuses = fs.listStatus(subPath);
            listFiles = Arrays.stream(fileStatuses)
                .map(fileStatus -> fileStatus.getPath().getName()).collect(Collectors.toList());
          } catch (IOException e) {
            e.printStackTrace();
          }
          return listFiles;
        }
    );
    assertEquals(2, result.size());
    for (String subPath : result.keySet()) {
      if (subPath.contains("subdir2")) {
        assertEquals(Collections.singletonList("file2.txt"), result.get(subPath));
      } else if (subPath.contains("file3")) {
        assertEquals(Collections.singletonList("file3.txt"), result.get(subPath));
      }
    }
  }

  @Test
  public void testGetFileStatusAtLevel() throws IOException {
    Path hoodieTempDir = getHoodieTempDir();
    FileSystem fileSystem = metaClient.getFs();
    prepareTestDirectory(fileSystem, hoodieTempDir);
    List<FileStatus> fileStatusList = FSUtils.getFileStatusAtLevel(
        new HoodieLocalEngineContext(fileSystem.getConf()), fileSystem,
        new Path(baseUri), 3, 2);
    assertEquals(CollectionUtils.createImmutableSet(
            new Path(baseUri.toString(), ".hoodie/.temp/subdir1/file1.txt"),
            new Path(baseUri.toString(), ".hoodie/.temp/subdir2/file2.txt")),
        fileStatusList.stream()
            .map(FileStatus::getPath)
            .filter(filePath -> filePath.getName().endsWith(".txt"))
            .collect(Collectors.toSet()));
  }

  private Path getHoodieTempDir() {
    return new Path(baseUri.toString(), ".hoodie/.temp");
  }
}
