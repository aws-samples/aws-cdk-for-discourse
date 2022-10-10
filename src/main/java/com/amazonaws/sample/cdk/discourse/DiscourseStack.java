package com.amazonaws.sample.cdk.discourse;

import software.amazon.awscdk.*;
import software.amazon.awscdk.customresources.*;
import software.amazon.awscdk.services.autoscaling.AutoScalingGroup;
import software.amazon.awscdk.services.autoscaling.ElbHealthCheckOptions;
import software.amazon.awscdk.services.certificatemanager.Certificate;
import software.amazon.awscdk.services.certificatemanager.CertificateValidation;
import software.amazon.awscdk.services.certificatemanager.DnsValidatedCertificate;
import software.amazon.awscdk.services.cloudfront.*;
import software.amazon.awscdk.services.cloudfront.origins.LoadBalancerV2Origin;
import software.amazon.awscdk.services.cloudfront.origins.S3Origin;
import software.amazon.awscdk.services.cognito.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.elasticache.CfnReplicationGroup;
import software.amazon.awscdk.services.elasticache.CfnSubnetGroup;
import software.amazon.awscdk.services.elasticloadbalancingv2.Protocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.rds.InstanceProps;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.services.route53.*;
import software.amazon.awscdk.services.route53.targets.CloudFrontTarget;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.ObjectOwnership;
import software.amazon.awscdk.services.s3.deployment.BucketDeployment;
import software.amazon.awscdk.services.s3.deployment.Source;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.ses.EmailIdentity;
import software.amazon.awscdk.services.ses.Identity;
import software.constructs.Construct;

