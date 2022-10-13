import * as cdk from 'aws-cdk-lib';
import * as process from "process";
import {Construct} from 'constructs';
import {PublicHostedZone} from "aws-cdk-lib/aws-route53";
import {CertificateNestedStack} from "./certificate-nested-stack";
import {CognitoNestedStack} from "./cognito-nested-stack";
import {VerifiedSeSDomainNestedStack} from "./verified-ses-domain-nested-stack";
import {NetworkNestedStack} from "./network-nested-stack";
import {RedisNestedStack} from "./redis-nested-stack";
import {AuroraServerlessV2NestedStack} from "./aurora-serverless-v2-nested-stack";
import {S3NestedStack} from "./s3-nested-stack";
import {CloudFrontNestedStack} from "./cloudfront-nested-stack";
import {EC2NestedStack} from "./ec2-nested-stack";

export class DiscourseStack extends cdk.Stack {
    constructor(scope: Construct, id: string, props?: cdk.StackProps) {
        super(scope, id, props);

        const hostedZone = PublicHostedZone.fromPublicHostedZoneAttributes(this, id + 'PublicHostedZone', {
            zoneName: process.env.CDK_DEPLOY_DISCOURSE_HOSTED_ZONE_NAME || '',
            hostedZoneId: process.env.CDK_DEPLOY_DISCOURSE_HOSTED_ZONE_ID || '',
        });
        const customHeaderName = process.env.CDK_DEPLOY_DISCOURSE_CLOUDFRONT_ALB_HEADER_CHECK_HEADER || 'X-Discourse-ALB-Check';
        const customHeaderValue = process.env.CDK_DEPLOY_DISCOURSE_CLOUDFRONT_ALB_HEADER_CHECK_VALUE || 'c9fd4d17-24a6-463f-b470-1c4347253245';

        const certificate = new CertificateNestedStack(this, id + 'Certificate', {
            hostedZone: hostedZone
        });
        const cognito = new CognitoNestedStack(this, id + 'Cognito', {});
        const verifiedSeSDomain = new VerifiedSeSDomainNestedStack(this, id + "VerifiedSeSDomain", {
            hostedZone: hostedZone
        });
        const network = new NetworkNestedStack(this, id + "Network");
        const redis = new RedisNestedStack(this, id + "Redis", {
            vpc: network.vpc,
            subnet: network.privateRedisSubnet,
            securityGroup: network.redisSecurityGroup
        });
        const auroraServerlessV2 = new AuroraServerlessV2NestedStack(this, id + "AuroraServerlessV2", {
            vpc: network.vpc,
            subnet: network.privateAuroraSubnet,
            securityGroup: network.auroraSecurityGroup
        });
        const s3 = new S3NestedStack(this, id + "S3");
        const ec2 = new EC2NestedStack(this, id + "EC2", {
            vpc: network.vpc,
            loadBalancerSecurityGroup: network.loadBalancerSecurityGroup,
            certificateArn: certificate.certificate.certificateArn,
            ec2SecurityGroup: network.ec2SecurityGroup,
            userPoolArn: cognito.userPool.userPoolArn,
            userPoolId: cognito.userPool.userPoolId,
            userPoolClientId: cognito.userPoolClient.userPoolClientId,
            auroraServerlessV2SecretArn: auroraServerlessV2.rdsCluster.secret?.secretArn || '',
            verifiedSeSDomainSecretArn: verifiedSeSDomain.secret.secretArn,
            publicBucket: s3.publicBucket,
            backupBucket: s3.backupBucket,
            redisAttrPrimaryEndPointAddress: redis.redis.attrPrimaryEndPointAddress,
            privateEC2SubnetName: network.privateEC2Subnet.name,
            customHeaderName: customHeaderName,
            customHeaderValue: customHeaderValue,
            smtpUserAccessKeyId: verifiedSeSDomain.smtpUserAccessKeyId
        });
        new CloudFrontNestedStack(this, id + "CloudFront", {
            loadBalancer: ec2.loadBalancer,
            certificateArn: certificate.certificate.certificateArn,
            publicBucket: s3.publicBucket,
            hostedZone: hostedZone,
            customHeaderName: customHeaderName,
            customHeaderValue: customHeaderValue
        });
    }
}
