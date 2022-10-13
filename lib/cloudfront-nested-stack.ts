import {Construct} from 'constructs';
import {Duration, NestedStack, NestedStackProps} from "aws-cdk-lib";
import {
    AllowedMethods,
    BehaviorOptions,
    CachePolicy,
    Distribution,
    OriginProtocolPolicy,
    OriginRequestPolicy,
    ResponseHeadersPolicy,
    ViewerProtocolPolicy
} from "aws-cdk-lib/aws-cloudfront";
import {ApplicationLoadBalancer} from "aws-cdk-lib/aws-elasticloadbalancingv2";
import {Bucket} from "aws-cdk-lib/aws-s3";
import {ARecord, IPublicHostedZone, RecordTarget} from "aws-cdk-lib/aws-route53";
import {LoadBalancerV2Origin, S3Origin} from "aws-cdk-lib/aws-cloudfront-origins";
import * as process from "process";
import {Certificate} from "aws-cdk-lib/aws-certificatemanager";
import {CloudFrontTarget} from "aws-cdk-lib/aws-route53-targets";

interface CloudFrontNestedStackProps extends NestedStackProps {
    readonly loadBalancer: ApplicationLoadBalancer;
    readonly certificateArn: string;
    readonly publicBucket: Bucket;
    readonly hostedZone: IPublicHostedZone;
    readonly customHeaderName: string;
    readonly customHeaderValue: string;
}

export class CloudFrontNestedStack extends NestedStack {
    distribution: Distribution;

    constructor(scope: Construct, id: string, props: CloudFrontNestedStackProps) {
        super(scope, id, props);

        const customHeaders: Record<string, string> = {};
        customHeaders[props.customHeaderName] = props.customHeaderValue;

        const lbOrigin = new LoadBalancerV2Origin(props.loadBalancer, {
            customHeaders: customHeaders,
            protocolPolicy: OriginProtocolPolicy.HTTPS_ONLY
        });

        const albBehaviour: BehaviorOptions = {
            origin: lbOrigin,
            compress: true,
            allowedMethods: AllowedMethods.ALLOW_ALL,
            viewerProtocolPolicy: ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
            cachePolicy: CachePolicy.CACHING_DISABLED,
            originRequestPolicy: OriginRequestPolicy.ALL_VIEWER,
            responseHeadersPolicy: ResponseHeadersPolicy.CORS_ALLOW_ALL_ORIGINS
        };
        const s3Origin = new S3Origin(props.publicBucket);

        this.distribution = new Distribution(this, id + 'ALBCloudFrontDistribution', {
            defaultBehavior: albBehaviour,
            domainNames: [
                process.env.CDK_DEPLOY_DISCOURSE_DOMAIN_NAME || ''
            ],
            certificate: Certificate.fromCertificateArn(this, id + 'CLoudFrontCertificate', props.certificateArn),
            additionalBehaviors: {
                'assets/*': {
                    origin: s3Origin,
                    compress: true,
                    allowedMethods: AllowedMethods.ALLOW_GET_HEAD,
                    viewerProtocolPolicy: ViewerProtocolPolicy.REDIRECT_TO_HTTPS
                },
                'optimized/*': {
                    origin: s3Origin,
                    compress: true,
                    allowedMethods: AllowedMethods.ALLOW_GET_HEAD,
                    viewerProtocolPolicy: ViewerProtocolPolicy.REDIRECT_TO_HTTPS
                },
                'original/*': {
                    origin: s3Origin,
                    compress: true,
                    allowedMethods: AllowedMethods.ALLOW_GET_HEAD,
                    viewerProtocolPolicy: ViewerProtocolPolicy.REDIRECT_TO_HTTPS
                }
            }
        });

        new ARecord(this, id + 'AliasRecord', {
            zone: props.hostedZone,
            recordName: process.env.CDK_DEPLOY_DISCOURSE_DOMAIN_NAME || '',
            deleteExisting: true,
            comment: 'Alias to cloudfront distribution',
            ttl: Duration.seconds(300),
            target: RecordTarget.fromAlias(new CloudFrontTarget(this.distribution))
        });
    }
}
