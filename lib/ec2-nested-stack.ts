import {Construct} from 'constructs';
import {Duration, NestedStack, NestedStackProps} from "aws-cdk-lib";
import {
    AmazonLinuxCpuType,
    AmazonLinuxEdition,
    AmazonLinuxGeneration,
    AmazonLinuxStorage,
    BlockDeviceVolume,
    CfnKeyPair,
    InstanceClass,
    InstanceSize,
    InstanceType,
    LaunchTemplate,
    MachineImage,
    SecurityGroup,
    SubnetType,
    UserData,
    Vpc
} from "aws-cdk-lib/aws-ec2";
import {
    ApplicationLoadBalancer,
    ApplicationProtocol,
    ApplicationTargetGroup,
    ListenerAction,
    ListenerCertificate,
    ListenerCondition,
    Protocol
} from "aws-cdk-lib/aws-elasticloadbalancingv2";
import {Bucket} from "aws-cdk-lib/aws-s3";
import {Effect, ManagedPolicy, PolicyStatement, Role, ServicePrincipal} from "aws-cdk-lib/aws-iam";
import {AutoScalingGroup, HealthCheck} from "aws-cdk-lib/aws-autoscaling";

interface EC2NestedStackProps extends NestedStackProps {
    readonly vpc: Vpc;
    readonly loadBalancerSecurityGroup: SecurityGroup;
    readonly ec2SecurityGroup: SecurityGroup;
    readonly certificateArn: string;
    readonly userPoolArn: string;
    readonly userPoolId: string;
    readonly userPoolClientId: string;
    readonly auroraServerlessV2SecretArn: string;
    readonly verifiedSeSDomainSecretArn: string;
    readonly publicBucket: Bucket;
    readonly backupBucket: Bucket;
    readonly redisAttrPrimaryEndPointAddress: string;
    readonly privateEC2SubnetName: string;
    readonly customHeaderName: string;
    readonly customHeaderValue: string;
    readonly smtpUserAccessKeyId: string;
}

export class EC2NestedStack extends NestedStack {
    loadBalancer: ApplicationLoadBalancer;

