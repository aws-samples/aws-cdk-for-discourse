package com.amazonaws.sample.cdk.discourse;

import software.amazon.awscdk.NestedStack;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.ObjectOwnership;
import software.amazon.awscdk.services.s3.deployment.BucketDeployment;
import software.amazon.awscdk.services.s3.deployment.Source;
import software.constructs.Construct;

import java.util.List;

public class S3NestedStack extends NestedStack {
    private Bucket publicBucket;
    private Bucket backupBucket;

    public S3NestedStack(final Construct scope, final String id) {
        super(scope, id);
        createPublicBucket(id);
        createBackupBucket(id);
        uploadAppTemplateToBackupBucket(id);
    }

    public Bucket getPublicBucket() {
        return this.publicBucket;
    }
    public Bucket getBackupBucket() { return this.backupBucket; }

    private void createPublicBucket(final String id) {
        publicBucket = Bucket.Builder.create(this, id + "PublicBucket")
                .objectOwnership(ObjectOwnership.OBJECT_WRITER)
                .blockPublicAccess(BlockPublicAccess.Builder.create()
                        .blockPublicAcls(false)
                        .blockPublicPolicy(true)
                        .ignorePublicAcls(true)
                        .restrictPublicBuckets(true)
                        .build())
                .autoDeleteObjects(true)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
    }

    private void createBackupBucket(final String id) {
        backupBucket = Bucket.Builder.create(this, id + "BackupBucket")
                .autoDeleteObjects(true)
                .removalPolicy(RemovalPolicy.DESTROY)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .build();
    }

    private void uploadAppTemplateToBackupBucket(final String id) {
        BucketDeployment.Builder.create(this, id + "AppTemplateDeployment")
                .destinationBucket(backupBucket)
                .sources(List.of(Source.asset("assets")))
                .build();
    }
}
