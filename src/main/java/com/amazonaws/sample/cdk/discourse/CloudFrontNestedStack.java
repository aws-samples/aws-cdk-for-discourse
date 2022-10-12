package com.amazonaws.sample.cdk.discourse;

import software.amazon.awscdk.NestedStack;
import software.amazon.awscdk.services.certificatemanager.Certificate;
import software.amazon.awscdk.services.cloudfront.*;
import software.amazon.awscdk.services.cloudfront.origins.LoadBalancerV2Origin;
import software.amazon.awscdk.services.cloudfront.origins.S3Origin;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.route53.ARecord;
import software.amazon.awscdk.services.route53.IPublicHostedZone;
import software.amazon.awscdk.services.route53.RecordTarget;
import software.amazon.awscdk.services.route53.targets.CloudFrontTarget;
import software.amazon.awscdk.services.s3.Bucket;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public class CloudFrontNestedStack extends NestedStack {
    public Distribution distribution;

    public CloudFrontNestedStack(final Construct scope, final String id, final ApplicationLoadBalancer loadBalancer, final String domainName, final String customCloudFrontALBHeaderCheckName,
                                 final String customCloudFrontALBHeaderCheckValue, final String certificateArn, final Bucket publicBucket, final IPublicHostedZone hostedZone) {
        super(scope, id);

        createCloudFrontDistribution(loadBalancer, domainName, customCloudFrontALBHeaderCheckName, customCloudFrontALBHeaderCheckValue, certificateArn, publicBucket, id);
        createCloudFrontDistributionARecord(domainName, hostedZone, id);
    }


    private void createCloudFrontDistribution(final ApplicationLoadBalancer loadBalancer, final String domainName, final String customCloudFrontALBHeaderCheckName,
                                              final String customCloudFrontALBHeaderCheckValue, final String certificateArn, final Bucket publicBucket, final String id) {
        BehaviorOptions albBehaviour = BehaviorOptions.builder()
                .origin(LoadBalancerV2Origin.Builder.create(loadBalancer)
                        .customHeaders(Map.of(customCloudFrontALBHeaderCheckName, customCloudFrontALBHeaderCheckValue))
                        .protocolPolicy(OriginProtocolPolicy.HTTPS_ONLY)
                        .build())
                .compress(true)
                .allowedMethods(AllowedMethods.ALLOW_ALL)
                .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                .cachePolicy(CachePolicy.CACHING_DISABLED)
                .originRequestPolicy(OriginRequestPolicy.ALL_VIEWER)
                .responseHeadersPolicy(ResponseHeadersPolicy.CORS_ALLOW_ALL_ORIGINS)
                .build();

        S3Origin s3Origin = S3Origin.Builder.create(publicBucket).build();
        distribution = Distribution.Builder.create(this, id + "ALBCloudFrontDistribution")
                .defaultBehavior(albBehaviour)
                .domainNames(List.of(domainName))
                .certificate(Certificate.fromCertificateArn(this, id + "CLoudFrontCertificate", certificateArn))
                .additionalBehaviors(Map.of(
                        "assets/*", BehaviorOptions.builder().origin(s3Origin)
                                .compress(true)
                                .allowedMethods(AllowedMethods.ALLOW_GET_HEAD)
                                .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                                .build(),
                        "optimized/*", BehaviorOptions.builder().origin(s3Origin)
                                .compress(true)
                                .allowedMethods(AllowedMethods.ALLOW_GET_HEAD)
                                .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                                .build(),
                        "original/*", BehaviorOptions.builder().origin(s3Origin)
                                .compress(true)
                                .allowedMethods(AllowedMethods.ALLOW_GET_HEAD)
                                .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                                .build()))
                .build();
    }

    private void createCloudFrontDistributionARecord(final String domainName, final IPublicHostedZone hostedZone, final String id) {
        ARecord.Builder.create(this, id + "AliasRecord")
                .zone(hostedZone)
                .recordName(domainName)
                .target(RecordTarget.fromAlias(new CloudFrontTarget(distribution)))
                .deleteExisting(true)
                .comment("Alias to cloudfront distribution")
                .build();
    }

}
