import {Construct} from 'constructs';
import {NestedStack, NestedStackProps} from "aws-cdk-lib";
import {SecurityGroup, SubnetConfiguration, Vpc} from "aws-cdk-lib/aws-ec2";
import {CfnReplicationGroup, CfnSubnetGroup} from "aws-cdk-lib/aws-elasticache";

interface RedisNestedStackProps extends NestedStackProps {
    readonly vpc: Vpc;
    readonly securityGroup: SecurityGroup;
    readonly subnet: SubnetConfiguration;
}

export class RedisNestedStack extends NestedStack {
    redis: CfnReplicationGroup;
    redisSubnetGroup: CfnSubnetGroup;

    constructor(scope: Construct, id: string, props: RedisNestedStackProps) {
        super(scope, id, props);
        this.redisSubnetGroup = new CfnSubnetGroup(this, id + "RedisSubnetGroup", {
            subnetIds: props.vpc.selectSubnets({
                subnetGroupName: props.subnet.name
            }).subnetIds,
            description: 'Redis subnet group'
        });

        this.redis = new CfnReplicationGroup(this, id + "Redis", {
            autoMinorVersionUpgrade: true,
            engine: 'redis',
            replicationGroupDescription: 'Discourse redis',
            replicasPerNodeGroup: 1,
            numNodeGroups: 1,
            cacheNodeType: 'cache.t4g.medium',
            multiAzEnabled: true,
            securityGroupIds: [props.securityGroup.securityGroupId]
        });
        this.redis.addDependsOn(this.redisSubnetGroup);
        this.redis.addPropertyOverride('CacheSubnetGroupName', this.redisSubnetGroup.ref);
    }
}