    constructor(scope: Construct, id: string, props: EC2NestedStackProps) {
        super(scope, id, props);

        const discourseSettingsSecretArn = process.env.CDK_DEPLOY_DISCOURSE_SETTINGS_SECRET_ARN || '';
        const domainName = process.env.CDK_DEPLOY_DISCOURSE_DOMAIN_NAME || '';
        const developerEmails = process.env.CDK_DEPLOY_DISCOURSE_DEVELOPER_EMAILS || '';
        const sesDomain = process.env.CDK_DEPLOY_DISCOURSE_SES_SMTP_DOMAIN_NAME || '';
        const notificationEmail = process.env.CDK_DEPLOY_DISCOURSE_NOTIFICATION_EMAIL || '';

        this.loadBalancer = new ApplicationLoadBalancer(this, id + "LoadBalancer", {
            vpc: props.vpc,
            vpcSubnets: {
                subnetType: SubnetType.PUBLIC
            },
            securityGroup: props.loadBalancerSecurityGroup,
            http2Enabled: true,
            idleTimeout: Duration.seconds(60),
            internetFacing: true
        });
        this.loadBalancer.setAttribute("routing.http.xff_header_processing.mode", "preserve");
        this.loadBalancer.setAttribute("routing.http.preserve_host_header.enabled", "true");

        const httpsListener = this.loadBalancer.addListener(id + "LoadBalancerHTTPSListener", {
            port: 443,
            certificates: [ListenerCertificate.fromArn(props.certificateArn)],
            protocol: ApplicationProtocol.HTTPS,
            defaultAction: ListenerAction.fixedResponse(403, {
                messageBody: 'Access denied'
            })
        });

        const ec2Policy = new ManagedPolicy(this, id + "EC2");
        const psListBuckets = new PolicyStatement({
            effect: Effect.ALLOW,
            actions: [
                's3:ListAllMyBuckets', 's3:ListBucket'
            ],
            resources: ['*']
        });
        ec2Policy.addStatements(psListBuckets);

        const psBucketObjectAccess = new PolicyStatement({
            effect: Effect.ALLOW,
            actions: ['s3:*'],
            resources: [
                props.backupBucket.bucketArn,
                props.backupBucket.bucketArn + '/*',
                props.publicBucket.bucketArn,
                props.publicBucket.bucketArn + '/*',
            ]
        });
        ec2Policy.addStatements(psBucketObjectAccess);

        const psDescribeUserPoolClient = new PolicyStatement({
            effect: Effect.ALLOW,
            actions: ['cognito-idp:DescribeUserPoolClient'],
            resources: [
                props.userPoolArn
            ]
        });
        ec2Policy.addStatements(psDescribeUserPoolClient);

        const psRDSSecrets = new PolicyStatement({
            effect: Effect.ALLOW,
            actions: ['secretsmanager:GetSecretValue'],
            resources: [
                props.auroraServerlessV2SecretArn,
                discourseSettingsSecretArn,
                props.verifiedSeSDomainSecretArn
            ]

        });
        ec2Policy.addStatements(psRDSSecrets);

        const ec2Role = new Role(this, id + "EC2Role", {
            assumedBy: new ServicePrincipal('ec2.amazonaws.com'),
            managedPolicies: [ec2Policy, ManagedPolicy.fromAwsManagedPolicyName('AmazonSSMManagedInstanceCore')],
            description: 'Role for discourse ec2 instances to access other services such as S3'
        });

        const keyPair = new CfnKeyPair(this, id + "EC2KeyPair", {
            keyName: id + '-discourse'
        });

        const userData = UserData.forLinux();
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
            "aws s3 cp s3://" + props.backupBucket.bucketName + "/app.yml.template ./containers/app.yml.template",
            "aws s3 cp s3://" + props.backupBucket.bucketName + "/smtp_credentials_generate.py ./smtp_credentials_generate.py",
            "echo -e 'export DISCOURSE_DB_USERNAME=$(aws secretsmanager get-secret-value --region " + this.region + " --secret-id " + props.auroraServerlessV2SecretArn + " --query SecretString --output text | jq -r .username)' > discourse-env",
            "echo -e 'export DISCOURSE_DB_PASSWORD=$(aws secretsmanager get-secret-value --region " + this.region + " --secret-id " + props.auroraServerlessV2SecretArn + " --query SecretString --output text | jq -r .password)' >> discourse-env",
            "echo -e 'export DISCOURSE_DB_HOST=$(aws secretsmanager get-secret-value --region " + this.region + " --secret-id " + props.auroraServerlessV2SecretArn + " --query SecretString --output text | jq -r .host)' >> discourse-env",
            "echo -e 'export DISCOURSE_DB_NAME=$(aws secretsmanager get-secret-value --region " + this.region + " --secret-id " + props.auroraServerlessV2SecretArn + " --query SecretString --output text | jq -r .dbname)' >> discourse-env",
            "echo -e 'export DISCOURSE_DB_PORT=$(aws secretsmanager get-secret-value --region " + this.region + " --secret-id " + props.auroraServerlessV2SecretArn + " --query SecretString --output text | jq -r .port)' >> discourse-env",
            "echo -e 'export PGPASSWORD=$(aws secretsmanager get-secret-value --region " + this.region + " --secret-id " + props.auroraServerlessV2SecretArn + " --query SecretString --output text | jq -r .password)' >> discourse-env",
            "echo -e 'export DISCOURSE_REDIS_HOST=" + props.redisAttrPrimaryEndPointAddress + "' >> discourse-env",
            "echo -e 'export DISCOURSE_HOSTNAME=" + domainName + "' >> discourse-env",
            "echo -e 'export DISCOURSE_DEVELOPER_EMAILS=" + developerEmails + "' >> discourse-env",
            "echo -e 'export DISCOURSE_SMTP_ADDRESS=email-smtp." + this.region + ".amazonaws.com' >> discourse-env",
            "echo -e 'export DISCOURSE_SMTP_PORT=587' >> discourse-env",
            "echo -e 'export DISCOURSE_SMTP_USER_NAME=" + props.smtpUserAccessKeyId + "' >> discourse-env",
            "echo -e 'export DISCOURSE_SMTP_PASSWORD=$(python3 smtp_credentials_generate.py $(aws secretsmanager get-secret-value --region " + this.region + " --secret-id " + props.verifiedSeSDomainSecretArn + " --query SecretString --output text | jq -r .SecretAccessKey) " + this.region + ")' >> discourse-env",
            "echo -e 'export DISCOURSE_SMTP_DOMAIN=" + sesDomain + "' >> discourse-env",
            "echo -e 'export DISCOURSE_NOTIFICATION_EMAIL=" + notificationEmail + "' >> discourse-env",
            "echo -e 'export DOCKER_USER=$(aws secretsmanager get-secret-value --region " + this.region + " --secret-id " + discourseSettingsSecretArn + " --query SecretString --output text | jq -r .DOCKER_USER)' >> discourse-env",
            "echo -e 'export DOCKER_PASSWORD=$(aws secretsmanager get-secret-value --region " + this.region + " --secret-id " + discourseSettingsSecretArn + " --query SecretString --output text | jq -r .DOCKER_PASSWORD)' >> discourse-env",
            "echo -e 'export DISCOURSE_ADMIN_USERNAME=$(aws secretsmanager get-secret-value --region " + this.region + " --secret-id " + discourseSettingsSecretArn + " --query SecretString --output text | jq -r .DISCOURSE_ADMIN_USERNAME)' >> discourse-env",
            "echo -e 'export DISCOURSE_ADMIN_EMAIL=$(aws secretsmanager get-secret-value --region " + this.region + " --secret-id " + discourseSettingsSecretArn + " --query SecretString --output text | jq -r .DISCOURSE_ADMIN_EMAIL)' >> discourse-env",
            "echo -e 'export DISCOURSE_ADMIN_PASSWORD=$(aws secretsmanager get-secret-value --region " + this.region + " --secret-id " + discourseSettingsSecretArn + " --query SecretString --output text | jq -r .DISCOURSE_ADMIN_PASSWORD)' >> discourse-env",
            "echo -e 'export DISCOURSE_S3_REGION=" + this.region + "' >> discourse-env",
            "echo -e 'export DISCOURSE_S3_UPLOAD_BUCKET=" + props.publicBucket.bucketName + "' >> discourse-env",
            "echo -e 'export DISCOURSE_S3_BACKUP_BUCKET=" + props.backupBucket.bucketName + "/backup' >> discourse-env",
            "echo -e 'export DISCOURSE_S3_BUCKET=" + props.publicBucket.bucketName + "' >> discourse-env",
            "echo -e 'export DISCOURSE_S3_CDN_URL=" + domainName + "' >> discourse-env",
            "echo -e 'export DISCOURSE_CDN_URL=" + domainName + "' >> discourse-env",
            "echo -e 'export DISCOURSE_OPENID_CONNECT_ENABLED=true' >> discourse-env",
            "echo -e 'export DISCOURSE_OPENID_CONNECT_CLIENT_ID=" + props.userPoolClientId + "' >> discourse-env",
            "echo -e 'export DISCOURSE_OPENID_CONNECT_CLIENT_SECRET=$(aws cognito-idp describe-user-pool-client --region " + this.region + " --user-pool-id=" + props.userPoolId + " --client-id=" + props.userPoolClientId + " --query UserPoolClient.ClientSecret --output text)' >> discourse-env",
            "echo -e 'export DISCOURSE_OPENID_CONNECT_DISCOVERY_DOCUMENT=https://cognito-idp." + this.region + ".amazonaws.com/" + props.userPoolId + "/.well-known/openid-configuration' >> discourse-env",
            "echo -e 'docker login -u $DOCKER_USER --password-stdin <<< $DOCKER_PASSWORD' >> discourse-env",
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
            "aws s3 cp s3://" + props.backupBucket.bucketName + "/adminenv.rake ./shared/standalone/adminenv.rake",
            "./launcher run app 'chown discourse:root /shared/adminenv.rake'",
            "./launcher run app 'cp -v /shared/adminenv.rake . | rake admin:fromenv' --docker-args '--privileged -w /var/www/discourse/lib/tasks'");

