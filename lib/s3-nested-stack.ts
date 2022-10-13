import {Construct} from 'constructs';
import {NestedStack, NestedStackProps, RemovalPolicy} from "aws-cdk-lib";
import {BlockPublicAccess, Bucket, ObjectOwnership} from "aws-cdk-lib/aws-s3";
import {BucketDeployment, Source} from "aws-cdk-lib/aws-s3-deployment";

export class S3NestedStack extends NestedStack {
    publicBucket: Bucket;
    backupBucket: Bucket;

    constructor(scope: Construct, id: string, props?: NestedStackProps) {
        super(scope, id, props);
        this.publicBucket = new Bucket(this, id + 'PublicBucket', {
            objectOwnership: ObjectOwnership.OBJECT_WRITER,
            blockPublicAccess: {
                blockPublicAcls: false,
                blockPublicPolicy: true,
                ignorePublicAcls: true,
                restrictPublicBuckets: true,
            },
            autoDeleteObjects: true,
            removalPolicy: RemovalPolicy.DESTROY
        });
        this.backupBucket = new Bucket(this, id + 'BackupBucket', {
            autoDeleteObjects: true,
            removalPolicy: RemovalPolicy.DESTROY,
            blockPublicAccess: BlockPublicAccess.BLOCK_ALL
        });
        new BucketDeployment(this, id + 'AppTemplateDeployment', {
            destinationBucket: this.backupBucket,
            sources: [
                Source.asset('assets')
            ]
        });
    }
}
