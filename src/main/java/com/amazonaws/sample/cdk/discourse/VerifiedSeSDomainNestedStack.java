package com.amazonaws.sample.cdk.discourse;

import software.amazon.awscdk.NestedStack;
import software.amazon.awscdk.SecretValue;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.route53.CfnRecordSet;
import software.amazon.awscdk.services.route53.IPublicHostedZone;
import software.amazon.awscdk.services.route53.PublicHostedZone;
import software.amazon.awscdk.services.route53.PublicHostedZoneAttributes;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.ses.EmailIdentity;
import software.amazon.awscdk.services.ses.Identity;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class VerifiedSeSDomainNestedStack extends NestedStack {
    private final String hostedZoneId;
    private final String hostedZoneName;
    private final String sesDomain;
    private Secret secret;

    public VerifiedSeSDomainNestedStack(final Construct scope, final String id, final String hostedZoneId, final String hostedZoneName, final String sesDomain) {
        super(scope, id);
        this.hostedZoneId = hostedZoneId;
        this.hostedZoneName = hostedZoneName;
        this.sesDomain = sesDomain;
        createVerifiedSeSDomain(id);
    }

    public Secret getSecret() {
        return secret;
    }

    private void createVerifiedSeSDomain(String id) {
        IPublicHostedZone publicHostedZone = PublicHostedZone.fromPublicHostedZoneAttributes(this, id + "SMTPPublicHostedZone", PublicHostedZoneAttributes.builder()
                .zoneName(hostedZoneName)
                .hostedZoneId(hostedZoneId)
                .build());
        EmailIdentity emailIdentity = EmailIdentity.Builder.create(this, id +"EmailIdentity")
                .identity(Identity.domain(sesDomain))
                .build();
        AtomicInteger index = new AtomicInteger(1);
        emailIdentity.getDkimRecords().forEach((r) -> CfnRecordSet.Builder.create(this, id + "DkimToken" + index.getAndIncrement())
                .hostedZoneId(publicHostedZone.getHostedZoneId())
                .type("CNAME")
                .name(r.getName())
                .resourceRecords(List.of(r.getValue()))
                .ttl("60")
                .build());

        User smtpUser = User.Builder.create(this, id + "SMTPUser")
                .passwordResetRequired(false)
                .build();
        smtpUser.attachInlinePolicy(Policy.Builder.create(this, id + "SMTPSendRawEmailPolicy")
                .statements(List.of(PolicyStatement.Builder.create()
                        .resources(List.of("*"))
                        .actions(List.of("ses:SendRawEmail"))
                        .effect(Effect.ALLOW)
                        .build()))
                .build());
        AccessKey accessKey = AccessKey.Builder.create(this, id + "SMTPAccessKey")
                .user(smtpUser)
                .status(AccessKeyStatus.ACTIVE)
                .build();
        secret = Secret.Builder.create(this, id + "SMTPUserSecretAccessKeySecret")
                .secretObjectValue(Map.of(
                        "AccessKey", SecretValue.Builder.create(accessKey.getAccessKeyId()).build(),
                        "SecretAccessKey", accessKey.getSecretAccessKey()))
                .build();
    }
}
