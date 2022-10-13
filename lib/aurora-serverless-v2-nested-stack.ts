import {Construct} from 'constructs';
import {CfnResource, NestedStack, NestedStackProps} from "aws-cdk-lib";
import {InstanceType, SecurityGroup, SubnetConfiguration, Vpc} from "aws-cdk-lib/aws-ec2";
import {
    AuroraPostgresEngineVersion,
    CfnDBCluster,
    CfnDBInstance,
    Credentials,
    DatabaseCluster,
    DatabaseClusterEngine
} from "aws-cdk-lib/aws-rds";
import {AwsCustomResource, AwsCustomResourcePolicy, PhysicalResourceId} from "aws-cdk-lib/custom-resources";

interface AuroraServerlessV2NestedStackProps extends NestedStackProps {
    readonly vpc: Vpc;
    readonly securityGroup: SecurityGroup;
    readonly subnet: SubnetConfiguration;
}

export class AuroraServerlessV2NestedStack extends NestedStack {
    rdsCluster: DatabaseCluster;

    constructor(scope: Construct, id: string, props: AuroraServerlessV2NestedStackProps) {
        super(scope, id, props);
        const dbClusterInstanceCount = 1;

        this.rdsCluster = new DatabaseCluster(this, id + "Postgres", {
            engine: DatabaseClusterEngine.auroraPostgres({
                version: AuroraPostgresEngineVersion.VER_13_7
            }),
            credentials: Credentials.fromGeneratedSecret('discourse'),
            instanceProps: {
                vpc: props.vpc,
                securityGroups: [props.securityGroup],
                vpcSubnets: {
                    subnetGroupName: props.subnet.name
                },
                autoMinorVersionUpgrade: true,
                allowMajorVersionUpgrade: false,
                publiclyAccessible: false,
                instanceType: new InstanceType('serverless')
            },
            defaultDatabaseName: 'discourse',
            instances: dbClusterInstanceCount
        });
        const applyScalingAction = {
            service: "RDS",
            action: "modifyDBCluster",
            parameters: {
                DBClusterIdentifier: this.rdsCluster.clusterIdentifier,
                ServerlessV2ScalingConfiguration: {
                    MinCapacity: 2,
                    MaxCapacity: 16,
                },
            },
            physicalResourceId: PhysicalResourceId.of(this.rdsCluster.clusterIdentifier)
        };

        // Create a custom resource to apply the scaling configuration.
        const configurator = new AwsCustomResource(this, id + "AuroraScaling", {
            onCreate: applyScalingAction,
            onUpdate: applyScalingAction,
            policy: AwsCustomResourcePolicy.fromSdkCalls({
                resources: AwsCustomResourcePolicy.ANY_RESOURCE,
            }),
        });

        const cfnCluster = this.rdsCluster.node.defaultChild as CfnDBCluster;
        cfnCluster.addPropertyOverride("EngineMode", "provisioned");
        configurator.node.addDependency(cfnCluster);

        const cfnConfigurator = configurator.node.findChild("Resource").node.defaultChild as CfnResource;
        for (let i = 1; i <= dbClusterInstanceCount; i++) {
            const instance = this.rdsCluster.node.findChild(`Instance${i}`) as CfnDBInstance;
            instance.addDependsOn(cfnConfigurator);
        }
    }
}
