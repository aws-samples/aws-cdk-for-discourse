package com.amazonaws.sample.cdk.discourse;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.NestedStack;
import software.amazon.awscdk.services.autoscaling.AutoScalingGroup;
import software.amazon.awscdk.services.autoscaling.ElbHealthCheckOptions;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.s3.Bucket;
import software.constructs.Construct;

import java.util.List;

public class EC2NestedStack extends NestedStack {
    private ManagedPolicy ec2Policy;
    private IRole ec2Role;
    private CfnKeyPair keyPair;
    private LaunchTemplate launchTemplate;
    private AutoScalingGroup autoScalingGroup;
    private ApplicationTargetGroup applicationTargetGroup;

    public EC2NestedStack(Construct scope, String id, final String userPoolArn, final String auroraServerlessV2SecretArn, final String verifiedSeSDomainSecretArn,
                          final String discourseSettingsSecretArn, final Bucket publicBucket, final Bucket backupBucket,
                          final String domainName, final String sesDomain, final String notificationEmail, final String developerEmails,
                          final String redisAttrPrimaryEndPointAddress, final String userPoolId, final String userPoolClientId, final SecurityGroup ec2SecurityGroup,
                          final Environment env, final Vpc vpc, final String privateEC2SubnetName, final String httpsListenerArn, final SecurityGroup loadBalancerSecurityGroup,
                          final String customCloudFrontALBHeaderCheckHeader, final String customCloudFrontALBHeaderCheckValue) {
        super(scope, id);
        createEC2ResourceAccessPolicy(userPoolArn, auroraServerlessV2SecretArn, verifiedSeSDomainSecretArn, discourseSettingsSecretArn, publicBucket, backupBucket, id);
        createEC2Role(id);
        createEC2Key(id);
        createLaunchTemplate(domainName, sesDomain, notificationEmail, developerEmails, auroraServerlessV2SecretArn, verifiedSeSDomainSecretArn,
                discourseSettingsSecretArn, redisAttrPrimaryEndPointAddress, userPoolId,
                userPoolClientId, ec2SecurityGroup, publicBucket, backupBucket, env, id);
        createAutoScalingGroup(vpc, privateEC2SubnetName, id);
        createApplicationTargetGroup(vpc, id);
        addTargetGroupsToLoadBalancer(httpsListenerArn, loadBalancerSecurityGroup, customCloudFrontALBHeaderCheckHeader, customCloudFrontALBHeaderCheckValue, id);
    }


