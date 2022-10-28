import {Construct} from 'constructs';
import {Aspects, CfnResource, NestedStack, NestedStackProps} from "aws-cdk-lib";
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
        Aspects.of(this.rdsCluster).add({
            visit(node) {
                if (node instanceof CfnDBCluster) {
                    node.serverlessV2ScalingConfiguration = {
                        minCapacity: 2,
                        maxCapacity: 16,
                    }
                }
            },
        });
    }
}
