package com.amazonaws.sample.cdk.discourse;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.NestedStack;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.constructs.Construct;

import java.util.List;

public class NetworkNestedStack extends NestedStack {
    private Vpc vpc;
    private SubnetConfiguration publicSubnet;
    private SubnetConfiguration privateEC2Subnet;
    private SubnetConfiguration privateDatabaseSubnet;
    private SubnetConfiguration privateRedisSubnet;
    private SecurityGroup loadBalancerSecurityGroup;
    private SecurityGroup auroraSecurityGroup;
    private SecurityGroup redisSecurityGroup;
    private SecurityGroup ec2SecurityGroup;
    private ApplicationLoadBalancer loadBalancer;
    private ApplicationListener httpsListener;

    public NetworkNestedStack(final Construct scope, final String id, final String cidr, final String certificateArn) {
        super(scope, id);
        createPublicSubnet();
        createEC2PrivateSubnet();
        createDatabasePrivateSubnet();
        createRedisPrivateSubnet();
        createVPC(cidr, id);
        createLoadBalancerSecurityGroup(id);
        createEC2SecurityGroup(id);
        createAuroraSecurityGroup(id);
        createRedisSecurityGroup(id);
        createLoadBalancer(certificateArn, id);
    }

    public Vpc getVpc() {
        return this.vpc;
    }
    public SubnetConfiguration getPublicSubnet() {
        return this.publicSubnet;
    }
    public SubnetConfiguration getPrivateEC2Subnet() {
        return this.privateEC2Subnet;
    }
    public SubnetConfiguration getPrivateAuroraSubnet() {
        return this.privateDatabaseSubnet;
    }
    public SubnetConfiguration getPrivateRedisSubnet() {
        return this.privateRedisSubnet;
    }
    public SecurityGroup getLoadBalancerSecurityGroup() {
        return this.loadBalancerSecurityGroup;
    }
    public SecurityGroup getAuroraSecurityGroup() {
        return this.auroraSecurityGroup;
    }
    public SecurityGroup getRedisSecurityGroup() {
        return this.redisSecurityGroup;
    }
    public SecurityGroup getEc2SecurityGroup() {
        return this.ec2SecurityGroup;
    }
    public ApplicationLoadBalancer getLoadBalancer() {
        return this.loadBalancer;
    }
    public ApplicationListener getHttpsListener() { return this.httpsListener; }

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

    private void createLoadBalancer(final String certificateArn, final String id) {
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

        httpsListener = loadBalancer.addListener(id + "LoadBalancerHTTPSListener", BaseApplicationListenerProps.builder()
                .port(443)
                .certificates(List.of(ListenerCertificate.fromArn(certificateArn)))
                .protocol(ApplicationProtocol.HTTPS)
                .defaultAction(ListenerAction.fixedResponse(403, FixedResponseOptions.builder().messageBody("Access denied").build()))
                .build());
    }
}
