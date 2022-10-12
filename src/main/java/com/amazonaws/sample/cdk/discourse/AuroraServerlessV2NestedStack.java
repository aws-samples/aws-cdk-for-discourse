package com.amazonaws.sample.cdk.discourse;

import software.amazon.awscdk.CfnResource;
import software.amazon.awscdk.NestedStack;
import software.amazon.awscdk.customresources.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.rds.InstanceProps;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.services.secretsmanager.ISecret;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public class AuroraServerlessV2NestedStack extends NestedStack {
    private DatabaseCluster rdsCluster;
    private final Vpc vpc;
    private final SecurityGroup securityGroup;
    private final SubnetConfiguration subnet;

    public AuroraServerlessV2NestedStack(final Construct scope, final String id, final Vpc vpc, final SecurityGroup securityGroup, final SubnetConfiguration subnet) {
        super(scope, id);
        this.vpc = vpc;
        this.securityGroup = securityGroup;
        this.subnet = subnet;
        createAuroraServerlessV2Cluster(id);
    }

    public ISecret getSecret() {
        return this.rdsCluster.getSecret();
    }

    private void createAuroraServerlessV2Cluster(final String id) {
        int dbClusterInstanceCount = 1;

        rdsCluster = DatabaseCluster.Builder.create(this, id + "Postgres")
                .engine(DatabaseClusterEngine.auroraPostgres(AuroraPostgresClusterEngineProps.builder()
                        .version(AuroraPostgresEngineVersion.VER_13_7)
                        .build()))
                .credentials(Credentials.fromGeneratedSecret("discourse"))
                .instanceProps(InstanceProps.builder()
                        .vpc(vpc)
                        .securityGroups(List.of(securityGroup))
                        .vpcSubnets(SubnetSelection.builder().subnetGroupName(subnet.getName()).build())
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
}