import java.util.InvalidPropertiesFormatException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class DiscourseStack extends Stack {
    public Secret secret;
    public UserPool userPool;
    public UserPoolClient userPoolClient;
    public Vpc vpc;
    public SubnetConfiguration publicSubnet;
    public SubnetConfiguration privateEC2Subnet;
    public SubnetConfiguration privateDatabaseSubnet;
    public SubnetConfiguration privateRedisSubnet;
    public SecurityGroup loadBalancerSecurityGroup;
    public SecurityGroup ec2SecurityGroup;
    public ApplicationLoadBalancer loadBalancer;
    public SecurityGroup auroraSecurityGroup;
    public DatabaseCluster rdsCluster;
    public SecurityGroup redisSecurityGroup;
    public CfnSubnetGroup redisSubnetGroup;
    public CfnReplicationGroup redis;
    public Bucket bucketPublic;
    public Bucket bucketBackup;
    public IPublicHostedZone hostedZone;
    public DnsValidatedCertificate certificate;
    public Distribution distribution;
    public ManagedPolicy ec2Policy;
    public IRole ec2Role;
    public CfnKeyPair keyPair;
    public LaunchTemplate launchTemplate;
    public AutoScalingGroup autoScalingGroup;
    public ApplicationTargetGroup applicationTargetGroup;

    public DiscourseStack(final Construct scope, final String id, final String hostedZoneId, final String hostedZoneName,
                          final String domainName, final String sesDomain, final String notificationEmail, final String developerEmails,
                          final String cognitoAuthSubDomainName, final String cidr, final String discourseSettingsSecretArn,
                          final String customCloudFrontALBHeaderCheckHeader, final String customCloudFrontALBHeaderCheckValue,
                          final StackProps props) throws InvalidPropertiesFormatException {
        super(scope, id, props);

        if (hostedZoneId == null || hostedZoneId.length() <= 0) {
            throw new InvalidPropertiesFormatException("Hosted Zone Id is required (environment CDK_DEPLOY_HOSTED_ZONE_ID)");
        }
        if (hostedZoneName == null || hostedZoneName.length() <= 0) {
            throw new InvalidPropertiesFormatException("Hosted Zone Name (domain name) is required (environment CDK_DEPLOY_HOSTED_ZONE_NAME)");
        }
        if (domainName == null || domainName.length() <= 0) {
            throw new InvalidPropertiesFormatException("Domain name is required (environment CDK_DEPLOY_DISCOURSE_DOMAIN_NAME)");
        }
        if (cognitoAuthSubDomainName == null || cognitoAuthSubDomainName.length() <= 0) {
            throw new InvalidPropertiesFormatException("Cognito auth sub domain name is required (environment CDK_DEPLOY_COGNITO_AUTH_SUB_DOMAIN_NAME)");
        }
        if (discourseSettingsSecretArn == null || discourseSettingsSecretArn.length() <= 0) {
            throw new InvalidPropertiesFormatException("Discourse settings secret arn is required (environment CDK_DEPLOY_DISCOURSE_SETTINGS_SECRET_ARN)");
        }
        if (notificationEmail == null || notificationEmail.length() <= 0) {
            throw new InvalidPropertiesFormatException("Discourse notification email is required (environment CDK_DEPLOY_DISCOURSE_NOTIFICATION_EMAIL)");
        }
        createVerifiedSeSDomain(hostedZoneId, hostedZoneName, sesDomain, id);
        createCognitoUserPool(id);
        createCognitoUserPoolDomain(cognitoAuthSubDomainName, id);
        createCognitoUserPoolClient(domainName, id);
        createPublicSubnet();
        createEC2PrivateSubnet();
        createDatabasePrivateSubnet();
        createRedisPrivateSubnet();
        createVPC(cidr, id);
        createLoadBalancerSecurityGroup(id);
        createEC2SecurityGroup(id);
        createLoadBalancer(id);
        createAuroraSecurityGroup(id);
        createAuroraServerlessV2Cluster(id);
        createRedisSecurityGroup(id);
        createRedisSubnetGroup(id);
        createRedis(id);
        createPublicBucket(id);
        createBackupBucket(id);
        uploadAppTemplateToBackupBucket(id);
        getHostedZone(hostedZoneId, hostedZoneName, id);
        createCertificate(domainName, id);
        createCloudFrontDistribution(domainName, customCloudFrontALBHeaderCheckHeader, customCloudFrontALBHeaderCheckValue, id);
        createCloudFrontDistributionARecord(domainName, id);
        createEC2ResourceAccessPolicy(discourseSettingsSecretArn, id);
        createEC2Role(id);
        createEC2Key(id);
        createLaunchTemplate(domainName, sesDomain, notificationEmail, developerEmails, discourseSettingsSecretArn, props.getEnv(), id);
        createAutoScalingGroup(id);
        createApplicationTargetGroup(id);
        addTargetGroupsToLoadBalancer(customCloudFrontALBHeaderCheckHeader, customCloudFrontALBHeaderCheckValue, id);
    }

    //----------- SeS-----------

    private void createVerifiedSeSDomain(final String hostedZoneId, final String hostedZoneName, final String sesDomain, String id) {
        IPublicHostedZone publicHostedZone = PublicHostedZone.fromPublicHostedZoneAttributes(this, id + "SMTPPublicHostedZone", PublicHostedZoneAttributes.builder()
                .hostedZoneId(hostedZoneId)
                .zoneName(hostedZoneName)
                .build());
        EmailIdentity emailIdentity = EmailIdentity.Builder.create(this, id +"EmailIdentity")
                .identity(Identity.domain(sesDomain))
                .build();
        AtomicInteger index = new AtomicInteger(1);
        emailIdentity.getDkimRecords().forEach((r) -> {
            CfnRecordSet.Builder.create(this, id + "DkimToken" + index.getAndIncrement())
                    .hostedZoneId(publicHostedZone.getHostedZoneId())
                    .type("CNAME")
                    .name(r.getName())
                    .resourceRecords(List.of(r.getValue()))
                    .ttl("60")
                    .build();
        });

        User smtpUser = User.Builder.create(this, id + "SMTPUser")
                .passwordResetRequired(false)
                .build();
        smtpUser.attachInlinePolicy(Policy.Builder.create(this, id + "DiscourseSMTPSendRawEmailPolicy")
                .statements(List.of(PolicyStatement.Builder.create()
                        .resources(List.of("*"))
                        .actions(List.of("ses:SendRawEmail"))
                        .effect(Effect.ALLOW)
                        .build()))
                .build());
        AccessKey accessKey = AccessKey.Builder.create(this, id + "DiscourseSMTPAccessKey")
                .user(smtpUser)
                .status(AccessKeyStatus.ACTIVE)
                .build();
        secret = Secret.Builder.create(this, id + "SMTPUserSecretAccessKeySecret")
                .secretObjectValue(Map.of(
                        "AccessKey", SecretValue.Builder.create(accessKey.getAccessKeyId()).build(),
                        "SecretAccessKey", accessKey.getSecretAccessKey()))
                .build();
    }

    //----------- Cognito -----------


    private void createCognitoUserPool(final String id) {
        userPool = UserPool.Builder.create(this, id + "UserPool")
                .removalPolicy(RemovalPolicy.DESTROY)
                .email(UserPoolEmail.withCognito())
                .signInCaseSensitive(false)
                .signInAliases(SignInAliases.builder().email(true).phone(false).username(false).build())
                .mfa(Mfa.OPTIONAL)
                .mfaSecondFactor(MfaSecondFactor.builder().otp(true).sms(true).build())
                .accountRecovery(AccountRecovery.EMAIL_AND_PHONE_WITHOUT_MFA)
                .autoVerify(AutoVerifiedAttrs.builder().email(true).build())
                .enableSmsRole(true)
                .passwordPolicy(PasswordPolicy.builder()
                        .minLength(8)
                        .requireDigits(true)
                        .requireSymbols(true)
                        .requireUppercase(true)
                        .requireLowercase(true)
                        .tempPasswordValidity(Duration.days(7))
                        .build())
                .selfSignUpEnabled(true)
                .standardAttributes(
                        StandardAttributes.builder()
                                .email(StandardAttribute.builder()
                                        .required(true)
                                        .mutable(true)
                                        .build())
                                .address(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .birthdate(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .familyName(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .gender(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .givenName(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .locale(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .middleName(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .fullname(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .nickname(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .phoneNumber(StandardAttribute.builder()
                                        .required(true)
                                        .mutable(true)
                                        .build())
                                .profilePicture(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .preferredUsername(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .profilePage(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .timezone(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .lastUpdateTime(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .website(StandardAttribute.builder()
                                        .mutable(true)
                                        .build())
                                .build())
                .build();
    }

    private void createCognitoUserPoolDomain(final String cognitoAuthSubDomainName, final String id) {
        UserPoolDomain.Builder.create(this, id + "UserPoolDomain")
                .userPool(userPool)
                .cognitoDomain(CognitoDomainOptions.builder()
                        .domainPrefix(cognitoAuthSubDomainName)
                        .build())
                .build();
    }

    private void createCognitoUserPoolClient(final String callbackDomain, final String id) {
        ClientAttributes clientReadAttributes = new ClientAttributes().withStandardAttributes(StandardAttributesMask.builder()
                .address(true)
                .birthdate(true)
                .email(true)
                .emailVerified(true)
                .familyName(true)
                .gender(true)
                .givenName(true)
                .locale(true)
                .middleName(true)
                .fullname(true)
                .nickname(true)
                .phoneNumber(true)
                .phoneNumberVerified(true)
                .profilePicture(true)
                .preferredUsername(true)
                .profilePage(true)
                .timezone(true)
                .lastUpdateTime(true)
                .website(true)
                .build());
        ClientAttributes clientWriteAttributes = new ClientAttributes().withStandardAttributes(StandardAttributesMask.builder()
                .address(true)
                .birthdate(true)
                .email(true)
                .familyName(true)
                .gender(true)
                .givenName(true)
                .locale(true)
                .middleName(true)
                .fullname(true)
                .nickname(true)
                .phoneNumber(true)
                .profilePicture(true)
                .preferredUsername(true)
                .profilePage(true)
                .timezone(true)
                .lastUpdateTime(true)
                .website(true)
                .build());
        userPoolClient = UserPoolClient.Builder.create(this, id + "UserPoolApp")
                .supportedIdentityProviders(List.of(
                        UserPoolClientIdentityProvider.COGNITO)
                )
                .oAuth(OAuthSettings.builder()
                        .callbackUrls(List.of("https://" + callbackDomain + "/auth/oidc/callback"))
                        .flows(OAuthFlows.builder()
                                .authorizationCodeGrant(true)
                                .build())
                        .scopes(List.of(OAuthScope.PHONE,
                                OAuthScope.EMAIL,
                                OAuthScope.OPENID,
                                OAuthScope.COGNITO_ADMIN,
                                OAuthScope.PROFILE))
                        .build())
                .refreshTokenValidity(Duration.days(30))
                .idTokenValidity(Duration.minutes(60))
                .enableTokenRevocation(true)
                .generateSecret(true)
                .preventUserExistenceErrors(true)
                .userPool(userPool)
                .readAttributes(clientReadAttributes)
                .writeAttributes(clientWriteAttributes)
                .authFlows(AuthFlow.builder()
                        .adminUserPassword(false)
                        .userPassword(false)
                        .userSrp(false)
                        .custom(false)
                        .build())
                .build();
    }

    //----------- VPC-----------
    private void createVPC(final String cidr, final String id) {
        vpc = Vpc.Builder.create(this, id + "Vpc")
                .enableDnsHostnames(true)
                .enableDnsSupport(true)
                .cidr(cidr)
                .natGateways(1)
                .maxAzs(2)
                .natGatewaySubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PUBLIC)
                        .build())
                .subnetConfiguration(List.of(publicSubnet, privateEC2Subnet, privateDatabaseSubnet, privateRedisSubnet))
                .build();
    }

    private void createPublicSubnet() {
        publicSubnet = SubnetConfiguration.builder()
                .cidrMask(20)
                .name("public")
                .subnetType(SubnetType.PUBLIC)
                .build();
    }

    private void createEC2PrivateSubnet() {
        privateEC2Subnet = SubnetConfiguration.builder()
                .cidrMask(24)
                .name("private-ec2")
                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                .build();
    }

    private void createDatabasePrivateSubnet() {
        privateDatabaseSubnet = SubnetConfiguration.builder()
                .cidrMask(24)
                .name("private-database")
                .subnetType(SubnetType.PRIVATE_ISOLATED)
                .build();
    }

    private void createRedisPrivateSubnet() {
        privateRedisSubnet = SubnetConfiguration.builder()
                .cidrMask(20)
                .name("private-redis")
                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                .build();
    }

    private void createLoadBalancerSecurityGroup(final String id) {
        loadBalancerSecurityGroup = SecurityGroup.Builder.create(this, id + "LoadBalancerSecurityGroup")
                .allowAllOutbound(true)
                .vpc(vpc)
                .build();
        loadBalancerSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(443));
    }

    private void createEC2SecurityGroup(final String id) {
        ec2SecurityGroup = SecurityGroup.Builder.create(this, id + "EC2SecurityGroup")
                .allowAllOutbound(true)
                .vpc(vpc)
                .build();
        ec2SecurityGroup.addIngressRule(Peer.securityGroupId(loadBalancerSecurityGroup.getSecurityGroupId()), Port.tcp(80));
    }

    private void createAuroraSecurityGroup(final String id) {
        auroraSecurityGroup = SecurityGroup.Builder.create(this, id + "AuroraSecurityGroup")
                .allowAllOutbound(true)
                .vpc(vpc)
                .build();
        auroraSecurityGroup.addIngressRule(Peer.securityGroupId(ec2SecurityGroup.getSecurityGroupId()), Port.tcp(5432));
    }

    private void createRedisSecurityGroup(final String id) {
        redisSecurityGroup = SecurityGroup.Builder.create(this, id + "RedisSecurityGroup")
                .allowAllOutbound(true)
                .vpc(vpc)
                .build();
        redisSecurityGroup.addIngressRule(Peer.securityGroupId(ec2SecurityGroup.getSecurityGroupId()), Port.tcp(6379));
    }

    private void createLoadBalancer(final String id) {
        loadBalancer = ApplicationLoadBalancer.Builder.create(this, id + "LoadBalancer")
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build())
                .securityGroup(loadBalancerSecurityGroup)
                .http2Enabled(true)
                .idleTimeout(Duration.seconds(60))
                .internetFacing(true)
                .build();
        loadBalancer.setAttribute("routing.http.xff_header_processing.mode","preserve");
        loadBalancer.setAttribute("routing.http.preserve_host_header.enabled", "true");
    }

    //----------- Database -----------

    private void createAuroraServerlessV2Cluster(final String id) {
        int dbClusterInstanceCount = 1;

        rdsCluster = DatabaseCluster.Builder.create(this, id + "Postgres")
                .engine(DatabaseClusterEngine.auroraPostgres(AuroraPostgresClusterEngineProps.builder()
                        .version(AuroraPostgresEngineVersion.VER_13_7)
                        .build()))
                .credentials(Credentials.fromGeneratedSecret("discourse"))
                .instanceProps(InstanceProps.builder()
                        .vpc(vpc)
                        .securityGroups(List.of(auroraSecurityGroup))
                        .vpcSubnets(SubnetSelection.builder().subnetGroupName(privateDatabaseSubnet.getName()).build())
                        .autoMinorVersionUpgrade(true)
                        .allowMajorVersionUpgrade(false)
                        .publiclyAccessible(false)
                        .instanceType(new InstanceType("serverless"))
                        .build())
                .defaultDatabaseName("discourse")
                .instances(dbClusterInstanceCount)
                .build();

        Map scalingParameters = Map.of(
                "DBClusterIdentifier", rdsCluster.getClusterIdentifier(),
                "ServerlessV2ScalingConfiguration", Map.of("MinCapacity", 2, "MaxCapacity", 16)
        );
        AwsCustomResource scalingConfig = AwsCustomResource.Builder.create(this, id + "PostgresScaling")
                .onCreate(AwsSdkCall.builder()
                        .service("RDS")
                        .action("modifyDBCluster")
                        .parameters(scalingParameters)
                        .physicalResourceId(PhysicalResourceId.of(rdsCluster.getClusterIdentifier()))
                        .build())
                .policy(AwsCustomResourcePolicy.fromSdkCalls(SdkCallsPolicyOptions.builder()
                        .resources(AwsCustomResourcePolicy.ANY_RESOURCE)
                        .build()))
                .build();

        CfnDBCluster cfnDBCluster = (CfnDBCluster)rdsCluster.getNode().getDefaultChild();
        ((CfnDBCluster)rdsCluster.getNode().getDefaultChild()).addPropertyOverride("EngineMode", "provisioned");
        scalingConfig.getNode().addDependency(cfnDBCluster);

        CfnResource dbScalingConfigureTarget = (CfnResource)scalingConfig.getNode().findChild("Resource").getNode().getDefaultChild();
        for (int i = 1 ; i <= dbClusterInstanceCount; i++) {
            ((CfnDBInstance)rdsCluster.getNode().findChild("Instance" + i)).addDependsOn(dbScalingConfigureTarget);
        }
    }

    //----------- Redis -----------

    private void createRedisSubnetGroup(final String id) {
        redisSubnetGroup = CfnSubnetGroup.Builder.create(this, id + "RedisSubnetGroup")
                .subnetIds(vpc.selectSubnets(SubnetSelection.builder().subnetGroupName(privateRedisSubnet.getName()).build()).getSubnets().stream().map(ISubnet::getSubnetId).collect(Collectors.toList()))
                .description("Redis subnet group")
                .build();
    }

    private void createRedis(final String id) {
        redis = CfnReplicationGroup.Builder.create(this, id + "Redis")
                .autoMinorVersionUpgrade(true)
                .engine("redis")
                .replicationGroupDescription("Discourse redis")
                .replicasPerNodeGroup(1)
                .numNodeGroups(1)
                .cacheNodeType("cache.t4g.medium")
                .multiAzEnabled(true)
                .securityGroupIds(List.of(redisSecurityGroup.getSecurityGroupId()))
                .build();
        redis.addDependsOn(redisSubnetGroup);
        redis.addPropertyOverride("CacheSubnetGroupName", redisSubnetGroup.getRef());
    }

    //----------- S3 -----------

    private void createPublicBucket(final String id) {
        bucketPublic = Bucket.Builder.create(this, id + "PublicBucket")
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
        bucketBackup = Bucket.Builder.create(this, id + "BucketBackup")
                .autoDeleteObjects(true)
                .removalPolicy(RemovalPolicy.DESTROY)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .build();
    }

    private void uploadAppTemplateToBackupBucket(final String id) {
        BucketDeployment.Builder.create(this, id + "AppTemplateDeployment")
                .destinationBucket(bucketBackup)
                .sources(List.of(Source.asset("assets")))
                .build();
    }

    //----------- CloudFront -----------

    private void getHostedZone(final String hostedZoneId, final String zoneName, final String id) {
        hostedZone = PublicHostedZone.fromPublicHostedZoneAttributes(this, id + "HostedZone", PublicHostedZoneAttributes.builder()
                .hostedZoneId(hostedZoneId)
                .zoneName(zoneName)
                .build());
    }

    private void createCertificate(final String domainName, final String id) {
        certificate = DnsValidatedCertificate.Builder.create(this, id + "Certificate")
                .hostedZone(hostedZone)
                .domainName(domainName)
                .cleanupRoute53Records(true)
                .region(getRegion())
                .validation(CertificateValidation.fromDns(hostedZone))
                .build();
    }

    private void createCloudFrontDistribution(final String domainName, final String customCloudFrontALBHeaderCheckName, final String customCloudFrontALBHeaderCheckValue, final String id) {
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

        S3Origin s3Origin = S3Origin.Builder.create(bucketPublic).build();
        distribution = Distribution.Builder.create(this, id + "ALBCloudFrontDistribution")
                .defaultBehavior(albBehaviour)
                .domainNames(List.of(domainName))
                .certificate(Certificate.fromCertificateArn(this, id + "CLoudFrontCertificate", certificate.getCertificateArn()))
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

    private void createCloudFrontDistributionARecord(final String domainName, final String id) {
        ARecord.Builder.create(this, id + "AliasRecord")
                .zone(hostedZone)
                .recordName(domainName)
                .target(RecordTarget.fromAlias(new CloudFrontTarget(distribution)))
                .deleteExisting(true)
                .comment("Alias to cloudfront distribution")
                .build();
    }

    //----------- EC2 -----------

    private void createEC2ResourceAccessPolicy(final String discourseSettingsSecretArn, final String id) {
        ec2Policy = ManagedPolicy.Builder.create(this, id + "EC2").build();
        PolicyStatement psListBuckets = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("s3:ListAllMyBuckets", "s3:ListBucket"))
                .resources(List.of("*"))
                .build();
        psListBuckets.setEffect(Effect.ALLOW);
        ec2Policy.addStatements(psListBuckets);

        PolicyStatement psBucketObjectAccess = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("s3:*"))
                .resources(List.of(
                        bucketBackup.getBucketArn(),
                        bucketBackup.getBucketArn() + "/*",
                        bucketPublic.getBucketArn(),
                        bucketPublic.getBucketArn() + "/*"
                ))
                .build();
        psBucketObjectAccess.setEffect(Effect.ALLOW);
        ec2Policy.addStatements(psBucketObjectAccess);

        PolicyStatement psDescribeUserPoolClient = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("cognito-idp:DescribeUserPoolClient"))
                .resources(List.of(
                        userPool.getUserPoolArn()
                ))
                .build();
        psDescribeUserPoolClient.setEffect(Effect.ALLOW);
        ec2Policy.addStatements(psDescribeUserPoolClient);

        PolicyStatement psRDSSecrets = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("secretsmanager:GetSecretValue"))
                .resources(List.of(
                        rdsCluster.getSecret().getSecretArn(),
                        discourseSettingsSecretArn,
                        secret.getSecretArn()
                ))
                .build();
        psRDSSecrets.setEffect(Effect.ALLOW);
        ec2Policy.addStatements(psRDSSecrets);
    }

    private void createEC2Role(final String id) {
        ec2Role = Role.Builder.create(this, id + "EC2Role")
                .assumedBy(ServicePrincipal.Builder.create("ec2.amazonaws.com").build())
                .managedPolicies(List.of(ec2Policy, ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore")))
                .description("Role for discourse ec2 instances to access other services such as S3")
                .build();
    }

    private void createEC2Key(final String id) {
        keyPair = CfnKeyPair.Builder.create(this, id + "EC2KeyPair")
                .keyName(id + "-discourse")
                .build();
    }

    private void createLaunchTemplate(final String domainName, final String sesDomain, final String notificationEmail, final String developerEmails, final String discourseSettingsSecretArn, final Environment env, final String id) {
        UserData userData = UserData.forLinux();
        userData.addCommands(
                "sudo -s",
                "yum -y update",
                "yum -y install docker",
                "yum -y install git",
                "yum -y install jq",
                "amazon-linux-extras install -y postgresql13",
                "systemctl enable docker.service",
                "systemctl start docker.service",
                "systemctl status docker.service",
                "git clone https://github.com/discourse/discourse_docker.git /var/discourse",
                "cd /var/discourse",
                "chmod 700 containers",
                "aws s3 cp s3://" + bucketBackup.getBucketName() + "/app.yml.template ./containers/app.yml.template",
                "aws s3 cp s3://" + bucketBackup.getBucketName() + "/smtp_credentials_generate.py ./smtp_credentials_generate.py",
                "echo -e 'export DISCOURSE_DB_USERNAME=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + rdsCluster.getSecret().getSecretName() + " --query SecretString --output text | jq -r .username)' > discourse-env",
                "echo -e 'export DISCOURSE_DB_PASSWORD=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + rdsCluster.getSecret().getSecretName() + " --query SecretString --output text | jq -r .password)' >> discourse-env",
                "echo -e 'export DISCOURSE_DB_HOST=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + rdsCluster.getSecret().getSecretName() + " --query SecretString --output text | jq -r .host)' >> discourse-env",
                "echo -e 'export DISCOURSE_DB_NAME=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + rdsCluster.getSecret().getSecretName() + " --query SecretString --output text | jq -r .dbname)' >> discourse-env",
                "echo -e 'export DISCOURSE_DB_PORT=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + rdsCluster.getSecret().getSecretName() + " --query SecretString --output text | jq -r .port)' >> discourse-env",
                "echo -e 'export PGPASSWORD=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + rdsCluster.getSecret().getSecretName() + " --query SecretString --output text | jq -r .password)' >> discourse-env",
                "echo -e 'export DISCOURSE_REDIS_HOST=" + redis.getAttrPrimaryEndPointAddress() + "' >> discourse-env",
                "echo -e 'export DISCOURSE_HOSTNAME=" + domainName + "' >> discourse-env",
                "echo -e 'export DISCOURSE_DEVELOPER_EMAILS=" + developerEmails + "' >> discourse-env",
                "echo -e 'export DISCOURSE_SMTP_ADDRESS=email-smtp." + env.getRegion() + ".amazonaws.com' >> discourse-env",
                "echo -e 'export DISCOURSE_SMTP_PORT=587' >> discourse-env",
                "echo -e 'export DISCOURSE_SMTP_USER_NAME=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + secret.getSecretName() + " --query SecretString --output text | jq -r .AccessKey)' >> discourse-env",
                "echo -e 'export DISCOURSE_SMTP_PASSWORD=$(python3 smtp_credentials_generate.py $(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + secret.getSecretName() + " --query SecretString --output text | jq -r .SecretAccessKey) " + env.getRegion() + ")' >> discourse-env",
                "echo -e 'export DISCOURSE_SMTP_DOMAIN=" + sesDomain + "' >> discourse-env",
                "echo -e 'export DISCOURSE_NOTIFICATION_EMAIL=" + notificationEmail + "' >> discourse-env",
                "echo -e 'export DOCKER_USER=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + discourseSettingsSecretArn + " --query SecretString --output text | jq -r .DOCKER_USER)' >> discourse-env",
                "echo -e 'export DOCKER_PASSWORD=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + discourseSettingsSecretArn + " --query SecretString --output text | jq -r .DOCKER_PASSWORD)' >> discourse-env",
                "echo -e 'export DISCOURSE_ADMIN_USERNAME=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + discourseSettingsSecretArn + " --query SecretString --output text | jq -r .DISCOURSE_ADMIN_USERNAME)' >> discourse-env",
                "echo -e 'export DISCOURSE_ADMIN_EMAIL=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + discourseSettingsSecretArn + " --query SecretString --output text | jq -r .DISCOURSE_ADMIN_EMAIL)' >> discourse-env",
                "echo -e 'export DISCOURSE_ADMIN_PASSWORD=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + discourseSettingsSecretArn + " --query SecretString --output text | jq -r .DISCOURSE_ADMIN_PASSWORD)' >> discourse-env",
                "echo -e 'export DISCOURSE_S3_REGION=" + env.getRegion() + "' >> discourse-env",
                "echo -e 'export DISCOURSE_S3_UPLOAD_BUCKET=" + bucketPublic.getBucketName() + "' >> discourse-env",
                "echo -e 'export DISCOURSE_S3_BACKUP_BUCKET=" + bucketBackup.getBucketName() + "/backup' >> discourse-env",
                "echo -e 'export DISCOURSE_S3_BUCKET=" + bucketPublic.getBucketName() + "' >> discourse-env",
                "echo -e 'export DISCOURSE_S3_CDN_URL=" + domainName + "' >> discourse-env",
                "echo -e 'export DISCOURSE_CDN_URL=" + domainName + "' >> discourse-env",
                "echo -e 'export DISCOURSE_OPENID_CONNECT_ENABLED=true' >> discourse-env",
                "echo -e 'export DISCOURSE_OPENID_CONNECT_CLIENT_ID=" + userPoolClient.getUserPoolClientId() + "' >> discourse-env",
                "echo -e 'export DISCOURSE_OPENID_CONNECT_CLIENT_SECRET=$(aws cognito-idp describe-user-pool-client --region " + env.getRegion() + " --user-pool-id=" + userPool.getUserPoolId() + " --client-id=" + userPoolClient.getUserPoolClientId() + " --query UserPoolClient.ClientSecret --output text)' >> discourse-env",
                "echo -e 'export DISCOURSE_OPENID_CONNECT_DISCOVERY_DOCUMENT=https://cognito-idp." + env.getRegion() + ".amazonaws.com/" + userPool.getUserPoolId() + "/.well-known/openid-configuration' >> discourse-env",
                "echo -e 'docker login -u $DOCKER_USER -p $DOCKER_PASSWORD' >> discourse-env",
                "chmod +x discourse-env",
                "echo -e 'psql -h $DISCOURSE_DB_HOST -U $DISCOURSE_DB_USERNAME' > psqlconnect",
                "chmod +x psqlconnect",
                ". discourse-env",
                "envsubst '$DISCOURSE_DB_USERNAME,$DISCOURSE_DB_PASSWORD,$DISCOURSE_DB_HOST,$DISCOURSE_DB_NAME,$DISCOURSE_DB_PORT,$DISCOURSE_REDIS_HOST,$DISCOURSE_HOSTNAME," +
                        "$DISCOURSE_DEVELOPER_EMAILS,$DISCOURSE_SMTP_ADDRESS,$DISCOURSE_SMTP_PORT,$DISCOURSE_SMTP_USER_NAME,$DISCOURSE_SMTP_PASSWORD,$DISCOURSE_SMTP_DOMAIN," +
                        "$DISCOURSE_NOTIFICATION_EMAIL,$DISCOURSE_S3_REGION,$DISCOURSE_S3_UPLOAD_BUCKET,$DISCOURSE_S3_BACKUP_BUCKET,$DISCOURSE_S3_BUCKET," +
                        "$DISCOURSE_S3_CDN_URL,$DISCOURSE_CDN_URL,$DISCOURSE_OPENID_CONNECT_ENABLED," +
                        "$DISCOURSE_OPENID_CONNECT_CLIENT_ID,$DISCOURSE_OPENID_CONNECT_CLIENT_SECRET,$DISCOURSE_OPENID_CONNECT_DISCOVERY_DOCUMENT," +
                        "$DISCOURSE_ADMIN_EMAIL,$DISCOURSE_ADMIN_PASSWORD,$DISCOURSE_ADMIN_USERNAME' < ./containers/app.yml.template > ./containers/app.yml",
                "chmod 700 containers",
                "chmod o-rwx containers/app.yml",
                "./launcher rebuild app",
                "aws s3 cp s3://" + bucketBackup.getBucketName() + "/adminenv.rake ./shared/standalone/adminenv.rake",
                "./launcher run app 'chown discourse:root /shared/adminenv.rake'",
                "./launcher run app 'cp -v /shared/adminenv.rake . | rake admin:fromenv' --docker-args '--privileged -w /var/www/discourse/lib/tasks'");

        IMachineImage mi = MachineImage.latestAmazonLinux(AmazonLinuxImageProps.builder()
                .cpuType(AmazonLinuxCpuType.X86_64)
                .edition(AmazonLinuxEdition.STANDARD)
                .generation(AmazonLinuxGeneration.AMAZON_LINUX_2)
                .storage(AmazonLinuxStorage.EBS)
                .userData(userData)
                .build());

        launchTemplate = LaunchTemplate.Builder.create(this, id + "LaunchTemplate")
                .machineImage(mi)
                .instanceType(InstanceType.of(InstanceClass.T3, InstanceSize.MEDIUM))
                .keyName(keyPair.getKeyName())
                .role(ec2Role)
                .securityGroup(ec2SecurityGroup)
                .blockDevices(List.of(BlockDevice.builder()
                        .deviceName("/dev/xvda")
                        .volume(BlockDeviceVolume.ebs(25))
                        .build()))
                .userData(userData)
                .build();
    }

    private void createAutoScalingGroup(final String id) {
        autoScalingGroup = AutoScalingGroup.Builder.create(this, id + "AutoScalingGroup")
                .launchTemplate(launchTemplate)
                .maxCapacity(2)
                .minCapacity(1)
                .healthCheck(software.amazon.awscdk.services.autoscaling.HealthCheck
                        .elb(ElbHealthCheckOptions.builder()
                                .grace(Duration.minutes(25))
                                .build()))
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder().subnetGroupName(privateEC2Subnet.getName()).build())
                .build();
    }

    private void createApplicationTargetGroup(final String id) {
        applicationTargetGroup = ApplicationTargetGroup.Builder.create(this, id + "ApplicationTargetGroup")
                .vpc(vpc)
                .port(80)
                .protocol(ApplicationProtocol.HTTP)
                .targets(List.of(autoScalingGroup))
                .deregistrationDelay(Duration.seconds(30))
                .healthCheck(HealthCheck.builder()
                        .enabled(true)
                        .protocol(Protocol.HTTP)
                        .path("/")
                        .healthyThresholdCount(2)
                        .unhealthyThresholdCount(5)
                        .interval(Duration.seconds(30))
                        .build())
                .build();
    }

    private void addTargetGroupsToLoadBalancer(final String customCloudFrontALBHeaderCheckHeader, final String customCloudFrontALBHeaderCheckValue,
                                               final String id) {
        ApplicationListener httpsListener = loadBalancer.addListener(id + "LoadBalancerHTTPSListener", BaseApplicationListenerProps.builder()
                .port(443)
                .certificates(List.of(ListenerCertificate.fromArn(certificate.getCertificateArn())))
                .protocol(ApplicationProtocol.HTTPS)
                .defaultAction(ListenerAction.fixedResponse(403, FixedResponseOptions.builder().messageBody("Access denied").build()))
                .build());
        httpsListener.addTargetGroups(id + "LoadBalancerHTTPSTargetGroupConditional", AddApplicationTargetGroupsProps.builder()
                .targetGroups(List.of(applicationTargetGroup))
                .conditions(List.of(ListenerCondition.httpHeader(customCloudFrontALBHeaderCheckHeader, List.of(customCloudFrontALBHeaderCheckValue))))
                .priority(1)
                .build());
    }
}