        const mi = MachineImage.latestAmazonLinux({
            cpuType: AmazonLinuxCpuType.X86_64,
            edition: AmazonLinuxEdition.STANDARD,
            generation: AmazonLinuxGeneration.AMAZON_LINUX_2,
            storage: AmazonLinuxStorage.EBS,
            userData: userData
        });

        const launchTemplate = new LaunchTemplate(this, id + "LaunchTemplate", {
            machineImage: mi,
            instanceType: InstanceType.of(InstanceClass.T3, InstanceSize.MEDIUM),
            keyName: keyPair.keyName,
            role: ec2Role,
            securityGroup: props.ec2SecurityGroup,
            blockDevices: [
                {
                    deviceName: '/dev/xvda',
                    volume: BlockDeviceVolume.ebs(25)
                }
            ],
            userData: userData
        });

        const autoScalingGroup = new AutoScalingGroup(this, id + "AutoScalingGroup", {
            launchTemplate: launchTemplate,
            maxCapacity: 2,
            minCapacity: 1,
            healthCheck: HealthCheck.elb({
                grace: Duration.minutes(25)
            }),
            vpc: props.vpc,
            vpcSubnets: {
                subnetGroupName: props.privateEC2SubnetName
            }
        });

        const applicationTargetGroup = new ApplicationTargetGroup(this, id + "ApplicationTargetGroup", {
            vpc: props.vpc,
            port: 80,
            protocol: ApplicationProtocol.HTTP,
            targets: [autoScalingGroup],
            deregistrationDelay: Duration.seconds(30),
            healthCheck: {
                enabled: true,
                protocol: Protocol.HTTP,
                path: '/',
                healthyThresholdCount: 2,
                unhealthyThresholdCount: 5,
                interval: Duration.seconds(30)
            }
        });

        httpsListener.addTargetGroups(id + "LoadBalancerHTTPSTargetGroupConditional", {
            targetGroups: [applicationTargetGroup],
            conditions: [ListenerCondition.httpHeader(props.customHeaderName, [props.customHeaderValue])],
            priority: 1
        });
    }
}
