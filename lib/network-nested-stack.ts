import {Construct} from 'constructs';
import {NestedStack, NestedStackProps} from "aws-cdk-lib";
import {Peer, Port, SecurityGroup, SubnetConfiguration, SubnetType, Vpc} from "aws-cdk-lib/aws-ec2";

export class NetworkNestedStack extends NestedStack {
    vpc: Vpc;
    publicSubnet: SubnetConfiguration;
    privateEC2Subnet: SubnetConfiguration;
    privateAuroraSubnet: SubnetConfiguration;
    privateRedisSubnet: SubnetConfiguration;
    loadBalancerSecurityGroup: SecurityGroup;
    auroraSecurityGroup: SecurityGroup;
    redisSecurityGroup: SecurityGroup;
    ec2SecurityGroup: SecurityGroup;

    constructor(scope: Construct, id: string, props?: NestedStackProps) {
        super(scope, id, props);

        this.publicSubnet = {
            cidrMask: 24,
            name: 'public',
            subnetType: SubnetType.PUBLIC,
        };

        this.privateEC2Subnet = {
            cidrMask: 24,
            name: 'private-ec2',
            subnetType: SubnetType.PRIVATE_WITH_EGRESS,
        };

        this.privateAuroraSubnet = {
            cidrMask: 24,
            name: 'private-aurora',
            subnetType: SubnetType.PRIVATE_ISOLATED,
        };

        this.privateRedisSubnet = {
            cidrMask: 24,
            name: 'private-redis',
            subnetType: SubnetType.PRIVATE_ISOLATED,
        };

        this.vpc = new Vpc(this, id + "Vpc", {
            enableDnsHostnames: true,
            enableDnsSupport: true,
            cidr: process.env.CDK_DEPLOY_DISCOURSE_CIDR || '10.0.0.0/16',
            natGateways: 1,
            maxAzs: 2,
            subnetConfiguration: [this.publicSubnet, this.privateEC2Subnet, this.privateAuroraSubnet, this.privateRedisSubnet],
            natGatewaySubnets: {
                subnetType: SubnetType.PUBLIC,
            }
        });

        this.loadBalancerSecurityGroup = new SecurityGroup(this, id + "LoadBalancerSecurityGroup", {
            allowAllOutbound: true,
            vpc: this.vpc
        });
        this.loadBalancerSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(443));

        this.ec2SecurityGroup = new SecurityGroup(this, id + "EC2SecurityGroup", {
            allowAllOutbound: true,
            vpc: this.vpc
        });
        this.ec2SecurityGroup.addIngressRule(Peer.securityGroupId(this.loadBalancerSecurityGroup.securityGroupId), Port.tcp(80));

        this.auroraSecurityGroup = new SecurityGroup(this, id + "AuroraSecurityGroup", {
            allowAllOutbound: true,
            vpc: this.vpc
        });
        this.auroraSecurityGroup.addIngressRule(Peer.securityGroupId(this.ec2SecurityGroup.securityGroupId), Port.tcp(5432));

        this.redisSecurityGroup = new SecurityGroup(this, id + "RedisSecurityGroup", {
            allowAllOutbound: true,
            vpc: this.vpc
        });
        this.redisSecurityGroup.addIngressRule(Peer.securityGroupId(this.ec2SecurityGroup.securityGroupId), Port.tcp(6379));
    }
}
