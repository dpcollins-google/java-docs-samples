package com.google.cloud.storage.storagetransfer.samples.test;

import static com.google.common.truth.Truth.assertThat;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.google.api.services.storagetransfer.v1.Storagetransfer;
import com.google.api.services.storagetransfer.v1.model.Date;
import com.google.api.services.storagetransfer.v1.model.GcsData;
import com.google.api.services.storagetransfer.v1.model.ObjectConditions;
import com.google.api.services.storagetransfer.v1.model.Schedule;
import com.google.api.services.storagetransfer.v1.model.TimeOfDay;
import com.google.api.services.storagetransfer.v1.model.TransferJob;
import com.google.api.services.storagetransfer.v1.model.TransferOptions;
import com.google.api.services.storagetransfer.v1.model.TransferSpec;
import com.google.cloud.Binding;
import com.google.cloud.Policy;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.BucketInfo.LifecycleRule;
import com.google.cloud.storage.BucketInfo.LifecycleRule.LifecycleAction;
import com.google.cloud.storage.BucketInfo.LifecycleRule.LifecycleCondition;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageClass;
import com.google.cloud.storage.storagetransfer.samples.CheckLatestTransferOperation;
import com.google.cloud.storage.storagetransfer.samples.TransferFromAws;
import com.google.cloud.storage.storagetransfer.samples.TransferToNearline;
import com.google.cloud.storage.storagetransfer.samples.apiary.CheckLatestTransferOperationApiary;
import com.google.cloud.storage.storagetransfer.samples.apiary.CreateTransferClient;
import com.google.cloud.storage.storagetransfer.samples.apiary.TransferFromAwsApiary;
import com.google.cloud.storage.storagetransfer.samples.apiary.TransferToNearlineApiary;
import com.google.cloud.storage.storagetransfer.samples.test.util.TransferJobUtils;
import com.google.cloud.storage.testing.RemoteStorageHelper;
import com.google.common.collect.ImmutableList;
import com.google.storagetransfer.v1.proto.StorageTransferServiceClient;
import com.google.storagetransfer.v1.proto.TransferProto.GetGoogleServiceAccountRequest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class ITStoragetransferSamplesTest {

  private static final String PROJECT_ID = System.getenv("GOOGLE_CLOUD_PROJECT");
  private static final String SINK_GCS_BUCKET = "sts-test-bucket-sink" + UUID.randomUUID();
  private static final String SOURCE_GCS_BUCKET = "sts-test-bucket-source" + UUID.randomUUID();
  private static final String AMAZON_BUCKET = "sts-amazon-bucket" + UUID.randomUUID();
  private static Storage storage;
  private static AmazonS3 s3;

  @BeforeClass
  public static void beforeClass() throws Exception {
    RemoteStorageHelper helper = RemoteStorageHelper.create();
    storage = helper.getOptions().getService();

    storage.create(BucketInfo.newBuilder(SOURCE_GCS_BUCKET).setLocation("us")
        .setLifecycleRules(
            ImmutableList.of(
                new LifecycleRule(
                    LifecycleAction.newDeleteAction(),
                    LifecycleCondition.newBuilder().setAge(1).build())))
        .build());
    storage.create(BucketInfo.newBuilder(SINK_GCS_BUCKET).setLocation("us")
        .setLifecycleRules(
            ImmutableList.of(
                new LifecycleRule(
                    LifecycleAction.newDeleteAction(),
                    LifecycleCondition.newBuilder().setAge(1).build())))
        .setStorageClass(StorageClass.NEARLINE)
        .build());

    grantBucketsStsPermissions();

    s3 = AmazonS3ClientBuilder.standard().withRegion(Regions.US_WEST_1).build();

    s3.createBucket(AMAZON_BUCKET);
  }

  private static void grantBucketsStsPermissions() throws Exception {
    StorageTransferServiceClient sts = StorageTransferServiceClient.create();

    String serviceAccount = sts.getGoogleServiceAccount(
        GetGoogleServiceAccountRequest.newBuilder().setProjectId(PROJECT_ID).build()).getAccountEmail();


    sts.shutdownNow();

    Policy sourceBucketPolicy =
        storage.getIamPolicy(SOURCE_GCS_BUCKET, Storage.BucketSourceOption.requestedPolicyVersion(3));

    Policy sinkBucketPolicy =
        storage.getIamPolicy(SINK_GCS_BUCKET, Storage.BucketSourceOption.requestedPolicyVersion(3));

    String objectViewer = "roles/storage.objectViewer";
    String bucketReader = "roles/storage.legacyBucketReader";
    String bucketWriter = "roles/storage.legacyBucketWriter";
    String member = "serviceAccount:" + serviceAccount;

    List<Binding> sourceBindings = new ArrayList<>(sourceBucketPolicy.getBindingsList());
    List<Binding> sinkBindings = new ArrayList<>(sinkBucketPolicy.getBindingsList());

    Binding objectViewerBinding = Binding.newBuilder().setRole(objectViewer).setMembers(Arrays.asList(member)).build();
    sourceBindings.add(objectViewerBinding);
    sinkBindings.add(objectViewerBinding);

    Binding bucketReaderBinding = Binding.newBuilder().setRole(bucketReader).setMembers(Arrays.asList(member)).build();
    sourceBindings.add(bucketReaderBinding);
    sinkBindings.add(bucketReaderBinding);

    Binding bucketWriterBinding = Binding.newBuilder().setRole(bucketWriter).setMembers(Arrays.asList(member)).build();
    sourceBindings.add(bucketWriterBinding);
    sinkBindings.add(bucketWriterBinding);

    Policy.Builder newSourcePolicy = sourceBucketPolicy.toBuilder().setBindings(sourceBindings).setVersion(3);
    storage.setIamPolicy(SOURCE_GCS_BUCKET, newSourcePolicy.build());

    Policy.Builder newSinkPolicy = sinkBucketPolicy.toBuilder().setBindings(sinkBindings).setVersion(3);
    storage.setIamPolicy(SINK_GCS_BUCKET, newSinkPolicy.build());

  }

  private static void cleanAmazonBucket() {
    try {
      ObjectListing object_listing = s3.listObjects(AMAZON_BUCKET);
      while (true) {
        for (Iterator<?> iterator =
            object_listing.getObjectSummaries().iterator();
            iterator.hasNext(); ) {
          S3ObjectSummary summary = (S3ObjectSummary) iterator.next();
          s3.deleteObject(AMAZON_BUCKET, summary.getKey());
        }

        if (object_listing.isTruncated()) {
          object_listing = s3.listNextBatchOfObjects(object_listing);
        } else {
          break;
        }
      }
      s3.deleteBucket(AMAZON_BUCKET);
    } catch (AmazonServiceException e) {
      System.err.println(e.getErrorMessage());
    }
  }

  @AfterClass
  public static void afterClass() throws ExecutionException, InterruptedException {
    if (storage != null) {
      long cleanTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2);
      long cleanTimeout = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1);
      RemoteStorageHelper.cleanBuckets(storage, cleanTime, cleanTimeout);

      RemoteStorageHelper.forceDelete(storage, SINK_GCS_BUCKET, 1, TimeUnit.MINUTES);
      RemoteStorageHelper.forceDelete(storage, SOURCE_GCS_BUCKET, 1, TimeUnit.MINUTES);
    }

    cleanAmazonBucket();
  }

  @Test
  public void testCheckLatestTransferOperationApiary() throws Exception {
    Date date = TransferJobUtils.createDate("2000-01-01");
    TimeOfDay time = TransferJobUtils.createTimeOfDay("00:00:00");
    TransferJob transferJob =
        new TransferJob()
            .setDescription("Sample job")
            .setProjectId(PROJECT_ID)
            .setTransferSpec(
                new TransferSpec()
                    .setGcsDataSource(
                        new GcsData().setBucketName(SOURCE_GCS_BUCKET))
                    .setGcsDataSink(
                        new GcsData().setBucketName(SINK_GCS_BUCKET))
                    .setObjectConditions(
                        new ObjectConditions()
                            .setMinTimeElapsedSinceLastModification("2592000s" /* 30 days */))
                    .setTransferOptions(
                        new TransferOptions().setDeleteObjectsFromSourceAfterTransfer(false)))
            .setSchedule(new Schedule().setScheduleStartDate(date).setStartTimeOfDay(time))
            .setStatus("ENABLED");

    Storagetransfer client = CreateTransferClient.createStorageTransferClient();
    TransferJob response = client.transferJobs().create(transferJob).execute();

    PrintStream standardOut = System.out;
    final ByteArrayOutputStream sampleOutputCapture = new ByteArrayOutputStream();
    System.setOut(new PrintStream(sampleOutputCapture));

    CheckLatestTransferOperationApiary.checkLatestTransferOperationApiary(PROJECT_ID, response.getName());

    String sampleOutput = sampleOutputCapture.toString();
    System.setOut(standardOut);
    System.out.println(sampleOutput);
    assertThat(sampleOutput).contains(response.getName());
  }

  @Test
  public void testCheckLatestTransferOperation() throws Exception {
    Date date = TransferJobUtils.createDate("2000-01-01");
    TimeOfDay time = TransferJobUtils.createTimeOfDay("00:00:00");
    TransferJob transferJob =
        new TransferJob()
            .setDescription("Sample job")
            .setProjectId(PROJECT_ID)
            .setTransferSpec(
                new TransferSpec()
                    .setGcsDataSource(
                        new GcsData().setBucketName(SOURCE_GCS_BUCKET))
                    .setGcsDataSink(
                        new GcsData().setBucketName(SINK_GCS_BUCKET))
                    .setObjectConditions(
                        new ObjectConditions()
                            .setMinTimeElapsedSinceLastModification("2592000s" /* 30 days */))
                    .setTransferOptions(
                        new TransferOptions().setDeleteObjectsFromSourceAfterTransfer(false)))
            .setSchedule(new Schedule().setScheduleStartDate(date).setStartTimeOfDay(time))
            .setStatus("ENABLED");

    Storagetransfer client = CreateTransferClient.createStorageTransferClient();

    TransferJob response = client.transferJobs().create(transferJob).execute();
    PrintStream standardOut = System.out;
    final ByteArrayOutputStream sampleOutputCapture = new ByteArrayOutputStream();
    System.setOut(new PrintStream(sampleOutputCapture));

    CheckLatestTransferOperation.checkLatestTransferOperation(PROJECT_ID, response.getName());

    String sampleOutput = sampleOutputCapture.toString();
    System.setOut(standardOut);
    System.out.println(sampleOutput);
    assertThat(sampleOutput).contains(response.getName());
  }

  @Test
  public void testTransferFromAws() throws Exception {
    PrintStream standardOut = System.out;
    final ByteArrayOutputStream sampleOutputCapture = new ByteArrayOutputStream();
    System.setOut(new PrintStream(sampleOutputCapture));

    TransferFromAws.transferFromAws(PROJECT_ID, "Sample transfer job from S3 to GCS.",
        AMAZON_BUCKET, SINK_GCS_BUCKET,
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2000-01-01 00:00:00").getTime(),
        System.getenv("AWS_ACCESS_KEY_ID"), System.getenv("AWS_SECRET_ACCESS_KEY"));

    String sampleOutput = sampleOutputCapture.toString();
    System.setOut(standardOut);
    assertThat(sampleOutput).contains("\"Sample transfer job from S3 to GCS.\"");
  }

  @Test
  public void testTransferFromAwsApiary() throws Exception {
    PrintStream standardOut = System.out;
    final ByteArrayOutputStream sampleOutputCapture = new ByteArrayOutputStream();
    System.setOut(new PrintStream(sampleOutputCapture));

    TransferFromAwsApiary.transferFromAws(PROJECT_ID, "Sample transfer job from S3 to GCS.",
        AMAZON_BUCKET, SINK_GCS_BUCKET,
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2000-01-01 00:00:00").getTime(),
        System.getenv("AWS_ACCESS_KEY_ID"), System.getenv("AWS_SECRET_ACCESS_KEY"));

    String sampleOutput = sampleOutputCapture.toString();
    System.setOut(standardOut);
    assertThat(sampleOutput).contains("\"Sample transfer job from S3 to GCS.\"");
  }

  @Test
  public void testTransferToNearlineApiary() throws Exception {
    PrintStream standardOut = System.out;
    final ByteArrayOutputStream sampleOutputCapture = new ByteArrayOutputStream();
    System.setOut(new PrintStream(sampleOutputCapture));

    TransferToNearlineApiary
        .transferToNearlineApiary(PROJECT_ID, "Sample transfer job from GCS to GCS Nearline.",
            SOURCE_GCS_BUCKET, SINK_GCS_BUCKET,
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2000-01-01 00:00:00").getTime());

    String sampleOutput = sampleOutputCapture.toString();
    System.setOut(standardOut);
    assertThat(sampleOutput)
        .contains("\"Sample transfer job from GCS to GCS Nearline.\"");
  }
  @Test
  public void testTransferToNearline() throws Exception {
    PrintStream standardOut = System.out;
    final ByteArrayOutputStream sampleOutputCapture = new ByteArrayOutputStream();
    System.setOut(new PrintStream(sampleOutputCapture));

    TransferToNearline
        .transferToNearline(PROJECT_ID, "Sample transfer job from GCS to GCS Nearline.",
            SOURCE_GCS_BUCKET, SINK_GCS_BUCKET,
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2000-01-01 00:00:00").getTime());

    String sampleOutput = sampleOutputCapture.toString();
    System.setOut(standardOut);
    assertThat(sampleOutput)
        .contains("\"Sample transfer job from GCS to GCS Nearline.\"");
  }
}