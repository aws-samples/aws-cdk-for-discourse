package com.amazonaws.sample.cdk.discourse;

import software.amazon.awscdk.NestedStack;
import software.amazon.awscdk.services.certificatemanager.CertificateValidation;
import software.amazon.awscdk.services.certificatemanager.DnsValidatedCertificate;
import software.amazon.awscdk.services.route53.IPublicHostedZone;
import software.constructs.Construct;

public class CertificateNestedStack extends NestedStack {

    public DnsValidatedCertificate certificate;

    public CertificateNestedStack(final Construct scope, final String id, final IPublicHostedZone hostedZone, final String domainName) {
        super(scope, id);

        createCertificate(hostedZone, domainName, id);
    }

    public String getCertificateArn() {
        return certificate.getCertificateArn();
    }

    private void createCertificate(final IPublicHostedZone hostedZone, final String domainName, final String id) {
        certificate = DnsValidatedCertificate.Builder.create(this, id + "Certificate")
                .hostedZone(hostedZone)
                .domainName(domainName)
                .cleanupRoute53Records(true)
                .region(getRegion())
                .validation(CertificateValidation.fromDns(hostedZone))
                .build();
    }
}
