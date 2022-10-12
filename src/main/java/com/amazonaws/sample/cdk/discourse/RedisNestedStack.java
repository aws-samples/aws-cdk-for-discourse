package com.amazonaws.sample.cdk.discourse;

import software.amazon.awscdk.NestedStack;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.elasticache.CfnReplicationGroup;
import software.amazon.awscdk.services.elasticache.CfnSubnetGroup;
import software.constructs.Construct;

import java.util.List;
import java.util.stream.Collectors;

public class RedisNestedStack extends NestedStack {
    private final Vpc vpc;
    private SecurityGroup securityGroup;
    private final SubnetConfiguration subnet;
    public CfnSubnetGroup redisSubnetGroup;
    public CfnReplicationGroup redis;

    public RedisNestedStack(final Construct scope, final String id, final Vpc vpc, final SecurityGroup securityGroup, final SubnetConfiguration subnet) {
        super(scope, id);
        this.vpc = vpc;
        this.securityGroup = securityGroup;
        this.subnet = subnet;
        createRedisSubnetGroup(id);
        createRedis(id);
    }

    public String getAttrPrimaryEndPointAddress() {
        return redis.getAttrPrimaryEndPointAddress();
    }

    private void createRedisSubnetGroup(final String id) {
        redisSubnetGroup = CfnSubnetGroup.Builder.create(this, id + "RedisSubnetGroup")
                .subnetIds(vpc.selectSubnets(SubnetSelection.builder().subnetGroupName(subnet.getName()).build()).getSubnets().stream().map(ISubnet::getSubnetId).collect(Collectors.toList()))
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
                .securityGroupIds(List.of(securityGroup.getSecurityGroupId()))
                .build();
        redis.addDependsOn(redisSubnetGroup);
        redis.addPropertyOverride("CacheSubnetGroupName", redisSubnetGroup.getRef());
    }
}