    private void createEC2ResourceAccessPolicy(final String userPoolArn, final String auroraServerlessV2SecretArn, final String verifiedSeSDomainSecretArn,
                                               final String discourseSettingsSecretArn, final Bucket publicBucket, final Bucket backupBucket, final String id) {
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
                        backupBucket.getBucketArn(),
                        backupBucket.getBucketArn() + "/*",
                        publicBucket.getBucketArn(),
                        publicBucket.getBucketArn() + "/*"
                ))
                .build();
        psBucketObjectAccess.setEffect(Effect.ALLOW);
        ec2Policy.addStatements(psBucketObjectAccess);

        PolicyStatement psDescribeUserPoolClient = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("cognito-idp:DescribeUserPoolClient"))
                .resources(List.of(
                        userPoolArn
                ))
                .build();
        psDescribeUserPoolClient.setEffect(Effect.ALLOW);
        ec2Policy.addStatements(psDescribeUserPoolClient);

        PolicyStatement psRDSSecrets = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("secretsmanager:GetSecretValue"))
                .resources(List.of(
                        auroraServerlessV2SecretArn,
                        discourseSettingsSecretArn,
                        verifiedSeSDomainSecretArn
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

    private void createLaunchTemplate(final String domainName, final String sesDomain, final String notificationEmail, final String developerEmails,
                                      final String auroraServerlessV2SecretArn, final String verifiedSeSDomainSecretArn, final String discourseSettingsSecretArn,
                                      final String redisAttrPrimaryEndPointAddress, final String userPoolId, final String userPoolClientId, final SecurityGroup ec2SecurityGroup,
                                      final Bucket publicBucket, final Bucket backupBucket,
                                      final Environment env, final String id) {
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
                "aws s3 cp s3://" + backupBucket.getBucketName() + "/app.yml.template ./containers/app.yml.template",
                "aws s3 cp s3://" + backupBucket.getBucketName() + "/smtp_credentials_generate.py ./smtp_credentials_generate.py",
                "echo -e 'export DISCOURSE_DB_USERNAME=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + auroraServerlessV2SecretArn + " --query SecretString --output text | jq -r .username)' > discourse-env",
                "echo -e 'export DISCOURSE_DB_PASSWORD=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + auroraServerlessV2SecretArn + " --query SecretString --output text | jq -r .password)' >> discourse-env",
                "echo -e 'export DISCOURSE_DB_HOST=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + auroraServerlessV2SecretArn + " --query SecretString --output text | jq -r .host)' >> discourse-env",
                "echo -e 'export DISCOURSE_DB_NAME=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + auroraServerlessV2SecretArn + " --query SecretString --output text | jq -r .dbname)' >> discourse-env",
                "echo -e 'export DISCOURSE_DB_PORT=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + auroraServerlessV2SecretArn + " --query SecretString --output text | jq -r .port)' >> discourse-env",
                "echo -e 'export PGPASSWORD=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + auroraServerlessV2SecretArn + " --query SecretString --output text | jq -r .password)' >> discourse-env",
                "echo -e 'export DISCOURSE_REDIS_HOST=" + redisAttrPrimaryEndPointAddress + "' >> discourse-env",
                "echo -e 'export DISCOURSE_HOSTNAME=" + domainName + "' >> discourse-env",
                "echo -e 'export DISCOURSE_DEVELOPER_EMAILS=" + developerEmails + "' >> discourse-env",
                "echo -e 'export DISCOURSE_SMTP_ADDRESS=email-smtp." + env.getRegion() + ".amazonaws.com' >> discourse-env",
                "echo -e 'export DISCOURSE_SMTP_PORT=587' >> discourse-env",
                "echo -e 'export DISCOURSE_SMTP_USER_NAME=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + verifiedSeSDomainSecretArn + " --query SecretString --output text | jq -r .AccessKey)' >> discourse-env",
                "echo -e 'export DISCOURSE_SMTP_PASSWORD=$(python3 smtp_credentials_generate.py $(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + verifiedSeSDomainSecretArn + " --query SecretString --output text | jq -r .SecretAccessKey) " + env.getRegion() + ")' >> discourse-env",
                "echo -e 'export DISCOURSE_SMTP_DOMAIN=" + sesDomain + "' >> discourse-env",
                "echo -e 'export DISCOURSE_NOTIFICATION_EMAIL=" + notificationEmail + "' >> discourse-env",
                "echo -e 'export DOCKER_USER=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + discourseSettingsSecretArn + " --query SecretString --output text | jq -r .DOCKER_USER)' >> discourse-env",
                "echo -e 'export DOCKER_PASSWORD=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + discourseSettingsSecretArn + " --query SecretString --output text | jq -r .DOCKER_PASSWORD)' >> discourse-env",
                "echo -e 'export DISCOURSE_ADMIN_USERNAME=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + discourseSettingsSecretArn + " --query SecretString --output text | jq -r .DISCOURSE_ADMIN_USERNAME)' >> discourse-env",
                "echo -e 'export DISCOURSE_ADMIN_EMAIL=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + discourseSettingsSecretArn + " --query SecretString --output text | jq -r .DISCOURSE_ADMIN_EMAIL)' >> discourse-env",
                "echo -e 'export DISCOURSE_ADMIN_PASSWORD=$(aws secretsmanager get-secret-value --region " + env.getRegion() + " --secret-id " + discourseSettingsSecretArn + " --query SecretString --output text | jq -r .DISCOURSE_ADMIN_PASSWORD)' >> discourse-env",
                "echo -e 'export DISCOURSE_S3_REGION=" + env.getRegion() + "' >> discourse-env",
                "echo -e 'export DISCOURSE_S3_UPLOAD_BUCKET=" + publicBucket.getBucketName() + "' >> discourse-env",
                "echo -e 'export DISCOURSE_S3_BACKUP_BUCKET=" + backupBucket.getBucketName() + "/backup' >> discourse-env",
                "echo -e 'export DISCOURSE_S3_BUCKET=" + publicBucket.getBucketName() + "' >> discourse-env",
                "echo -e 'export DISCOURSE_S3_CDN_URL=" + domainName + "' >> discourse-env",
                "echo -e 'export DISCOURSE_CDN_URL=" + domainName + "' >> discourse-env",
                "echo -e 'export DISCOURSE_OPENID_CONNECT_ENABLED=true' >> discourse-env",
                "echo -e 'export DISCOURSE_OPENID_CONNECT_CLIENT_ID=" + userPoolClientId + "' >> discourse-env",
                "echo -e 'export DISCOURSE_OPENID_CONNECT_CLIENT_SECRET=$(aws cognito-idp describe-user-pool-client --region " + env.getRegion() + " --user-pool-id=" + userPoolId + " --client-id=" + userPoolClientId + " --query UserPoolClient.ClientSecret --output text)' >> discourse-env",
                "echo -e 'export DISCOURSE_OPENID_CONNECT_DISCOVERY_DOCUMENT=https://cognito-idp." + env.getRegion() + ".amazonaws.com/" + userPoolId + "/.well-known/openid-configuration' >> discourse-env",
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
                "aws s3 cp s3://" + backupBucket.getBucketName() + "/adminenv.rake ./shared/standalone/adminenv.rake",
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

    private void createAutoScalingGroup(final Vpc vpc, final String privateEC2SubnetName, final String id) {
        autoScalingGroup = AutoScalingGroup.Builder.create(this, id + "AutoScalingGroup")
                .launchTemplate(launchTemplate)
                .maxCapacity(2)
                .minCapacity(1)
                .healthCheck(software.amazon.awscdk.services.autoscaling.HealthCheck
                        .elb(ElbHealthCheckOptions.builder()
                                .grace(Duration.minutes(25))
                                .build()))
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder().subnetGroupName(privateEC2SubnetName).build())
                .build();
    }

    private void createApplicationTargetGroup(final Vpc vpc, final String id) {
        applicationTargetGroup = ApplicationTargetGroup.Builder.create(this, id + "ApplicationTargetGroup")
                .vpc(vpc)
                .port(80)
                .protocol(ApplicationProtocol.HTTP)
                .targets(List.of(autoScalingGroup))
                .deregistrationDelay(Duration.seconds(30))
                .healthCheck(HealthCheck.builder()
                        .enabled(true)
                        .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.HTTP)
                        .path("/")
                        .healthyThresholdCount(2)
                        .unhealthyThresholdCount(5)
                        .interval(Duration.seconds(30))
                        .build())
                .build();
    }

    private void addTargetGroupsToLoadBalancer(final String httpsListenerArn, final SecurityGroup loadBalancerSecurityGroup,
                                               final String customCloudFrontALBHeaderCheckHeader, final String customCloudFrontALBHeaderCheckValue,
                                               final String id) {
        IApplicationListener httpsListener = ApplicationListener.fromApplicationListenerAttributes(this, "DiscourseHttpsListener", ApplicationListenerAttributes.builder()
                .listenerArn(httpsListenerArn)
                .securityGroup(loadBalancerSecurityGroup)
                .build());
        httpsListener.addTargetGroups(id + "LoadBalancerHTTPSTargetGroupConditional", AddApplicationTargetGroupsProps.builder()
                .targetGroups(List.of(applicationTargetGroup))
                .conditions(List.of(ListenerCondition.httpHeader(customCloudFrontALBHeaderCheckHeader, List.of(customCloudFrontALBHeaderCheckValue))))
                .priority(1)
                .build());
    }
}
