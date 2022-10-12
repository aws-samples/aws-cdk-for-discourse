package com.amazonaws.sample.cdk.discourse;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.route53.IPublicHostedZone;
import software.amazon.awscdk.services.route53.PublicHostedZone;
import software.amazon.awscdk.services.route53.PublicHostedZoneAttributes;
import software.constructs.Construct;

public class DiscourseStack extends Stack {
    private CertificateNestedStack certificate;
    private CognitoNestedStack cognito;
    private VerifiedSeSDomainNestedStack verifiedSeSDomain;
    private NetworkNestedStack network;
    private RedisNestedStack redis;
    private AuroraServerlessV2NestedStack auroraServerlessV2;
    private S3NestedStack s3;
    private CloudFrontNestedStack CloudFront;

    private EC2NestedStack ec2;

    public DiscourseStack(final Construct scope, final String id, final StackProps props, final String cidr, final String domainName, final String cognitoAuthSubDomainName,
                          final String hostedZoneId, final String hostedZoneName,
                          final String sesDomain, final String notificationEmail, final String developerEmails, final String discourseSettingsSecretArn,
                          final String customCloudFrontALBHeaderCheckHeader, final String customCloudFrontALBHeaderCheckValue) {
        super(scope, id, props);
        IPublicHostedZone hostedZone = PublicHostedZone.fromPublicHostedZoneAttributes(this, id + "PublicHostedZone", PublicHostedZoneAttributes.builder()
                .zoneName(hostedZoneName)
                .hostedZoneId(hostedZoneId)
                .build());
        certificate = new CertificateNestedStack(this, id + "Certificate", hostedZone, domainName);
        cognito = new CognitoNestedStack(this,id + "Cognito", domainName, cognitoAuthSubDomainName);
        verifiedSeSDomain = new VerifiedSeSDomainNestedStack(this, id + "VerifiedSeSDomain", hostedZoneId, hostedZoneName, sesDomain);
        network = new NetworkNestedStack(this,id + "Network", cidr, certificate.getCertificateArn());
        redis = new RedisNestedStack(this, id + "Redis", network.getVpc(), network.getRedisSecurityGroup(), network.getPrivateRedisSubnet());
        auroraServerlessV2 = new AuroraServerlessV2NestedStack(this, id + "AuroraServerlessV2", network.getVpc(), network.getAuroraSecurityGroup(), network.getPrivateAuroraSubnet());
        s3 = new S3NestedStack(this,  id + "S3");
        CloudFront = new CloudFrontNestedStack(this, id + "CloudFront", network.getLoadBalancer(), domainName, customCloudFrontALBHeaderCheckHeader, customCloudFrontALBHeaderCheckValue, certificate.getCertificateArn(), s3.getPublicBucket(), hostedZone);
        ec2 = new EC2NestedStack(this, id + "EC2", cognito.getUserPool().getUserPoolArn(), auroraServerlessV2.getSecret().getSecretArn(),
                                verifiedSeSDomain.getSecret().getSecretArn(), discourseSettingsSecretArn, s3.getPublicBucket(), s3.getBackupBucket(),
                                domainName, sesDomain, notificationEmail, developerEmails, redis.getAttrPrimaryEndPointAddress(), cognito.getUserPool().getUserPoolId(),
                                cognito.getUserPoolClient().getUserPoolClientId(), network.getEc2SecurityGroup(), props.getEnv(),network.getVpc(), network.getPrivateEC2Subnet().getName(),
                                network.getHttpsListener().getListenerArn(), network.getLoadBalancerSecurityGroup(), customCloudFrontALBHeaderCheckHeader, customCloudFrontALBHeaderCheckValue);
    }
}
