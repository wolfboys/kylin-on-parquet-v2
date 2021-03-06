/*
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

package org.apache.kylin.engine.spark;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import org.apache.hadoop.util.Shell;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.StorageURL;
import org.apache.kylin.common.util.DateFormat;
import org.apache.kylin.common.util.LocalFileMetadataTestCase;
import org.apache.kylin.common.util.TempMetadataBuilder;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.cube.CubeSegment;
import org.apache.kylin.engine.spark.builder.CreateFlatTable;
import org.apache.kylin.engine.spark.job.NSparkCubingJob;
import org.apache.kylin.engine.spark.job.NSparkCubingStep;
import org.apache.kylin.engine.spark.job.NSparkMergingJob;
import org.apache.kylin.engine.spark.job.UdfManager;
import org.apache.kylin.engine.spark.metadata.MetadataConverter;
import org.apache.kylin.job.engine.JobEngineConfig;
import org.apache.kylin.job.exception.SchedulerException;
import org.apache.kylin.job.execution.AbstractExecutable;
import org.apache.kylin.job.execution.ExecutableManager;
import org.apache.kylin.job.execution.ExecutableState;
import org.apache.kylin.job.impl.threadpool.DefaultScheduler;
import org.apache.kylin.job.lock.MockJobLock;
import org.apache.kylin.metadata.TableMetadataManager;
import org.apache.kylin.metadata.model.ColumnDesc;
import org.apache.kylin.metadata.model.DataModelManager;
import org.apache.kylin.metadata.model.SegmentRange;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.project.ProjectInstance;
import org.apache.kylin.metadata.project.ProjectManager;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.KylinSparkEnv;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.internal.StaticSQLConf;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spark_project.guava.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class LocalWithSparkSessionTest extends LocalFileMetadataTestCase implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(LocalWithSparkSessionTest.class);
    private static final String CSV_TABLE_DIR = "../../examples/test_metadata/data/%s.csv";
    protected static final String KYLIN_SQL_BASE_DIR = "../../kylin-it/src/test/resources/query";

    private Map<String, String> systemProp = Maps.newHashMap();
    protected static SparkConf sparkConf;
    protected static SparkSession ss;

    @Before
    public void setup() throws SchedulerException {
        overwriteSystemProp("kylin.job.scheduler.poll-interval-second", "1");
        overwriteSystemProp("calcite.keep-in-clause", "true");
        overwriteSystemProp("kylin.metadata.distributed-lock-impl", "org.apache.kylin.engine.spark.utils.MockedDistributedLock$MockedFactory");
        this.createTestMetadata();
        DefaultScheduler scheduler = DefaultScheduler.getInstance();
        scheduler.init(new JobEngineConfig(KylinConfig.getInstanceFromEnv()), new MockJobLock());
        if (!scheduler.hasStarted()) {
            throw new RuntimeException("scheduler has not been started");
        }
    }

    @After
    public void after() {
        DefaultScheduler.destroyInstance();
        this.cleanupTestMetadata();
        restoreAllSystemProp();
    }

    protected void overwriteSystemProp(String key, String value) {
        systemProp.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    @BeforeClass
    public static void beforeClass() {

        if (Shell.MAC)
            System.setProperty("org.xerial.snappy.lib.name", "libsnappyjava.jnilib");//for snappy

        sparkConf = new SparkConf().setAppName(UUID.randomUUID().toString()).setMaster("local[4]");
        sparkConf.set("spark.serializer", "org.apache.spark.serializer.JavaSerializer");
        sparkConf.set(StaticSQLConf.CATALOG_IMPLEMENTATION().key(), "in-memory");
        sparkConf.set("spark.sql.shuffle.partitions", "1");
        sparkConf.set("spark.memory.fraction", "0.1");
        // opt memory
        sparkConf.set("spark.shuffle.detectCorrupt", "false");
        // For sinai_poc/query03, enable implicit cross join conversion
        sparkConf.set("spark.sql.crossJoin.enabled", "true");

        ss = SparkSession.builder().config(sparkConf).getOrCreate();
        KylinSparkEnv.setSparkSession(ss);
        UdfManager.create(ss);

        System.out.println("Check spark sql config [spark.sql.catalogImplementation = "
                + ss.conf().get("spark.sql.catalogImplementation") + "]");
    }

    public void createTestMetadata() {
        String tempMetadataDir = TempMetadataBuilder.prepareNLocalTempMetadata();
        KylinConfig.setKylinConfigForLocalTest(tempMetadataDir);
        getTestConfig().setProperty("kylin.query.security.acl-tcr-enabled", "false");
    }

    public void createTestMetadata(String metadataDir) {
        String tempMetadataDir = TempMetadataBuilder.prepareNLocalTempMetadata(false, metadataDir);
        KylinConfig.setKylinConfigForLocalTest(tempMetadataDir);
        getTestConfig().setProperty("kylin.query.security.acl-tcr-enabled", "false");
    }

    protected ExecutableState wait(AbstractExecutable job) throws InterruptedException {
        while (true) {
            Thread.sleep(500);
            ExecutableState status = job.getStatus();
            if (!status.isProgressing()) {
                return status;
            }
        }
    }

    protected void cleanupSegments(String cubeName) throws IOException {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        CubeManager cubeMgr = CubeManager.getInstance(config);
        CubeInstance cube = cubeMgr.getCube(cubeName);
        cubeMgr.updateCubeDropSegments(cube, cube.getSegments());
    }

    public ExecutableState buildCuboid(String cubeName, SegmentRange.TSRange tsRange) throws Exception {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        CubeManager cubeMgr = CubeManager.getInstance(config);
        CubeInstance cube = cubeMgr.getCube(cubeName);
        ExecutableManager execMgr = ExecutableManager.getInstance(config);
        DataModelManager.getInstance(config).getModels();
        // ready cube, segment, cuboid layout
        CubeSegment oneSeg = cubeMgr.appendSegment(cube, tsRange);
        NSparkCubingJob job = NSparkCubingJob.create(Sets.newHashSet(oneSeg), "ADMIN");
        NSparkCubingStep sparkStep = job.getSparkCubingStep();
        StorageURL distMetaUrl = StorageURL.valueOf(sparkStep.getDistMetaUrl());
        Assert.assertEquals("hdfs", distMetaUrl.getScheme());
        Assert.assertTrue(distMetaUrl.getParameter("path").startsWith(config.getHdfsWorkingDirectory()));

        // launch the job
        execMgr.addJob(job);

        return wait(job);
    }

    protected ExecutableState mergeSegments(String cubeName, long start, long end, boolean force) throws Exception {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        CubeManager cubeMgr = CubeManager.getInstance(config);
        ExecutableManager execMgr = ExecutableManager.getInstance(config);
        CubeInstance cube = cubeMgr.reloadCube(cubeName);
        CubeSegment mergeSegment = cubeMgr.mergeSegments(cube, new SegmentRange.TSRange(start, end), null, force);
        NSparkMergingJob mergeJob = NSparkMergingJob.merge(mergeSegment,  "ADMIN");
        execMgr.addJob(mergeJob);
        return wait(mergeJob);
    }

    protected void fullBuildCube(String cubeName) throws Exception {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assert.assertTrue(config.getHdfsWorkingDirectory().startsWith("file:"));
        // cleanup all segments first
        cleanupSegments(cubeName);
        ExecutableState state = buildCuboid(cubeName, null);
        Assert.assertEquals(ExecutableState.SUCCEED, state);
    }

    protected void restoreAllSystemProp() {
        systemProp.forEach((prop, value) -> {
            if (value == null) {
                logger.info("Clear {}", prop);
                System.clearProperty(prop);
            } else {
                logger.info("restore {}", prop);
                System.setProperty(prop, value);
            }
        });
        systemProp.clear();
    }

    protected static void populateSSWithCSVData(KylinConfig kylinConfig, String project, SparkSession sparkSession) {

        ProjectInstance projectInstance = ProjectManager.getInstance(kylinConfig).getProject(project);
        Preconditions.checkArgument(projectInstance != null);
        for (String table : projectInstance.getTables()) {

            if ("DEFAULT.STREAMING_TABLE".equals(table)) {
                continue;
            }
            if (!new File(String.format(Locale.ROOT, CSV_TABLE_DIR, table)).exists()) {
                continue;
            }
            TableDesc tableDesc = TableMetadataManager.getInstance(kylinConfig).getTableDesc(table, project);
            ColumnDesc[] columns = tableDesc.getColumns();
            StructType schema = new StructType();
            for (ColumnDesc column : columns) {
                schema = schema.add(column.getName(), convertType(column.getType()), false);
            }
            Dataset<Row> ret = sparkSession.read().schema(schema).csv(String.format(Locale.ROOT, CSV_TABLE_DIR, table));
            ret.createOrReplaceTempView(tableDesc.getName());
        }

    }

    private static DataType convertType(org.apache.kylin.metadata.datatype.DataType type) {
        if (type.isTimeFamily())
            return DataTypes.TimestampType;

        if (type.isDateTimeFamily())
            return DataTypes.DateType;

        if (type.isIntegerFamily())
            return DataTypes.LongType;

        if (type.isNumberFamily())
            return DataTypes.createDecimalType(19, 4);

        if (type.isStringFamily())
            return DataTypes.StringType;

        if (type.isBoolean())
            return DataTypes.BooleanType;

        throw new IllegalArgumentException("KAP data type: " + type + " can not be converted to spark's type.");
    }

    public void buildMultiSegs(String cubeName) throws Exception {
        cleanupSegments(cubeName);

        long start = dateToLong("2009-01-01 00:00:00");
        long end = dateToLong("2011-01-01 00:00:00");
        buildCuboid(cubeName, new SegmentRange.TSRange(start, end));

        start = dateToLong("2011-01-01 00:00:00");
        end = dateToLong("2013-01-01 00:00:00");
        buildCuboid(cubeName, new SegmentRange.TSRange(start, end));

        start = dateToLong("2013-01-01 00:00:00");
        end = dateToLong("2015-01-01 00:00:00");
        buildCuboid(cubeName, new SegmentRange.TSRange(start, end));
    }

    protected Dataset<Row> initFlatTable(CubeSegment segment) {
        System.out.println(getTestConfig().getMetadataUrl());

        CreateFlatTable flatTable = new CreateFlatTable(
                MetadataConverter.getSegmentInfo(segment.getCubeInstance(), segment.getUuid(),
                        segment.getName(), segment.getStorageLocationIdentifier()), null, ss, null);
        Dataset<Row> ds = flatTable.generateDataset(false, true);
        return ds;
    }

    protected long dateToLong(String date) {
        return DateFormat.stringToMillis(date);
    }

    public String getProject() {
        return "default";
    }
}
